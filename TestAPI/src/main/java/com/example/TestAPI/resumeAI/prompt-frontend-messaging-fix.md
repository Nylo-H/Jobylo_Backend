# Guide Frontend : Correction du bug "premier message n'apparaît pas"

## Le bug

Quand un user appuie sur **"Discuter"** et envoie son premier message :
- Le message **n'apparaît pas immédiatement** dans le fil
- Il faut **actualiser** la page (ou redémarrer l'app)
- Parfois il y a **confusion sender/receiver** (le message est affiché avec le mauvais user)

## Cause racine

### Avant (cassé)

```
[Client]                   [Backend]
   |                           |
   |---POST /messages/start--->|
   |   { content: "Hello" }    |
   |                           | (1) crée la conversation
   |                           | (2) sauve le message en DB
   |                           | (3) WS push → /topic/messages/{newId}
   |                           |     ⚠️ ZÉRO abonné (conversationId inconnu du client)
   |                           | (4) retourne 201 + MessageResponse
   |<---201 + MessageResponse--|
   |                           |
   | (5) le client ne s'abonne |   <-- C'est ici que le bug se produit :
   |     à /topic/... QUE      |   Le client reçoit le 201 mais
   |     APRÈS avoir vu        |   n'a pas encore de conversationId
   |     conversationId        |   pour s'abonner
   |                           |
   | (6) Le push WS (étape 3)  |
   |     est PERDU             |
```

**Problème** : `startConversation` fait le push WebSocket **avant** que le client ait reçu la `conversationId` (qui est dans la réponse HTTP). Le push atteint **zéro abonné** côté client → message jamais affiché sans refresh.

### Après (corrigé)

Le backend envoie maintenant le message via **3 canaux** :

| Canal | Destination | Qui reçoit | Quand |
|---|---|---|---|
| Topic broadcast | `/topic/messages/{conversationId}` | Tous les abonnés (sender + receiver) | Pour les **messages suivants** (conversationId déjà connu) |
| **Per-user queue** | `/user/{userId}/queue/messages` | L'utilisateur cible uniquement (sender + receiver) | **Toujours**, même avant abonnement au topic |
| Notification | `/topic/notifications/{userId}` | L'utilisateur cible (badge) | Pour mettre à jour le compteur |

Le client doit **s'abonner à `/user/{userId}/queue/messages` UNE SEULE FOIS** (à la connexion STOMP), et il recevra tous ses messages peu importe l'état d'abonnement aux topics.

---

## Ce que le frontend doit faire

### 1. Connexion STOMP (à faire UNE fois au login/app start)

```dart
class StompService {
  late StompClient stompClient;
  String? currentUserId;
  String? accessToken;
  String? currentConversationId;

  Future<void> connect(String token) async {
    accessToken = token;

    stompClient = StompClient(
      config: StompConfig(
        url: 'ws://api.example.com/api/ws',  // base path /api
        onConnect: _onConnect,
        onWebSocketError: (e) => print('WS error: $e'),
        stompConnectHeaders: {'Authorization': 'Bearer $token'},
        webSocketConnectHeaders: {'Authorization': 'Bearer $token'},
      ),
    );

    stompClient.activate();
  }

  void _onConnect(StompFrame frame) {
    print('STOMP connected');

    // ⭐ ABONNEMENT GLOBAL À LA QUEUE PERSONNELLE ⭐
    // C'est ICI la correction principale : on s'abonne à /user/queue/messages
    // avec notre userId. Le backend y enverra TOUS nos messages
    // (envoyés + reçus) peu importe l'état d'abonnement aux topics.
    if (currentUserId != null) {
      stompClient.subscribe(
        destination: '/user/$currentUserId/queue/messages',
        callback: _onPersonalMessage,
      );
    }

    // ⭐ ABONNEMENT GLOBAL AUX NOTIFICATIONS (badge) ⭐
    if (currentUserId != null) {
      stompClient.subscribe(
        destination: '/topic/notifications/$currentUserId',
        callback: _onNotification,
      );
    }

    // ⭐ ABONNEMENT GLOBAL À LA PRÉSENCE (online/offline) ⭐
    stompClient.subscribe(
      destination: '/topic/presence',
      callback: _onPresence,
    );
  }

  void _onPersonalMessage(StompFrame frame) {
    final message = MessageResponse.fromJson(jsonDecode(frame.body!));
    // Traiter le message (envoyé par moi OU reçu de quelqu'un)
    _handleNewMessage(message);
  }

  void _onNotification(StompFrame frame) {
    final event = NotificationEvent.fromJson(jsonDecode(frame.body!));
    // Mettre à jour le badge
  }

  void _onPresence(StompFrame frame) {
    // Mettre à jour l'indicateur online/offline
  }

  // Appelé à chaque ouverture d'une conversation
  void subscribeToConversation(String conversationId) {
    currentConversationId = conversationId;

    // Topic de la conversation (pour les messages futurs du correspondant)
    stompClient.subscribe(
      destination: '/topic/messages/$conversationId',
      callback: _onConversationMessage,
    );

    // Read receipts
    stompClient.subscribe(
      destination: '/topic/read/$conversationId',
      callback: _onReadReceipt,
    );
  }

  // Appelé à chaque fermeture d'une conversation
  void unsubscribeFromConversation(String conversationId) {
    stompClient.unsubscribe(
      destination: '/topic/messages/$conversationId',
    );
    stompClient.unsubscribe(
      destination: '/topic/read/$conversationId',
    );
    currentConversationId = null;
  }

  void _onConversationMessage(StompFrame frame) {
    final message = MessageResponse.fromJson(jsonDecode(frame.body!));
    _handleNewMessage(message);
  }

  void _handleNewMessage(MessageResponse message) {
    // Logique unique pour gérer les messages reçus via WS :
    // - soit de la queue personnelle (/user/{userId}/queue/messages)
    // - soit du topic de conversation (/topic/messages/{conversationId})
    // ⚠️ IMPORTANT : dédupliquer par message.id !
    final exists = messages.any((m) => m.id == message.id);
    if (exists) return;

    // Détecter si c'est un message qu'on a envoyé (pour l'afficher comme tel)
    if (message.senderId == currentUserId) {
      // C'est NOTRE message qu'on reçoit via WS
      // (il a peut-être déjà été ajouté via la réponse REST)
      messages.add(message.copyWith(isMine: true));
    } else {
      // Message reçu
      messages.add(message.copyWith(isMine: false));
    }
    sortMessages();
    notifyListeners();
  }

  void _onReadReceipt(StompFrame frame) {
    final receipt = ReadReceiptEvent.fromJson(jsonDecode(frame.body!));
    // Marquer tous les messages du reader comme lus
    for (var msg in messages) {
      if (msg.senderId == currentUserId && !msg.isRead) {
        msg = msg.copyWith(isRead: true);
      }
    }
    notifyListeners();
  }
}
```

### 2. Envoyer le premier message (cliquer "Discuter")

```dart
Future<void> startConversation({
  required String jobId,
  required String content,
}) async {
  try {
    final response = await api.post('/messages/start/$jobId', body: {
      'content': content,
    });

    final message = MessageResponse.fromJson(response.data);

    // ⭐ ÉTAPE 1 : ajouter le message à la liste locale immédiatement ⭐
    // (le backend l'a aussi poussé via /user/{userId}/queue/messages,
    // mais l'ajouter ici garantit l'affichage instantané sans attendre WS)
    _addOrUpdateMessage(message);

    // ⭐ ÉTAPE 2 : naviguer vers la conversation ⭐
    router.push('/messages/${message.conversationId}');

    // ⭐ ÉTAPE 3 : s'abonner au topic de la conversation ⭐
    // (fait dans la page de conversation, voir section 3)
  } catch (e) {
    showError(e);
  }
}

void _addOrUpdateMessage(MessageResponse message) {
  final idx = messages.indexWhere((m) => m.id == message.id);
  if (idx == -1) {
    messages.add(message);
  } else {
    messages[idx] = message;
  }
  sortMessages();
  notifyListeners();
}
```

### 3. Page de conversation (déjà ouverte)

```dart
class ConversationPage extends StatefulWidget {
  final String conversationId;
  final String otherUserId;
  // ...
}

class _ConversationPageState extends State<ConversationPage> {
  @override
  void initState() {
    super.initState();

    // ⭐ S'abonner au topic de CETTE conversation ⭐
    // Pour les messages futurs du correspondant
    stompService.subscribeToConversation(widget.conversationId);

    // ⭐ Charger l'historique (REST) ⭐
    _loadHistory();

    // ⭐ Marquer tous les messages non lus comme lus ⭐
    _markAllAsRead();
  }

  @override
  void dispose() {
    stompService.unsubscribeFromConversation(widget.conversationId);
    super.dispose();
  }

  Future<void> _loadHistory() async {
    final response = await api.get('/messages/conversation/${widget.conversationId}?page=0&size=50');
    final page = PageResponse.fromJson(response.data, MessageResponse.fromJson);
    setState(() {
      messages = page.content;
    });
  }

  Future<void> _markAllAsRead() async {
    await api.patch('/messages/conversation/${widget.conversationId}/read');
  }

  Future<void> _sendMessage() async {
    final content = textController.text.trim();
    if (content.isEmpty) return;

    try {
      final response = await api.post('/messages', body: {
        'content': content,
        'conversationId': widget.conversationId,
      });

      final message = MessageResponse.fromJson(response.data);

      // ⭐ Ajouter immédiatement le message à la liste ⭐
      setState(() {
        messages.add(message);
      });

      textController.clear();
    } catch (e) {
      showError(e);
    }
  }
}
```

### 4. Modèle Dart unifié (avec isMine)

```dart
class MessageResponse {
  final String id;
  final String conversationId;
  final String senderId;
  final String senderUsername;
  final String receiverId;
  final String receiverUsername;
  final String jobId;
  final String content;
  final DateTime timestamp;
  final bool isRead;
  final bool isMine; // ⭐ calculé localement, pas envoyé par le backend

  MessageResponse({
    required this.id,
    required this.conversationId,
    required this.senderId,
    required this.senderUsername,
    required this.receiverId,
    required this.receiverUsername,
    required this.jobId,
    required this.content,
    required this.timestamp,
    required this.isRead,
    this.isMine = false,
  });

  factory MessageResponse.fromJson(Map<String, dynamic> json) {
    return MessageResponse(
      id: json['id'],
      conversationId: json['conversationId'],
      senderId: json['senderId'],
      senderUsername: json['senderUsername'],
      receiverId: json['receiverId'],
      receiverUsername: json['receiverUsername'],
      jobId: json['jobId'],
      content: json['content'],
      timestamp: DateTime.parse(json['timestamp']),
      isRead: json['isRead'] ?? false,
      // ⭐ NE PAS LIRE isMine depuis le JSON ; le calculer localement
    );
  }

  MessageResponse copyWith({bool? isMine, bool? isRead}) {
    return MessageResponse(
      id: id,
      conversationId: conversationId,
      senderId: senderId,
      senderUsername: senderUsername,
      receiverId: receiverId,
      receiverUsername: receiverUsername,
      jobId: jobId,
      content: content,
      timestamp: timestamp,
      isRead: isRead ?? this.isRead,
      isMine: isMine ?? this.isMine,
    );
  }

  static List<MessageResponse> withIsMine(
      List<MessageResponse> messages, String currentUserId) {
    return messages
        .map((m) => m.copyWith(isMine: m.senderId == currentUserId))
        .toList();
  }
}
```

---

## Règles d'or

### 1. Toujours ajouter le message à la liste locale APRÈS la réponse REST

```dart
// ✅ BON : double-canal (REST + WS), déduplication
final response = await api.post('/messages', body: {...});
final message = MessageResponse.fromJson(response.data);
messages.add(message); // affiché immédiatement
// WS peut aussi le renvoyer plus tard → dédupliquer par id

// ❌ MAUVAIS : ignorer la réponse REST
final response = await api.post('/messages', body: {...});
// attendre WS → race possible
```

### 2. S'abonner à `/user/{userId}/queue/messages` UNE seule fois

Au login / démarrage de l'app, **après** la connexion STOMP. C'est la queue personnelle qui reçoit **TOUS** les messages de l'utilisateur (envoyés et reçus) sans dépendre de l'abonnement aux topics.

### 3. S'abonner aux topics de conversation seulement à l'ouverture de la conversation

```dart
// Dans initState() de la page de conversation
stompService.subscribeToConversation(conversationId);
// Dans dispose()
stompService.unsubscribeFromConversation(conversationId);
```

### 4. Toujours dédupliquer par `message.id`

Le backend peut envoyer le même message 2-3 fois (REST + WS topic + WS queue) → dédupliquer pour éviter l'affichage en double.

```dart
void _handleNewMessage(MessageResponse message) {
  final exists = messages.any((m) => m.id == message.id);
  if (exists) return; // ⭐ déduplication ⭐
  messages.add(message);
}
```

### 5. Déterminer `isMine` localement, pas depuis le serveur

```dart
// Le backend ne renvoie PAS isMine ; le frontend le calcule :
final isMine = message.senderId == currentUserId;
```

### 6. Bien gérer la connexion STOMP à la reconnexion

Si la connexion STOMP tombe (perte réseau), le client doit :
- Se reconnecter automatiquement
- **Re-s'abonner** à `/user/{userId}/queue/messages` et `/topic/notifications/{userId}`
- **Recharger** l'historique de la conversation ouverte (REST)

---

## Résumé des abonnements STOMP

| Destination | Quand s'abonner | Quoi recevoir |
|---|---|---|
| `/user/{userId}/queue/messages` | Une fois, à la connexion STOMP | **TOUS** les messages (envoyés + reçus) |
| `/topic/notifications/{userId}` | Une fois, à la connexion STOMP | Événements (badge, nouvelles candidatures) |
| `/topic/presence` | Une fois, à la connexion STOMP | Online/offline des autres users |
| `/topic/messages/{conversationId}` | À l'ouverture d'une conversation | Messages temps réel dans CE fil |
| `/topic/read/{conversationId}` | À l'ouverture d'une conversation | Notifications de lecture (✓✓ bleus) |

**En double réception** : REST (immédiat) + WS queue personnelle (en parallèle). Le frontend déduplique par `message.id`.

---

## Ce qui a changé côté backend (pour info)

1. `WebSocketConfig` : `setUserDestinationPrefix("/user")` activé, broker étendu à `/queue`
2. `WebSocketConfig` : principal changé en `() -> user.getId().toString()` pour que `convertAndSendToUser(userId, ...)` fonctionne
3. `MessageServiceImpl.startConversation` : pousse le message sur `/topic/messages/{id}` + `/user/{senderId}/queue/messages` + `/user/{receiverId}/queue/messages`
4. `MessageServiceImpl.sendMessage` : pareil
5. `MessageServiceImpl.startConversation` : `lastMessageAt` et `lastMessageContent` mis à jour même quand la conversation est réutilisée (correction d'un bug où la liste des conversations affichait un message obsolète)
