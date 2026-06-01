# Guide UI/UX : Paiement, Modification/Annulation de job, Filtre catégorie

---

## 1. Vérifier l'état du paiement en temps réel

### Problème : ne pas montrer "Payer" pour un job déjà payé

```tsx
// ❌ MAUVAIS : toujours afficher le bouton "Payer"
<Button>Payer {job.price}€</Button>  // ⚠️ 409 si déjà payé

// ✅ BON : vérifier d'abord l'état de la transaction
```

### Solution : lookup transaction par `jobId`

Quand tu charges les détails d'un job ou la liste "Mes créations", fais un appel à `GET /payments/mine` une fois au montage et construis une `Map<jobId, PaymentResponse>` :

```tsx
// hooks/usePaymentStatus.ts
import { useQuery } from '@tanstack/react-query';
import api from '@/api/axiosInstance';

export interface PaymentInfo {
  id: string;
  jobId: string;
  status: 'HELD' | 'COMPLETED' | 'CANCELLED';
  amount: number;
  netAmount: number;
}

export function usePaymentStatus() {
  return useQuery({
    queryKey: ['my-transactions'],
    queryFn: async (): Promise<PaymentInfo[]> => {
      const { data } = await api.get('/payments');
      return data;
    },
    staleTime: 30_000, // 30s avant refetch
  });
}
```

### Dans le composant JobCard / JobDetail

```tsx
// components/JobCard.tsx
import { usePaymentStatus } from '@/hooks/usePaymentStatus';

function JobCard({ job, isCreator }: { job: JobResponse; isCreator: boolean }) {
  const { data: transactions = [] } = usePaymentStatus();

  // Chercher la transaction pour ce job
  const tx = transactions.find(t => t.jobId === job.id);

  // Déterminer l'état du paiement
  const paymentStatus: 'none' | 'held' | 'completed' | 'cancelled' =
    !tx ? 'none'
    : tx.status === 'HELD' ? 'held'
    : tx.status === 'COMPLETED' ? 'completed'
    : 'cancelled';

  return (
    <div>
      {/* ... contenu du job ... */}

      {/* 👤 CRÉATEUR : boutons de paiement */}
      {isCreator && (
        <PaymentActionButtons
          job={job}
          paymentStatus={paymentStatus}
          transactionId={tx?.id}
        />
      )}

      {/* 🧑‍💻 WORKER : badge statut paiement */}
      {!isCreator && paymentStatus !== 'none' && (
        <PaymentBadge status={paymentStatus} />
      )}
    </div>
  );
}
```

### Composant PaymentActionButtons

```tsx
// components/PaymentActionButtons.tsx
type PaymentStatus = 'none' | 'held' | 'completed' | 'cancelled';

function PaymentActionButtons({
  job,
  paymentStatus,
  transactionId,
}: {
  job: JobResponse;
  paymentStatus: PaymentStatus;
  transactionId?: string;
}) {
  // Les règles métier :
  // - PENDING : job pas encore assigné → pas de paiement
  // - IN_PROGRESS : en cours → pas de paiement
  // - DONE + payment === 'none' → bouton PAYER
  // - DONE + payment === 'held' → bouton CONFIRMER LIVRAISON
  // - DONE + payment === 'completed' → badge DÉJÀ PAYÉ

  if (job.status === 'PENDING' || job.status === 'IN_PROGRESS') {
    return null; // pas de bouton de paiement
  }

  return (
    <div className="flex gap-3 mt-4">
      {paymentStatus === 'none' && (
        <Button onClick={() => initiatePayment(job.id)}>
          Payer {job.price}€
        </Button>
      )}

      {paymentStatus === 'held' && (
        <Button
          onClick={() => confirmDelivery(transactionId!)}
          variant="success"
        >
          Confirmer la livraison ✅
        </Button>
      )}

      {paymentStatus === 'completed' && (
        <Badge variant="secondary">
          ✓ Paiement complété — {tx.netAmount}€ versé au worker
        </Badge>
      )}
    </div>
  );
}
```

### Badge côté worker

```tsx
function PaymentBadge({ status }: { status: PaymentStatus }) {
  if (status === 'completed') {
    return <Badge variant="success">Paiement reçu ✓</Badge>;
  }
  if (status === 'held') {
    return <Badge variant="warning">En attente de confirmation</Badge>;
  }
  return null;
}
```

### Rafraîchissement automatique avec React Query

