# Guide Flutter : Date limite de candidature & Expiration des offres

## Nouveautés API

### 1. `applicationDeadline` (date limite de candidature)

Champ optionnel sur `JobResponse`, `CreateJobRequest`, `UpdateJobRequest` :

```dart
class JobResponse {
  final String id;
  final String title;
  final String description;
  // ... autres champs existants ...
  final DateTime? applicationDeadline; // 👈 NOUVEAU
  final String status; // PENDING | IN_PROGRESS | DONE | EXPIRED 👈 NOUVEAU
}
```

### 2. Nouveau statut `EXPIRED`

`JobStatus` inclut désormais `EXPIRED` en plus de `PENDING`, `IN_PROGRESS`, `DONE`.

| Statut | Signification |
|--------|---------------|
| `PENDING` | En attente de candidatures |
| `IN_PROGRESS` | En cours (un worker assigné) |
| `DONE` | Terminé |
| `EXPIRED` | Expiré (date dépassée ou fermé par le créateur) |

### 3. Nouvel endpoint

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `POST` | `/jobs/{jobId}/expire` | **Créateur** expire manuellement son annonce |

### 4. Comportement modifié

- `POST /jobs/{jobId}/apply` → refusé si `status == EXPIRED` **ou** si `applicationDeadline` est dépassée
- `GET /jobs/available` → n'inclut plus les jobs dont `applicationDeadline` est passée (même pas encore EXPIRED)
- `PUT /jobs/{id}` → accepte `applicationDeadline` optionnel
- `POST /jobs` → accepte `applicationDeadline` optionnel
- `GET /admin/stats` → inclut désormais `jobsExpired`

### 5. Auto-expiration (backend)

Un scheduler tourne tous les jours à 2h du matin et marque `EXPIRED` :
- Les jobs `PENDING` dont `applicationDeadline` est dans le passé
- Les jobs `PENDING` sans `applicationDeadline` inactifs depuis plus de 90 jours

---

## Ce que le frontend doit gérer

### 1. Afficher la date limite dans le formulaire de création/édition

```dart
// Page de création d'annonce
class CreateJobForm extends StatefulWidget { /* ... */ }

class _CreateJobFormState extends State<CreateJobForm> {
  final _titleController = TextEditingController();
  final _descriptionController = TextEditingController();
  final _priceController = TextEditingController();
  DateTime? _applicationDeadline; // null = pas de limite

  Future<void> _pickDeadline() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: now.add(Duration(days: 7)),
      firstDate: now.add(Duration(days: 1)),
      lastDate: now.add(Duration(days: 365)),
    );
    if (picked != null) {
      setState(() => _applicationDeadline = picked);
    }
  }

  Future<void> _submit() async {
    final response = await api.post('/jobs', body: {
      'title': _titleController.text,
      'description': _descriptionController.text,
      'price': double.parse(_priceController.text),
      'applicationDeadline': _applicationDeadline?.toUtc().toIso8601String(), // null si pas de date
    });
    // ...
  }

  @override
  Widget build(BuildContext context) {
    return Form(
      child: ListView(
        children: [
          // ... champs existants ...

          // Champ date limite (optionnel)
          ListTile(
            title: Text(_applicationDeadline != null
                ? 'Date limite : ${_formatDate(_applicationDeadline!)}'
                : 'Pas de date limite'),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_applicationDeadline != null)
                  IconButton(
                    icon: Icon(Icons.clear),
                    onPressed: () => setState(() => _applicationDeadline = null),
                  ),
                IconButton(
                  icon: Icon(Icons.calendar_month),
                  onPressed: _pickDeadline,
                ),
              ],
            ),
          ),

          ElevatedButton(onPressed: _submit, child: Text('Publier')),
        ],
      ),
    );
  }
}
```

### 2. Afficher le badge EXPIRED dans les listes

```dart
Widget _buildStatusBadge(String status, {DateTime? deadline}) {
  if (status == 'EXPIRED') {
    return Container(
      padding: EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.grey.shade200,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text('Expirée', style: TextStyle(fontSize: 12, color: Colors.grey.shade700)),
    );
  }

  if (status == 'PENDING' && deadline != null && deadline.isBefore(DateTime.now())) {
    return Container(
      padding: EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.orange.shade50,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text('Date limite dépassée', style: TextStyle(fontSize: 12, color: Colors.orange.shade800)),
    );
  }

  // badges existants (PENDING, IN_PROGRESS, DONE)
  switch (status) {
    case 'PENDING':
      return _badge('Disponible', Colors.green);
    case 'IN_PROGRESS':
      return _badge('En cours', Colors.blue);
    case 'DONE':
      return _badge('Terminé', Colors.grey);
    default:
      return SizedBox.shrink();
  }
}
```

