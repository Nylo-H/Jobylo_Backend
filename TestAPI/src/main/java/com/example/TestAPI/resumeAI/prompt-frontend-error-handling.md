# Guide Gestion des Erreurs Backend → Frontend

## Format standard de réponse d'erreur

Toutes les erreurs backend retournent **un seul format JSON cohérent** :

```json
{
  "status": 400,
  "error": "Message explicite en français",
  "errorCode": "VALIDATION_ERROR",
  "timestamp": "2026-06-01T14:30:00.123",
  "path": "uri=/api/auth/login"
}
```

| Champ | Type | Description |
|---|---|---|
| `status` | int | Code HTTP |
| `error` | string | Message lisible pour l'utilisateur |
| `errorCode` | string | Code machine pour le branching frontend |
| `timestamp` | string | ISO 8601 |
| `path` | string | URI de la requête (peut être null) |

---

## Codes d'erreur (`errorCode`) et leur signification

| errorCode | HTTP | Cause | Quand |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | Champ invalide/vide (`@Valid`) | Formulaire mal rempli |
| `BAD_REQUEST` | 400 | Requête invalide (OTP expiré, fichier vide, etc.) | Logique métier |
| `NOT_FOUND` | 404 | Ressource introuvable | Mauvais ID, email inconnu |
| `INVALID_PASSWORD` | 401 | Mot de passe incorrect | Login |
| `UNAUTHORIZED` | 401 | Token manquant, invalide ou expiré | JWT expiré, refresh token invalide |
| `ACCESS_DENIED` | 403 | Rôle insuffisant | Non-admin sur /admin |
| `FORBIDDEN` | 403 | Action interdite | KYC pas encore vérifié |
| `USER_NOT_VERIFIED` | 403 | Email non vérifié | Action nécessitant un email vérifié |
| `CONFLICT` | 409 | Conflit (doublon, déjà fait) | Username/email déjà pris, déjà vérifié, etc. |
| `TOO_MANY_REQUESTS` | 429 | Rate limit atteint | Trop de tentatives forgot-password |
| `INTERNAL_ERROR` | 500 | Erreur serveur inattendue | Bug, NPE, etc. |

---

## Scénarios spécifiques

### 1. Login — Mauvais email
```json
{"status":404, "error":"Utilisateur non trouvé", "errorCode":"NOT_FOUND", ...}
```

### 2. Login — Mauvais mot de passe
```json
{"status":401, "error":"Mot de passe invalide", "errorCode":"INVALID_PASSWORD", ...}
```

### 3. Login — Email non vérifié
Login réussi (200), mais `verified: false` dans `LoginResponse` :
```json
{"accessToken":"...", "refreshtoken":"...", "verified": false}
```

### 4. KYC requis mais pas encore fait
```json
{"status":403, "error":"Pour effectuer cette action, vous devez d'abord vérifier votre identité...", "errorCode":"FORBIDDEN", ...}
```

### 5. KYC en cours
```json
{"status":403, "error":"Votre vérification d'identité est en cours. Veuillez patienter.", "errorCode":"FORBIDDEN", ...}
```

### 6. KYC rejeté
```json
{"status":403, "error":"Votre vérification d'identité a été rejetée. Veuillez soumettre à nouveau vos documents.", "errorCode":"FORBIDDEN", ...}
```

### 7. Token JWT expiré ou invalide
```json
{"status":401, "error":"Token invalide ou expiré", "errorCode":"UNAUTHORIZED", ...}
```

### 8. Token refresh expiré
```json
{"status":401, "error":"Refresh token invalide ou expiré", "errorCode":"UNAUTHORIZED", ...}
```

### 9. Accès refusé (rôle insuffisant)
```json
{"status":403, "error":"Accès refusé : privilèges insuffisants", "errorCode":"ACCESS_DENIED", ...}
```

### 10. Validation d'un champ (`@Valid`)
```json
{"status":400, "error":"Le titre est obligatoire", "errorCode":"VALIDATION_ERROR", ...}
```
(Si plusieurs erreurs, le message les concatène avec `; `)

### 11. Rate limit (forgot-password)
```json
{"status":429, "error":"Trop de tentatives. Veuillez réessayer dans 45 secondes.", "errorCode":"TOO_MANY_REQUESTS", ...}
```

### 12. Erreur 500 imprévue
```json
{"status":500, "error":"Erreur interne du serveur", "errorCode":"INTERNAL_ERROR", ...}
```

---

## Implémentation Axios recommandée

### Intercepteur de réponse unifié

```typescript
// api/axiosInstance.ts
import axios, { AxiosError } from 'axios';

export interface ApiError {
  status: number;
  error: string;
  errorCode: string;
  timestamp?: string;
  path?: string;
}

const api = axios.create({
  baseURL: '/api',
});

// Intercepteur de réponse : normalise toutes les erreurs
api.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiError>) => {
    if (error.response?.data) {
      // Format normalisé du backend
      const apiError = error.response.data as ApiError;
      return Promise.reject(apiError);
    }

    // Erreur réseau / timeout → créer une erreur normalisée
    return Promise.reject({
      status: 0,
      error: 'Erreur réseau. Veuillez vérifier votre connexion.',
      errorCode: 'NETWORK_ERROR',
    } as ApiError);
  }
);

export default api;
```