```tsx
// Revalider automatiquement après avoir payé
const queryClient = useQueryClient();
const { mutate: pay } = useMutation({
  mutationFn: (jobId: string) => api.post('/payments', { jobId }),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['my-transactions'] });
    queryClient.invalidateQueries({ queryKey: ['job'] });
    toast({ title: 'Paiement initié' });
  },
  onError: (err: ApiError) => {
    if (err.errorCode === 'CONFLICT') {
      toast({ title: 'Déjà payé', description: 'Un paiement existe déjà' });
      queryClient.invalidateQueries({ queryKey: ['my-transactions'] });
    }
  },
});
```

---

## 2. Modifier et annuler un job — UI/UX

### États possibles d'un job et actions disponibles

| Statut job | Créateur peut | Worker peut |
|---|---|---|
| `PENDING` | ✏️ Modifier • 🗑️ Supprimer • 👥 Voir candidats | ❌ Rien (postuler seulement) |
| `IN_PROGRESS` | 📝 Voir • 📋 Voir messages | 📝 Voir • 💬 Messagerie |
| `DONE` | 💳 Payer • ⭐ Noter worker | ⭐ Noter créateur |

### Boutons contextuels — Code

```tsx
// components/JobActions.tsx
import { useRouter } from 'next/navigation';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import api from '@/api/axiosInstance';
import { usePaymentStatus } from '@/hooks/usePaymentStatus';

export function JobActions({ job, currentUserId }: { job: JobResponse; currentUserId: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { data: transactions = [] } = usePaymentStatus();
  const tx = transactions.find(t => t.jobId === job.id);

  const isCreator = job.creatorId === currentUserId;
  const isWorker = job.workerId === currentUserId;

  const { mutate: deleteJob } = useMutation({
    mutationFn: () => api.delete(`/jobs/${job.id}`),
    onSuccess: () => {
      toast({ title: 'Annonce supprimée' });
      queryClient.invalidateQueries({ queryKey: ['my-created-jobs'] });
      router.push('/mes-annonces');
    },
  });

  const { mutate: updateStatus } = useMutation({
    mutationFn: (status: string) => api.patch(`/jobs/${job.id}/status?status=${status}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['job', job.id] });
      toast({ title: 'Statut mis à jour' });
    },
  });

  return (
    <div className="flex flex-wrap gap-3">
      {/* ——— CRÉATEUR ——— */}

      {/* PENDING : on peut modifier et supprimer */}
      {isCreator && job.status === 'PENDING' && (
        <>
          <Button
            variant="outline"
            onClick={() => router.push(`/jobs/${job.id}/edit`)}
          >
            ✏️ Modifier
          </Button>

          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="destructive">🗑️ Supprimer</Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Supprimer l'annonce ?</AlertDialogTitle>
                <AlertDialogDescription>
                  Cette action est irréversible. Les candidatures seront supprimées.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Annuler</AlertDialogCancel>
                <AlertDialogAction onClick={() => deleteJob()}>
                  Confirmer la suppression
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      )}

      {/* IN_PROGRESS : marquer comme DONE */}
      {isWorker && job.status === 'IN_PROGRESS' && (
        <Button
          variant="default"
          onClick={() => updateStatus('DONE')}
        >
          ✅ Marquer comme terminé
        </Button>
      )}

      {/* DONE : paiement pour le créateur */}
      {isCreator && job.status === 'DONE' && !tx && (
        <Button onClick={() => pay(job.id)}>
          💳 Payer {job.price}€
        </Button>
      )}

      {isCreator && job.status === 'DONE' && tx?.status === 'HELD' && (
        <Button onClick={() => confirmDelivery(tx.id)} variant="success">
          ✅ Confirmer la livraison
        </Button>
      )}

      {isWorker && job.status === 'DONE' && (
        <Button variant="ghost" onClick={() => router.push(`/jobs/${job.id}/rate`)}>
          ⭐ Noter le créateur
        </Button>
      )}

      {isCreator && job.status === 'DONE' && tx?.status === 'COMPLETED' && (
        <Button variant="ghost" onClick={() => router.push(`/jobs/${job.id}/rate`)}>
          ⭐ Noter le worker
        </Button>
      )}

      {/* ——— WORKER : bouton messagerie ——— */}
      {(isCreator || isWorker) && job.status !== 'PENDING' && (
        <Button variant="outline" onClick={() => router.push(`/messages/${job.id}`)}>
          💬 Messages
        </Button>
      )}
    </div>
  );
}
```

### Design recommandé (Tailwind + Shadcn)

```tsx
// Layout pour la fiche détaillée d'un job
export function JobDetailLayout() {
  return (
    <div className="max-w-4xl mx-auto p-6 space-y-8">
      {/* En-tête */}
      <div className="flex justify-between items-start">
        <div>
          <h1 className="text-3xl font-bold">{job.title}</h1>
          <p className="text-muted-foreground">
            {job.categoryName} · {job.location}
          </p>
        </div>
        <Badge variant={statusToVariant(job.status)}>
          {statusLabel(job.status)}
        </Badge>
      </div>

      {/* Description */}
      <Card>
        <CardContent className="pt-6">
          <p>{job.description}</p>
        </CardContent>
      </Card>

      {/* Prix */}
      <div className="text-2xl font-semibold text-primary">
        {job.price}€
      </div>

      {/* Actions */}
      <Separator />
      <JobActions job={job} currentUserId={user.id} />
    </div>
  );
}