### 3. Compter à rebours dans la fiche détaillée

```dart
// Dans la page de détail d'un job
class _JobDetailState extends State<JobDetailPage> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    if (job.applicationDeadline != null && job.status == 'PENDING') {
      _timer = Timer.periodic(Duration(seconds: 60), (_) => setState(() {}));
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  String _formatCountdown(DateTime deadline) {
    final remaining = deadline.difference(DateTime.now());
    if (remaining.isNegative) return 'Expirée';
    if (remaining.inDays > 0) return '${remaining.inDays}j ${remaining.inHours % 24}h restantes';
    if (remaining.inHours > 0) return '${remaining.inHours}h ${remaining.inMinutes % 60}min restantes';
    return '${remaining.inMinutes}min restantes';
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // ... en-tête du job ...

        if (job.applicationDeadline != null && job.status == 'PENDING')
          Card(
            color: job.applicationDeadline!.isBefore(DateTime.now())
                ? Colors.orange.shade50
                : Colors.blue.shade50,
            child: ListTile(
              leading: Icon(
                job.applicationDeadline!.isBefore(DateTime.now())
                    ? Icons.timer_off
                    : Icons.timer_outlined,
              ),
              title: Text(
                job.applicationDeadline!.isBefore(DateTime.now())
                    ? 'Date limite dépassée'
                    : 'Date limite de candidature',
              ),
              subtitle: Text(
                _formatCountdown(job.applicationDeadline!),
              ),
            ),
          ),

        // ... actions (postuler, etc.) ...
        // Cacher le bouton "Postuler" si EXPIRED ou deadline passée
        if (job.status == 'EXPIRED' || 
            (job.applicationDeadline != null && job.applicationDeadline!.isBefore(DateTime.now())))
          Card(
            color: Colors.grey.shade100,
            child: ListTile(
              leading: Icon(Icons.block, color: Colors.grey),
              title: Text('Candidatures fermées', style: TextStyle(color: Colors.grey)),
            ),
          )
        else
          ElevatedButton(
            onPressed: _apply,
            child: Text('Postuler'),
          ),
      ],
    );
  }
}
```

### 4. Bouton "Expirer l'annonce" pour le créateur

```dart
// Dans les actions du créateur (page détail ou "Mes annonces")
if (isCreator && job.status == 'PENDING') {
  OutlinedButton.icon(
    onPressed: () async {
      final confirm = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text('Expirer l\'annonce'),
          content: Text('Les candidatures seront fermées. Les candidats en attente seront notifiés.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text('Annuler')),
            TextButton(onPressed: () => Navigator.pop(ctx, true), child: Text('Expirer')),
          ],
        ),
      );
      if (confirm == true) {
        await api.post('/jobs/${job.id}/expire');
        // rafraîchir
      }
    },
    icon: Icon(Icons.timer_off),
    label: Text('Expirer'),
    style: OutlinedButton.styleFrom(foregroundColor: Colors.orange),
  );
}
```

### 5. Filtre "Expirées" dans "Mes annonces"

```dart
// Dans la page "Mes annonces" (créateur)
enum MyJobsFilter { all, pending, inProgress, done, expired }

class MyJobsPage extends StatefulWidget { /* ... */ }

class _MyJobsPageState extends State<MyJobsPage> {
  MyJobsFilter _filter = MyJobsFilter.all;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        // Filtres
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              _filterChip('Toutes', MyJobsFilter.all),
              _filterChip('Disponibles', MyJobsFilter.pending),
              _filterChip('En cours', MyJobsFilter.inProgress),
              _filterChip('Terminées', MyJobsFilter.done),
              _filterChip('Expirées', MyJobsFilter.expired),
            ],
          ),
        ),

        // Liste filtrée
        Expanded(
          child: Consumer<JobProvider>(
            builder: (_, provider, __) {
              final jobs = provider.myCreatedJobs.where((j) {
                switch (_filter) {
                  case MyJobsFilter.all: return true;
                  case MyJobsFilter.pending: return j.status == 'PENDING';
                  case MyJobsFilter.inProgress: return j.status == 'IN_PROGRESS';
                  case MyJobsFilter.done: return j.status == 'DONE';
                  case MyJobsFilter.expired: return j.status == 'EXPIRED';
                }
              }).toList();

              return ListView.builder(
                itemCount: jobs.length,
                itemBuilder: (_, i) => JobCard(job: jobs[i]),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _filterChip(String label, MyJobsFilter value) {
    final selected = _filter == value;
    return Padding(
      padding: EdgeInsets.only(right: 8),
      child: FilterChip(
        label: Text(label),
        selected: selected,
        onSelected: (_) => setState(() => _filter = value),
      ),
    );
  }
}
```