### Hook useApi generics
```typescript
// hooks/useApi.ts
import { useToast } from '@/components/ui/use-toast';
import { useRouter } from 'next/navigation';
import type { ApiError } from '@/api/axiosInstance';

export function useApiErrorHandler() {
  const { toast } = useToast();
  const router = useRouter();

  const handleError = (err: ApiError) => {
    switch (err.errorCode) {
      case 'UNAUTHORIZED':
        // Token expiré → tenter refresh ou rediriger vers login
        toast({ title: 'Session expirée', description: 'Veuillez vous reconnecter' });
        router.push('/login');
        break;

      case 'ACCESS_DENIED':
        toast({ title: 'Accès refusé', description: err.error, variant: 'destructive' });
        router.push('/');
        break;

      case 'VALIDATION_ERROR':
      case 'BAD_REQUEST':
        toast({ title: 'Erreur', description: err.error, variant: 'destructive' });
        break;

      case 'FORBIDDEN':
        if (err.error.includes('KYC') || err.error.includes('vérification')) {
          toast({ title: 'KYC requis', description: err.error, variant: 'destructive' });
          router.push('/kyc');
        } else {
          toast({ title: 'Action interdite', description: err.error, variant: 'destructive' });
        }
        break;

      case 'TOO_MANY_REQUESTS':
        toast({ title: 'Trop de tentatives', description: err.error, variant: 'destructive' });
        break;

      case 'CONFLICT':
        toast({ title: 'Conflit', description: err.error, variant: 'destructive' });
        break;

      case 'NOT_FOUND':
        toast({ title: 'Introuvable', description: err.error, variant: 'destructive' });
        break;

      case 'INTERNAL_ERROR':
      case 'NETWORK_ERROR':
        toast({ title: 'Erreur serveur', description: err.error, variant: 'destructive' });
        break;

      default:
        toast({ title: 'Erreur', description: err.error || 'Une erreur est survenue', variant: 'destructive' });
    }
  };

  return { handleError };
}
```

### Exemple : Gestion inline dans un composant

```tsx
// components/LoginForm.tsx
import { useApiErrorHandler } from '@/hooks/useApi';
import api from '@/api/axiosInstance';
import type { ApiError } from '@/api/axiosInstance';

function LoginForm() {
  const { handleError } = useApiErrorHandler();
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleSubmit = async (data: LoginData) => {
    try {
      const res = await api.post('/auth/login', data);
      // Succès → traiter le LoginResponse
    } catch (err) {
      const apiErr = err as ApiError;

      // Action spécifique au formulaire de login
      if (apiErr.errorCode === 'INVALID_PASSWORD') {
        setFieldErrors({ password: apiErr.error });
      } else if (apiErr.errorCode === 'NOT_FOUND') {
        setFieldErrors({ email: apiErr.error });
      } else {
        // Erreur générique → toast
        handleError(apiErr);
      }
    }
  };
}
```

### Exemple : Intercepteur refresh token + queue
```typescript
// api/axiosInstance.ts (extension)
let isRefreshing = false;
let failedQueue: Array<{
  resolve: (value: unknown) => void;
  reject: (reason: ApiError) => void;
}> = [];

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiError>) => {
    const originalRequest = error.config as any;

    // Si 401 et pas déjà retenté → tenter refresh
    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // Mettre en queue pendant le refresh
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const { data } = await axios.post('/api/auth/refresh', {}, { withCredentials: true });
        // Nouveau token stocké, retenter la requête originale
        failedQueue.forEach(({ resolve }) => resolve(data));
        failedQueue = [];
        return api(originalRequest);
      } catch {
        failedQueue.forEach(({ reject }) => reject(error));
        failedQueue = [];
        // Redirection vers login
        window.location.href = '/login';
        return Promise.reject(error);
      } finally {
        isRefreshing = false;
      }
    }

    // Fallback → erreur normalisée
    if (error.response?.data) {
      return Promise.reject(error.response.data as ApiError);
    }
    return Promise.reject({
      status: 0,
      error: 'Erreur réseau',
      errorCode: 'NETWORK_ERROR',
    } as ApiError);
  }
);
```

---

## Règles générales

1. **`errorCode` est la source de vérité** pour le branching — ne parsez jamais le message d'erreur textuel
2. **Tous les messages sont en français** et prêts à être affichés à l'utilisateur
3. **HTTP status et `status` dans le body sont identiques** — utilisez celui du body pour la cohérence
4. **Cas particulier `verified: false`** dans `LoginResponse` : ce n'est pas une erreur, le login a réussi mais l'email n'est pas vérifié. Le frontend doit afficher un écran "Vérifiez votre email" et proposer de renvoyer l'OTP
5. **Pour les erreurs réseau** (pas de réponse serveur), créez une `ApiError` synthétique avec `errorCode: 'NETWORK_ERROR'` et `status: 0`