// Variantes Shadcn pour les statuts
function statusToVariant(status: string): 'default' | 'secondary' | 'success' | 'warning' {
  switch (status) {
    case 'PENDING': return 'secondary';
    case 'IN_PROGRESS': return 'warning';
    case 'DONE': return 'success';
    default: return 'default';
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'PENDING': return '📋 Ouvert aux candidatures';
    case 'IN_PROGRESS': return '🔧 En cours';
    case 'DONE': return '✅ Terminé';
    default: return status;
  }
}
```

---

## 3. Filtre par catégorie sur la page d'accueil

### Endpoints utilisés

| Endpoint | Usage |
|---|---|
| `GET /categories/tree` | Récupérer l'arbre hiérarchique des catégories |
| `GET /categories` | Liste plate de toutes les catégories |
| `GET /jobs/available?categoryId=xx&sort=yy` | Filtrer les jobs |

### Architecture du filtre

```tsx
// components/HomeFilters.tsx — Filtre catégorie + tri
'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import api from '@/api/axiosInstance';

interface CategoryTree {
  id: string;
  name: string;
  icon: string;
  subcategories: CategoryTree[];
}

interface HomeFiltersProps {
  onFiltersChange: (filters: { categoryId?: string; sort?: string; q?: string }) => void;
}