### 6. Gérer le nouveau champ dans le modèle Dart

```dart
// models/job.dart
class Job {
  final String id;
  final String title;
  final String description;
  final String? location;
  final double price;
  final String creatorId;
  final String creatorUsername;
  final String? workerId;
  final String? workerUsername;
  final String status;
  final DateTime createdAt;
  final DateTime updatedAt;
  final List<String> images;
  final String? categoryId;
  final String? categoryName;
  final DateTime? applicationDeadline; // 👈 NOUVEAU

  Job({
    required this.id,
    required this.title,
    required this.description,
    this.location,
    required this.price,
    required this.creatorId,
    required this.creatorUsername,
    this.workerId,
    this.workerUsername,
    required this.status,
    required this.createdAt,
    required this.updatedAt,
    required this.images,
    this.categoryId,
    this.categoryName,
    this.applicationDeadline, // 👈 NOUVEAU
  });

  factory Job.fromJson(Map<String, dynamic> json) {
    return Job(
      id: json['id'],
      title: json['title'],
      description: json['description'] ?? '',
      location: json['location'],
      price: (json['price'] as num).toDouble(),
      creatorId: json['creatorId'],
      creatorUsername: json['creatorUsername'],
      workerId: json['workerId'],
      workerUsername: json['workerUsername'],
      status: json['status'],
      createdAt: DateTime.parse(json['createdAt']),
      updatedAt: DateTime.parse(json['updatedAt']),
      images: List<String>.from(json['images'] ?? []),
      categoryId: json['categoryId'],
      categoryName: json['categoryName'],
      applicationDeadline: json['applicationDeadline'] != null
          ? DateTime.parse(json['applicationDeadline'])
          : null, // 👈 NOUVEAU
    );
  }

  bool get isExpired => status == 'EXPIRED';
  bool get isDeadlinePassed =>
      applicationDeadline != null && applicationDeadline!.isBefore(DateTime.now());
  bool get canApply =>
      status == 'PENDING' && !isDeadlinePassed;
}

// Pour l'envoi (create/update)
Map<String, dynamic> toCreateJson() => {
  'title': title,
  'description': description,
  'location': location,
  'price': price,
  'images': images,
  'categoryId': categoryId,
  'applicationDeadline': applicationDeadline?.toUtc().toIso8601String(),
};
```

### 7. Résumé des modifications UI nécessaires

| Élément | Changement |
|---------|-----------|
| Page création/édition annonce | Ajouter un champ "Date limite de candidature" (date picker optionnel) |
| Fiche détaillée d'un job | Afficher le compte à rebours si `applicationDeadline` défini |
| Fiche détaillée d'un job | Cacher "Postuler" si `EXPIRED` ou deadline passée |
| Fiche détaillée d'un job | Ajouter bouton "Expirer" pour le créateur si `PENDING` |
| Liste "Annonces disponibles" | Déjà filtrée côté backend, aucun changement nécessaire |
| Liste "Mes annonces" (créateur) | Ajouter filtre "Expirées", afficher badge "Expirée" |
| Modèle Dart `Job` | Ajouter `applicationDeadline`, `isExpired`, `isDeadlinePassed`, `canApply` |
| Admin dashboard | Afficher le compteur `jobsExpired` |

### 8. Format de date pour l'API

Le backend attend/renvoie les dates au format ISO 8601 UTC :

```
2026-06-15T23:59:59.000Z
```

En Dart :
```dart
final isoDate = dateTime.toUtc().toIso8601String(); // "2026-06-15T23:59:59.000Z"
final parsed = DateTime.parse(isoDate);              // conversion inverse
```