export function HomeFilters({ onFiltersChange }: HomeFiltersProps) {
  // Charger l'arbre des catégories
  const { data: categories = [] } = useQuery<CategoryTree[]>({
    queryKey: ['category-tree'],
    queryFn: () => api.get('/categories/tree').then(r => r.data),
    staleTime: 5 * 60_000, // 5 min
  });

  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [sort, setSort] = useState('date_desc');
  const [search, setSearch] = useState('');

  const toggleExpand = (id: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  const handleSelect = (categoryId?: string) => {
    setSelected(categoryId);
    onFiltersChange({ categoryId, sort, q: search || undefined });
  };

  return (
    <div className="space-y-6">
      {/* 🔍 Barre de recherche */}
      <Input
        placeholder="Rechercher un service..."
        value={search}
        onChange={e => setSearch(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter') handleSelect(selected);
        }}
      />

      {/* 📂 Catégories */}
      <div className="space-y-1">
        <button
          onClick={() => handleSelect(undefined)}
          className={`w-full text-left px-3 py-2 rounded-md transition-colors ${
            !selected ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'
          }`}
        >
          Toutes les catégories
        </button>

        {categories.map(cat => (
          <CategoryTreeItem
            key={cat.id}
            category={cat}
            selected={selected}
            expanded={expanded}
            onToggle={toggleExpand}
            onSelect={handleSelect}
            level={0}
          />
        ))}
      </div>

      {/* 🔄 Tri */}
      <Select value={sort} onValueChange={v => { setSort(v); onFiltersChange({ categoryId: selected, sort: v, q: search || undefined }); }}>
        <SelectTrigger>
          <SelectValue placeholder="Trier par" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="date_desc">Plus récents</SelectItem>
          <SelectItem value="date_asc">Plus anciens</SelectItem>
          <SelectItem value="price_asc">Prix croissant</SelectItem>
          <SelectItem value="price_desc">Prix décroissant</SelectItem>
        </SelectContent>
      </Select>
    </div>
  );
}

// Composant récursif pour l'arbre
function CategoryTreeItem({
  category,
  selected,
  expanded,
  onToggle,
  onSelect,
  level,
}: {
  category: CategoryTree;
  selected?: string;
  expanded: Set<string>;
  onToggle: (id: string) => void;
  onSelect: (id?: string) => void;
  level: number;
}) {
  const hasChildren = category.subcategories.length > 0;
  const isSelected = selected === category.id;
  const isExpanded = expanded.has(category.id);

  return (
    <div>
      <div className="flex items-center gap-1" style={{ paddingLeft: `${level * 16}px` }}>
        {hasChildren && (
          <button
            onClick={() => onToggle(category.id)}
            className="w-5 h-5 flex items-center justify-center text-muted-foreground hover:text-foreground"
          >
            {isExpanded ? '▾' : '▸'}
          </button>
        )}
        {!hasChildren && <div className="w-5" />}

        <button
          onClick={() => onSelect(category.id)}
          className={`flex-1 text-left px-3 py-1.5 rounded-md transition-colors text-sm ${
            isSelected ? 'bg-primary text-primary-foreground font-medium' : 'hover:bg-muted'
          }`}
        >
          {category.icon && <span className="mr-2">{category.icon}</span>}
          {category.name}
        </button>
      </div>

      {hasChildren && isExpanded && (
        <div>
          {category.subcategories.map(sub => (
            <CategoryTreeItem
              key={sub.id}
              category={sub}
              selected={selected}
              expanded={expanded}
              onToggle={onToggle}
              onSelect={onSelect}
              level={level + 1}
            />
          ))}
        </div>
      )}
    </div>
  );
}
```

### Page d'accueil qui utilise le filtre

```tsx
// app/page.tsx — Page d'accueil
export default function HomePage() {
  const [filters, setFilters] = useState<{
    categoryId?: string;
    sort?: string;
    q?: string;
  }>({});

  const { data: jobs = [], isLoading } = useQuery({
    queryKey: ['available-jobs', filters],
    queryFn: () =>
      api.get('/jobs/available', { params: filters }).then(r => r.data),
    keepPreviousData: true,
  });

  const { data: categories = [] } = useQuery({
    queryKey: ['category-tree'],
    queryFn: () => api.get('/categories/tree').then(r => r.data),
    staleTime: 5 * 60_000,
  });

  return (
    <div className="max-w-7xl mx-auto px-4 py-8">
      <h1 className="text-4xl font-bold mb-8">
        {filters.q
          ? `Résultats pour "${filters.q}"`
          : 'Services disponibles'}
      </h1>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
        {/* Sidebar filtres */}
        <aside className="lg:col-span-1">
          <HomeFilters onFiltersChange={setFilters} />
        </aside>

        {/* Grille des jobs */}
        <main className="lg:col-span-3">
          {isLoading ? (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {[1,2,3,4].map(n => <Skeleton key={n} className="h-64" />)}
            </div>
          ) : jobs.length === 0 ? (
            <EmptyState
              icon={SearchX}
              title="Aucun résultat"
              description="Essayez de modifier vos filtres ou d'élargir votre recherche"
            />
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {jobs.map(job => (
                <JobCard key={job.id} job={job} />
              ))}
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
```

### Gestion des paramètres d'URL (pour le partage de lien)

```tsx
// Synchroniser les filtres avec l'URL
import { useSearchParams, useRouter, usePathname } from 'next/navigation';

function useFiltersFromUrl() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const filters = {
    categoryId: searchParams.get('categoryId') || undefined,
    sort: searchParams.get('sort') || 'date_desc',
    q: searchParams.get('q') || undefined,
  };

  const setFilters = (newFilters: typeof filters) => {
    const params = new URLSearchParams();
    if (newFilters.categoryId) params.set('categoryId', newFilters.categoryId);
    if (newFilters.sort && newFilters.sort !== 'date_desc') params.set('sort', newFilters.sort);
    if (newFilters.q) params.set('q', newFilters.q);
    router.replace(`${pathname}?${params.toString()}`);
  };

  return { filters, setFilters };
}
```

---

## Résumé des règles métier (rappel)

| Statut job | Créateur peut | Worker peut | Paiement |
|---|---|---|---|
| `PENDING` | ✏️ Modifier, 🗑️ Supprimer, 👥 Voir candidats, 🤝 Assigner | 📝 Postuler | ❌ |
| `IN_PROGRESS` | 💬 Messages | 💬 Messages, ✅ Marquer DONE | ❌ |
| `DONE` | 💳 Payer, ⭐ Noter, ✅ Confirmer livraison | ⭐ Noter créateur | 💳 `HELD` → `COMPLETED` |

**Règles de vérification du paiement :**
1. Toujours charger `GET /payments/mine` au montage
2. Faire un `transactions.find(t => t.jobId === job.id)`
3. Si `tx.status === 'COMPLETED'` → badge "✓ Payé", pas de bouton Payer
4. Si `tx.status === 'HELD'` → bouton "Confirmer la livraison"
5. Si pas de `tx` + `job.status === 'DONE'` → bouton "Payer"
6. Si `job.status !== 'DONE'` → pas de bouton de paiement

---

## 4. Galerie d'images multiples pour un job

### Modèle de données

Un job peut avoir **zéro, une ou plusieurs images**. Le backend les stocke comme une liste d'URLs :

```json
{
  "id": "...",
  "title": "Développeur React",
  "images": [
    "/uploads/jobs/image1.jpg",
    "/uploads/jobs/image2.jpg",
    "/uploads/jobs/image3.jpg"
  ],
  ...
}
```

### Endpoints pour la gestion des images

| Endpoint | Méthode | Description |
|---|---|---|
| `POST /jobs` | `CreateJobRequest { images: ["url1", "url2"] }` | Créer un job avec des images |
| `PUT /jobs/{id}` | `UpdateJobRequest { images: [...] }` | Remplacer toutes les images |
| `POST /jobs/{id}/images?file=...` | Multipart | Uploader et ajouter une image |
| `DELETE /jobs/{id}/images?imageUrl=...` | DELETE | Supprimer une image spécifique |

### Affichage dans la fiche détaillée (galerie + lightbox)

```tsx
// components/JobImageGallery.tsx
'use client';

import { useState } from 'react';
import Image from 'next/image';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { ChevronLeft, ChevronRight, X } from 'lucide-react';

interface JobImageGalleryProps {
  images: string[];
  title: string;
}

export function JobImageGallery({ images, title }: JobImageGalleryProps) {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [lightboxOpen, setLightboxOpen] = useState(false);

  // Pas d'image → placeholder
  if (!images || images.length === 0) {
    return (
      <div className="w-full h-64 bg-muted rounded-lg flex items-center justify-center text-muted-foreground">
        <div className="text-center">
          <ImageIcon className="mx-auto h-12 w-12 mb-2" />
          <span>Aucune image</span>
        </div>
      </div>
    );
  }

  return (
    <>
      {/* Vignette principale */}
      <div
        className="relative w-full h-80 rounded-lg overflow-hidden cursor-pointer group"
        onClick={() => setLightboxOpen(true)}
      >
        <Image
          src={images[currentIndex]}
          alt={`${title} - Image ${currentIndex + 1}`}
          fill
          className="object-cover transition-transform group-hover:scale-105"
          priority
        />

        {/* Compteur sur l'image principale */}
        {images.length > 1 && (
          <div className="absolute top-3 right-3 bg-black/60 text-white text-xs px-2 py-1 rounded-full">
            {currentIndex + 1} / {images.length}
          </div>
        )}

        {/* Flèches navigation rapide sur l'image principale */}
        {images.length > 1 && (
          <>
            <button
              onClick={e => { e.stopPropagation(); setCurrentIndex(i => (i === 0 ? images.length - 1 : i - 1)); }}
              className="absolute left-2 top-1/2 -translate-y-1/2 bg-black/40 hover:bg-black/60 text-white p-1.5 rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
            >
              <ChevronLeft className="h-5 w-5" />
            </button>
            <button
              onClick={e => { e.stopPropagation(); setCurrentIndex(i => (i === images.length - 1 ? 0 : i + 1)); }}
              className="absolute right-2 top-1/2 -translate-y-1/2 bg-black/40 hover:bg-black/60 text-white p-1.5 rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
            >
              <ChevronRight className="h-5 w-5" />
            </button>
          </>
        )}
      </div>

      {/* Miniatures en dessous */}
      {images.length > 1 && (
        <div className="flex gap-2 mt-3 overflow-x-auto pb-2">
          {images.map((img, idx) => (
            <button
              key={idx}
              onClick={() => setCurrentIndex(idx)}
              className={`relative flex-shrink-0 w-20 h-16 rounded-md overflow-hidden border-2 transition-all ${
                idx === currentIndex
                  ? 'border-primary ring-2 ring-primary/30'
                  : 'border-transparent hover:border-muted-foreground/50'
              }`}
            >
              <Image
                src={img}
                alt={`Miniature ${idx + 1}`}
                fill
                className="object-cover"
              />
            </button>
          ))}
        </div>
      )}

      {/* Lightbox plein écran */}
      <Dialog open={lightboxOpen} onOpenChange={setLightboxOpen}>
        <DialogContent className="max-w-5xl h-[90vh] p-0 bg-black/95">
          <div className="relative w-full h-full flex items-center justify-center">
            {/* Bouton fermer */}
            <button
              onClick={() => setLightboxOpen(false)}
              className="absolute top-4 right-4 z-10 bg-black/60 hover:bg-black/80 text-white p-2 rounded-full"
            >
              <X className="h-6 w-6" />
            </button>

            {/* Image en grand */}
            <div className="relative w-full h-full p-12">
              <Image
                src={images[currentIndex]}
                alt={`${title} - Image ${currentIndex + 1}`}
                fill
                className="object-contain"
              />
            </div>

            {/* Navigation lightbox */}
            {images.length > 1 && (
              <>
                <button
                  onClick={() => setCurrentIndex(i => (i === 0 ? images.length - 1 : i - 1))}
                  className="absolute left-4 top-1/2 -translate-y-1/2 bg-black/60 hover:bg-black/80 text-white p-3 rounded-full"
                >
                  <ChevronLeft className="h-6 w-6" />
                </button>
                <button
                  onClick={() => setCurrentIndex(i => (i === images.length - 1 ? 0 : i + 1))}
                  className="absolute right-4 top-1/2 -translate-y-1/2 bg-black/60 hover:bg-black/80 text-white p-3 rounded-full"
                >
                  <ChevronRight className="h-6 w-6" />
                </button>

                {/* Compteur en bas */}
                <div className="absolute bottom-6 left-1/2 -translate-x-1/2 bg-black/60 text-white text-sm px-4 py-2 rounded-full">
                  {currentIndex + 1} / {images.length}
                </div>
              </>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
```

### Utilisation dans la page détail

```tsx
// app/jobs/[id]/page.tsx
export default function JobDetailPage({ params }: { params: { id: string } }) {
  const { data: job, isLoading } = useQuery({
    queryKey: ['job', params.id],
    queryFn: () => api.get(`/jobs/${params.id}`).then(r => r.data),
  });

  if (isLoading) return <JobDetailSkeleton />;

  return (
    <div className="max-w-4xl mx-auto p-6 space-y-6">
      {/* Galerie d'images */}
      <JobImageGallery images={job.images} title={job.title} />

      {/* Infos du job */}
      <div>
        <h1 className="text-3xl font-bold">{job.title}</h1>
        <p className="text-muted-foreground mt-1">
          {job.categoryName} · {job.location}
        </p>
      </div>

      <Badge>{statusLabel(job.status)}</Badge>

      <Card>
        <CardContent className="pt-6">
          <p className="whitespace-pre-wrap">{job.description}</p>
        </CardContent>
      </Card>

      <div className="text-2xl font-semibold text-primary">
        {job.price}€
      </div>

      <Separator />
      <JobActions job={job} currentUserId={user.id} />
    </div>
  );
}
```

### Upload d'images (formulaire de création/édition)

```tsx
// components/JobImageUploader.tsx
'use client';

import { useState, useRef } from 'react';
import Image from 'next/image';
import { Button } from '@/components/ui/button';
import { X, Upload, GripVertical } from 'lucide-react';

interface JobImageUploaderProps {
  images: string[];
  onChange: (images: string[]) => void;
  onUpload: (file: File) => Promise<string>; // upload → retourne l'URL
}

export function JobImageUploader({ images, onChange, onUpload }: JobImageUploaderProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    if (files.length === 0) return;

    setUploading(true);
    try {
      const newUrls = await Promise.all(files.map(f => onUpload(f)));
      onChange([...images, ...newUrls]);
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const removeImage = (idx: number) => {
    onChange(images.filter((_, i) => i !== idx));
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-3">
        {/* Preview des images existantes */}
        {images.map((url, idx) => (
          <div key={idx} className="relative group w-28 h-28 rounded-lg overflow-hidden border">
            <Image src={url} alt={`Image ${idx + 1}`} fill className="object-cover" />

            {/* Bouton supprimer au hover */}
            <button
              onClick={() => removeImage(idx)}
              className="absolute top-1 right-1 bg-destructive text-destructive-foreground p-1 rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
            >
              <X className="h-4 w-4" />
            </button>

            {/* Indice */}
            <div className="absolute bottom-1 left-1 bg-black/60 text-white text-xs px-1.5 py-0.5 rounded">
              {idx + 1}
            </div>
          </div>
        ))}

        {/* Bouton ajouter */}
        <label
          className={`w-28 h-28 border-2 border-dashed rounded-lg flex flex-col items-center justify-center gap-1 cursor-pointer transition-colors ${
            uploading
              ? 'border-muted-foreground/30 animate-pulse'
              : 'border-muted-foreground/50 hover:border-primary hover:bg-primary/5'
          }`}
        >
          {uploading ? (
            <span className="text-sm text-muted-foreground">Upload...</span>
          ) : (
            <>
              <Upload className="h-6 w-6 text-muted-foreground" />
              <span className="text-xs text-muted-foreground">Ajouter</span>
            </>
          )}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            multiple
            className="hidden"
            onChange={handleFileSelect}
            disabled={uploading}
          />
        </label>
      </div>

      <p className="text-xs text-muted-foreground">
        {images.length === 0
          ? 'Aucune image. Cliquez sur "Ajouter" pour uploader.'
          : `${images.length} image${images.length > 1 ? 's' : ''}`}
      </p>
    </div>
  );
}
```

### Exemple : Page de création de job avec images

```tsx
// app/jobs/new/page.tsx
export default function CreateJobPage() {
  const [images, setImages] = useState<string[]>([]);
  const router = useRouter();
  const queryClient = useQueryClient();

  const uploadMutation = useMutation({
    mutationFn: async (file: File): Promise<string> => {
      const form = new FormData();
      form.append('file', file);
      const { data } = await api.post('/upload', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return data.url; // ou data selon le retour de ton endpoint upload
    },
  });

  const createMutation = useMutation({
    mutationFn: (formData: CreateJobData) =>
      api.post('/jobs', { ...formData, images }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['available-jobs'] });
      toast({ title: 'Annonce créée' });
      router.push('/mes-annonces');
    },
  });

  const handleUpload = async (file: File) => {
    const url = await uploadMutation.mutateAsync(file);
    return url;
  };

  return (
    <div className="max-w-2xl mx-auto p-6 space-y-6">
      <h1 className="text-2xl font-bold">Nouvelle annonce</h1>

      <form onSubmit={/* ... */} className="space-y-6">
        {/* Images */}
        <div>
          <Label>Photos du projet</Label>
          <JobImageUploader
            images={images}
            onChange={setImages}
            onUpload={handleUpload}
          />
        </div>

        {/* Titre */}
        <div>
          <Label htmlFor="title">Titre</Label>
          <Input id="title" {...register('title')} />
        </div>

        {/* Description */}
        <div>
          <Label htmlFor="description">Description</Label>
          <Textarea id="description" {...register('description')} />
        </div>

        {/* Prix */}
        <div>
          <Label htmlFor="price">Prix (€)</Label>
          <Input id="price" type="number" step="0.01" {...register('price')} />
        </div>

        {/* Catégorie */}
        <div>
          <Label htmlFor="categoryId">Catégorie</Label>
          <CategorySelect />
        </div>

        <Button type="submit" disabled={createMutation.isPending}>
          {createMutation.isPending ? 'Création...' : 'Publier l\'annonce'}
        </Button>
      </form>
    </div>
  );
}
```

### Bonnes pratiques

1. **Placeholder si 0 image** — toujours afficher un bloc grisé "Aucune image" plutôt qu'un trou dans la mise en page
2. **Navigation clavier** — ← → pour naviguer dans la lightbox, Escape pour fermer
3. **Touch/mobile** — swipe gesture pour la galerie (optionnel, utiliser une lib comme `embla-carousel-react` de Shadcn)
4. **Alt text** — toujours mettre `alt={`${title} - Image ${n}`}` pour l'accessibilité
5. **Loading priority** — la première image (currentIndex=0) doit avoir `priority`, les autres `loading="lazy"`
6. **Pas de drag & drop par défaut** — inutile de complexifier, le bouton "Ajouter" + sélecteur multiple suffit
7. **Limite** — côté backend il n'y a pas de limite stricte, mais pour l'UX on recommande **max 5 images**
