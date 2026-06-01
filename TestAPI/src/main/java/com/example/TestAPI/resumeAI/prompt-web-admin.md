# Prompt Frontend Web Admin : Dashboard Moderne

## Contexte

Tu dois construire un **dashboard web admin** pour la marketplace "Jobylo". Stack : **React + Vite + TypeScript + Shadcn/ui + Tailwind + React Query + Zustand + Axios**. Authentification via JWT (déjà en place). Tout le code admin doit être protégé par un `AdminGuard` qui vérifie `role === "ADMIN"`.

## Configuration de base

```typescript
// src/config/api.ts
export const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';
export const UPLOADS_BASE_URL = API_BASE_URL; // les /uploads/ sont servis en statique
```

```typescript
// src/lib/axios.ts
import axios from 'axios';
import { useAuthStore } from '@/stores/authStore';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api',
});

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (r) => r,
  async (err) => {
    // refresh token queue : même logique que l'app mobile/web user
    // voir prompt-frontend-error-handling.md
    if (err.response?.status === 401 && !err.config.__isRetry) {
      // ... refresh + retry
    }
    return Promise.reject(err);
  }
);
```

## Authentification Admin

Le login est le **même** que les users normaux (`POST /auth/login`). La différence se fait à l'obtention du rôle :
- `LoginResponse` ne contient **pas** le `role`
- Il faut appeler `GET /auth/me` pour récupérer le profil

```typescript
// src/stores/authStore.ts
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface User {
  id: string;
  email: string;
  username: string;
  firstName: string;
  lastName: string;
  role: 'USER' | 'ADMIN';
  verified: boolean;
  kycStatus: 'PENDING' | 'VERIFIED' | 'REJECTED' | null;
  // ...
}

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: User | null;
  setSession: (tokens: LoginResponse, user: User) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      setSession: (tokens, user) =>
        set({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, user }),
      logout: () => set({ accessToken: null, refreshToken: null, user: null }),
    }),
    { name: 'admin-auth' }
  )
);
```

```typescript
// src/components/AdminGuard.tsx
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';

export function AdminGuard({ children }: { children: React.ReactNode }) {
  const { user, accessToken } = useAuthStore();
  if (!accessToken) return <Navigate to="/login" replace />;
  if (user?.role !== 'ADMIN') return <Navigate to="/unauthorized" replace />;
  return <>{children}</>;
}
```

```tsx
// src/routes.tsx
<Routes>
  <Route path="/login" element={<LoginPage />} />
  <Route path="/unauthorized" element={<UnauthorizedPage />} />
  <Route element={<AdminGuard><AdminLayout /></AdminGuard>}>
    <Route path="/" element={<DashboardPage />} />
    {/* ... */}
  </Route>
</Routes>
```

## Palette de couleurs (cohérence avec le design system)

```css
/* tailwind.config.ts (extrait) */
colors: {
  primary: '#0D47A1',
  secondary: '#1976D2',
  background: '#F5F7FA',
  success: '#10B981',
  error: '#EF4444',
  warning: '#F59E0B',
  info: '#3B82F6',
}
```

---

# PAGE 1 : Dashboard (Stats)

## Layout

```
┌──────────────────────────────────────────────────────────┐
│ Sidebar │  Dashboard                                     │
│         │  ┌──────────┬──────────┬──────────┬─────────┐ │
│ 📊 Dash │  │ 4 521    │ 1 247    │ 89       │ 12 530€ │ │
│ 👥 Users│  │ Users    │ KYC en   │ KYC en   │ Volume  │ │
│ 🆔 KYC  │  │ total    │ attente  │ attente  │ paiements│ │
│ 💼 Jobs │  └──────────┴──────────┴──────────┴─────────┘ │
│ 💸 Pay  │  ┌────────────────────┬─────────────────────┐  │
│ 📜 Audit│  │ Jobs par statut    │ Inscriptions/mois  │  │
│ 🏷 Categ│  │ [Pie chart]        │ [Line chart]        │  │
│         │  └────────────────────┴─────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## Endpoint

```typescript
// GET /admin/stats
interface AdminStatsResponse {
  totalUsers: number;
  verifiedUsers: number;
  kycPending: number;
  kycVerified: number;
  kycRejected: number;
  jobsPending: number;
  jobsInProgress: number;
  jobsDone: number;
  jobsExpired: number;
  transactionsHeld: number;
  transactionsCompleted: number;
  transactionsCancelled: number;
  totalApplications: number;
  applicationsPending: number;
  totalAuditLogs: number;
}
```

## Code

```tsx
// pages/DashboardPage.tsx
import { useQuery } from '@tanstack/react-query';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { api } from '@/lib/axios';
import { Users, FileCheck, Briefcase, Wallet, Clock, CheckCircle2, XCircle, TrendingUp } from 'lucide-react';
import { PieChart, Pie, Cell, ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip, Legend } from 'recharts';

export function DashboardPage() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['admin-stats'],
    queryFn: async () => (await api.get<AdminStatsResponse>('/admin/stats')).data,
    refetchInterval: 30_000, // rafraîchir toutes les 30s
  });

  if (isLoading || !stats) return <DashboardSkeleton />;

  const kycData = [
    { name: 'En attente', value: stats.kycPending, color: '#F59E0B' },
    { name: 'Vérifiés', value: stats.kycVerified, color: '#10B981' },
    { name: 'Rejetés', value: stats.kycRejected, color: '#EF4444' },
  ];

  const jobData = [
    { name: 'Disponibles', value: stats.jobsPending, color: '#0D47A1' },
    { name: 'En cours', value: stats.jobsInProgress, color: '#1976D2' },
    { name: 'Terminés', value: stats.jobsDone, color: '#10B981' },
    { name: 'Expirés', value: stats.jobsExpired, color: '#6B7280' },
  ];

  return (
    <div className="p-8 space-y-6">
      <h1 className="text-3xl font-bold">Tableau de bord</h1>

      {/* KPI cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <KpiCard title="Utilisateurs" value={stats.totalUsers} icon={Users} subtitle={`${stats.verifiedUsers} vérifiés`} color="primary" />
        <KpiCard title="KYC en attente" value={stats.kycPending} icon={Clock} subtitle="À traiter" color="warning" pulse={stats.kycPending > 0} />
        <KpiCard title="Annonces actives" value={stats.jobsPending + stats.jobsInProgress} icon={Briefcase} subtitle={`${stats.jobsPending} disponibles`} color="info" />
        <KpiCard title="Candidatures" value={stats.applicationsPending} icon={FileCheck} subtitle="En attente" color="info" />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Card>
          <CardHeader><CardTitle>Annonces par statut</CardTitle></CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={300}>
              <PieChart>
                <Pie data={jobData} dataKey="value" nameKey="name" outerRadius={100} label>
                  {jobData.map((d, i) => <Cell key={i} fill={d.color} />)}
                </Pie>
                <Tooltip />
                <Legend />
              </PieChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle>Statuts KYC</CardTitle></CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={300}>
              <PieChart>
                <Pie data={kycData} dataKey="value" nameKey="name" outerRadius={100} label>
                  {kycData.map((d, i) => <Cell key={i} fill={d.color} />)}
                </Pie>
                <Tooltip />
                <Legend />
              </PieChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function KpiCard({ title, value, icon: Icon, subtitle, color, pulse }: any) {
  return (
    <Card className="hover:shadow-md transition-shadow">
      <CardContent className="p-6">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-sm text-muted-foreground">{title}</p>
            <p className="text-3xl font-bold mt-2">{value.toLocaleString('fr-FR')}</p>
            {subtitle && <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>}
          </div>
          <div className={`p-3 rounded-full bg-${color}/10 relative`}>
            <Icon className={`h-6 w-6 text-${color}`} />
            {pulse && <span className="absolute top-1 right-1 h-2 w-2 rounded-full bg-error animate-ping" />}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
```

---

# PAGE 2 : Utilisateurs

## Endpoints

| Méthode | Endpoint | Description |
|---|---|---|
| `GET` | `/admin/users` | Liste tous les utilisateurs |
| `GET` | `/admin/users/{id}` | Détail d'un user |
| `PUT` | `/admin/users/{id}/role` | Change le rôle `{ "role": "ADMIN" \| "USER" }` |
| `PUT` | `/admin/users/{id}/kyc` | Change le KYC `{ "status": "VERIFIED"\|"REJECTED"\|"PENDING", "rejectionReason": "..." }` |
| `DELETE` | `/admin/users/{id}` | Supprime le user |

## Layout

```
┌──────────────────────────────────────────────────────────────────┐
│ Utilisateurs                              [+ Ajouter un admin]   │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ Recherche: [______________]  Rôle: [Tous▾]  KYC: [Tous▾]    │ │
│ └──────────────────────────────────────────────────────────────┘ │
│ ┌────────┬─────────────┬────────────────┬───────┬──────┬───────┐ │
│ │ Avatar │ Nom complet │ Email          │ Rôle  │ KYC  │Actions│ │
│ ├────────┼─────────────┼────────────────┼───────┼──────┼───────┤ │
│ │ [img]  │ Jean Dupont │ jean@mail.com  │ USER  │ ✓    │ ⋮    │ │
│ │ [img]  │ Marie K.    │ marie@mail.com │ ADMIN │ ⏳   │ ⋮    │ │
│ └────────┴─────────────┴────────────────┴───────┴──────┴───────┘ │
│ Précédent 1 2 3 ... 12                Suivant                     │
└──────────────────────────────────────────────────────────────────┘
```

## Code

```tsx
// pages/UsersPage.tsx
import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Search, MoreVertical, Edit, Trash2, Shield, ShieldOff } from 'lucide-react';
import { api } from '@/lib/axios';
import { UserDetailDialog } from '@/components/admin/UserDetailDialog';
import { PromoteAdminDialog } from '@/components/admin/PromoteAdminDialog';
import { toast } from 'sonner';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export function UsersPage() {
  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<string>('all');
  const [kycFilter, setKycFilter] = useState<string>('all');
  const [selectedUser, setSelectedUser] = useState<UserResponse | null>(null);
  const [promoteUser, setPromoteUser] = useState<UserResponse | null>(null);
  const [page, setPage] = useState(1);
  const queryClient = useQueryClient();

  const { data: users = [], isLoading } = useQuery({
    queryKey: ['admin-users'],
    queryFn: async () => (await api.get<UserResponse[]>('/admin/users')).data,
  });

  const updateRoleMutation = useMutation({
    mutationFn: async ({ userId, role }: { userId: string; role: 'USER' | 'ADMIN' }) =>
      (await api.put<UserResponse>(`/admin/users/${userId}/role`, { role })).data,
    onSuccess: (_, vars) => {
      toast.success(vars.role === 'ADMIN' ? 'Rôle ADMIN attribué' : 'Rôle USER attribué');
      queryClient.invalidateQueries({ queryKey: ['admin-users'] });
      queryClient.invalidateQueries({ queryKey: ['admin-stats'] });
    },
  });

  const updateKycMutation = useMutation({
    mutationFn: async ({ userId, status, reason }: { userId: string; status: 'PENDING' | 'VERIFIED' | 'REJECTED'; reason?: string }) =>
      (await api.put<UserResponse>(`/admin/users/${userId}/kyc`, { status, rejectionReason: reason })).data,
    onSuccess: () => {
      toast.success('Statut KYC mis à jour');
      queryClient.invalidateQueries({ queryKey: ['admin-users'] });
      queryClient.invalidateQueries({ queryKey: ['admin-stats'] });
    },
  });

  const deleteUserMutation = useMutation({
    mutationFn: async (userId: string) => (await api.delete(`/admin/users/${userId}`)).data,
    onSuccess: () => {
      toast.success('Utilisateur supprimé');
      queryClient.invalidateQueries({ queryKey: ['admin-users'] });
      queryClient.invalidateQueries({ queryKey: ['admin-stats'] });
    },
  });

  const filtered = users.filter((u) => {
    if (search && !`${u.firstName} ${u.lastName} ${u.email} ${u.username}`.toLowerCase().includes(search.toLowerCase())) return false;
    if (roleFilter !== 'all' && u.role !== roleFilter) return false;
    if (kycFilter !== 'all' && u.kycStatus !== kycFilter) return false;
    return true;
  });

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold">Utilisateurs</h1>
        <Button onClick={() => setPromoteUser({} as UserResponse)} className="gap-2">
          <Shield className="h-4 w-4" /> Ajouter un admin
        </Button>
      </div>

      <Card>
        <CardContent className="p-4">
          <div className="flex flex-col md:flex-row gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="Rechercher par nom, email, username..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-10"
              />
            </div>
            <Select value={roleFilter} onValueChange={setRoleFilter}>
              <SelectTrigger className="w-[180px]"><SelectValue placeholder="Rôle" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">Tous les rôles</SelectItem>
                <SelectItem value="USER">Utilisateurs</SelectItem>
                <SelectItem value="ADMIN">Administrateurs</SelectItem>
              </SelectContent>
            </Select>
            <Select value={kycFilter} onValueChange={setKycFilter}>
              <SelectTrigger className="w-[180px]"><SelectValue placeholder="KYC" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">Tous les KYC</SelectItem>
                <SelectItem value="VERIFIED">Vérifiés</SelectItem>
                <SelectItem value="PENDING">En attente</SelectItem>
                <SelectItem value="REJECTED">Rejetés</SelectItem>
                <SelectItem value="null">Non soumis</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-muted/50">
                <tr>
                  <th className="text-left p-4">Utilisateur</th>
                  <th className="text-left p-4">Email</th>
                  <th className="text-left p-4">Rôle</th>
                  <th className="text-left p-4">KYC</th>
                  <th className="text-left p-4">Note</th>
                  <th className="text-right p-4">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((user) => (
                  <tr key={user.id} className="border-t hover:bg-muted/30 cursor-pointer" onClick={() => setSelectedUser(user)}>
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <Avatar>
                          <AvatarImage src={user.photoProfile ? `${API_BASE}${user.photoProfile}` : undefined} />
                          <AvatarFallback>{user.firstName?.[0]}{user.lastName?.[0]}</AvatarFallback>
                        </Avatar>
                        <div>
                          <div className="font-medium">{user.firstName} {user.lastName}</div>
                          <div className="text-xs text-muted-foreground">@{user.username}</div>
                        </div>
                      </div>
                    </td>
                    <td className="p-4 text-sm">{user.email}</td>
                    <td className="p-4">
                      <Badge variant={user.role === 'ADMIN' ? 'default' : 'secondary'}>{user.role}</Badge>
                    </td>
                    <td className="p-4">
                      <KycBadge status={user.kycStatus} />
                    </td>
                    <td className="p-4 text-sm">
                      {user.averageRating ? `⭐ ${user.averageRating.toFixed(1)} (${user.totalRatings})` : '—'}
                    </td>
                    <td className="p-4 text-right" onClick={(e) => e.stopPropagation()}>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon"><MoreVertical className="h-4 w-4" /></Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onClick={() => setSelectedUser(user)}>
                            <Edit className="h-4 w-4 mr-2" /> Voir détail
                          </DropdownMenuItem>
                          {user.role === 'USER' ? (
                            <DropdownMenuItem onClick={() => updateRoleMutation.mutate({ userId: user.id, role: 'ADMIN' })}>
                              <Shield className="h-4 w-4 mr-2" /> Promouvoir ADMIN
                            </DropdownMenuItem>
                          ) : user.id !== useAuthStore.getState().user?.id && (
                            <DropdownMenuItem onClick={() => updateRoleMutation.mutate({ userId: user.id, role: 'USER' })}>
                              <ShieldOff className="h-4 w-4 mr-2" /> Rétrograder USER
                            </DropdownMenuItem>
                          )}
                          {user.id !== useAuthStore.getState().user?.id && (
                            <DropdownMenuItem
                              onClick={() => {
                                if (confirm(`Supprimer ${user.firstName} ${user.lastName} ?`)) {
                                  deleteUserMutation.mutate(user.id);
                                }
                              }}
                              className="text-error"
                            >
                              <Trash2 className="h-4 w-4 mr-2" /> Supprimer
                            </DropdownMenuItem>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {selectedUser && (
        <UserDetailDialog
          user={selectedUser}
          open={!!selectedUser}
          onOpenChange={(o) => !o && setSelectedUser(null)}
          onUpdateKyc={(status, reason) => updateKycMutation.mutate({ userId: selectedUser.id, status, reason })}
        />
      )}

      {promoteUser && (
        <PromoteAdminDialog
          open={!!promoteUser}
          onOpenChange={(o) => !o && setPromoteUser(null)}
          onPromote={(userId) => updateRoleMutation.mutate({ userId, role: 'ADMIN' })}
        />
      )}
    </div>
  );
}

function KycBadge({ status }: { status: string | null }) {
  if (status === 'VERIFIED') return <Badge className="bg-success/10 text-success">✓ Vérifié</Badge>;
  if (status === 'PENDING') return <Badge className="bg-warning/10 text-warning">⏳ En attente</Badge>;
  if (status === 'REJECTED') return <Badge className="bg-error/10 text-error">✗ Rejeté</Badge>;
  return <Badge variant="outline">Non soumis</Badge>;
}
```

```tsx
// components/admin/PromoteAdminDialog.tsx
// ⭐ Permet de promouvoir un utilisateur existant en admin OU de créer un nouvel admin ⭐

import { useState } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { api } from '@/lib/axios';
import { toast } from 'sonner';
import { useQuery } from '@tanstack/react-query';
import { Search, Shield } from 'lucide-react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export function PromoteAdminDialog({ open, onOpenChange, onPromote }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onPromote: (userId: string) => void;
}) {
  const [tab, setTab] = useState<'existing' | 'new'>('existing');
  const [search, setSearch] = useState('');

  const { data: users = [] } = useQuery({
    queryKey: ['admin-users'],
    queryFn: async () => (await api.get<UserResponse[]>('/admin/users')).data,
    enabled: open,
  });

  const candidates = users
    .filter((u) => u.role === 'USER')
    .filter((u) => !search || `${u.firstName} ${u.lastName} ${u.email}`.toLowerCase().includes(search.toLowerCase()));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Ajouter un administrateur</DialogTitle>
          <DialogDescription>Promouvez un utilisateur existant ou créez un compte admin.</DialogDescription>
        </DialogHeader>

        <Tabs value={tab} onValueChange={(v) => setTab(v as any)}>
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="existing">Utilisateur existant</TabsTrigger>
            <TabsTrigger value="new">Nouveau compte</TabsTrigger>
          </TabsList>

          <TabsContent value="existing" className="space-y-3">
            <Input
              placeholder="Rechercher un utilisateur..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="mt-2"
            />
            <div className="max-h-80 overflow-y-auto space-y-1">
              {candidates.length === 0 && <p className="text-sm text-muted-foreground text-center py-6">Aucun utilisateur trouvé</p>}
              {candidates.map((u) => (
                <button
                  key={u.id}
                  onClick={() => {
                    if (confirm(`Promouvoir ${u.firstName} ${u.lastName} en ADMIN ?`)) {
                      onPromote(u.id);
                      onOpenChange(false);
                    }
                  }}
                  className="w-full flex items-center gap-3 p-3 rounded-lg hover:bg-muted text-left"
                >
                  <Avatar>
                    <AvatarImage src={u.photoProfile ? `${API_BASE}${u.photoProfile}` : undefined} />
                    <AvatarFallback>{u.firstName?.[0]}{u.lastName?.[0]}</AvatarFallback>
                  </Avatar>
                  <div className="flex-1">
                    <div className="font-medium">{u.firstName} {u.lastName}</div>
                    <div className="text-xs text-muted-foreground">{u.email}</div>
                  </div>
                  <Shield className="h-4 w-4 text-primary" />
                </button>
              ))}
            </div>
          </TabsContent>

          <TabsContent value="new">
            <NewAdminForm onCreated={() => onOpenChange(false)} />
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}

function NewAdminForm({ onCreated }: { onCreated: () => void }) {
  const [form, setForm] = useState({ firstName: '', lastName: '', username: '', email: '', password: '' });
  const [loading, setLoading] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      // 1. Créer l'utilisateur
      const created = await api.post('/auth/register', form);
      // 2. Promouvoir en admin
      await api.put(`/admin/users/${created.data.id}/role`, { role: 'ADMIN' });
      toast.success('Compte admin créé avec succès');
      onCreated();
    } catch (err: any) {
      toast.error(err.response?.data?.error || 'Erreur lors de la création');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-3 mt-2">
      <div className="grid grid-cols-2 gap-3">
        <div><Label>Prénom</Label><Input required value={form.firstName} onChange={(e) => setForm({...form, firstName: e.target.value})} /></div>
        <div><Label>Nom</Label><Input required value={form.lastName} onChange={(e) => setForm({...form, lastName: e.target.value})} /></div>
      </div>
      <div><Label>Username</Label><Input required value={form.username} onChange={(e) => setForm({...form, username: e.target.value})} /></div>
      <div><Label>Email</Label><Input type="email" required value={form.email} onChange={(e) => setForm({...form, email: e.target.value})} /></div>
      <div><Label>Mot de passe</Label><Input type="password" required minLength={8} value={form.password} onChange={(e) => setForm({...form, password: e.target.value})} /></div>
      <Button type="submit" disabled={loading} className="w-full">
        {loading ? 'Création...' : 'Créer le compte admin'}
      </Button>
    </form>
  );
}
```

---

# PAGE 3 : Validation KYC avec visualisation des images

## Endpoint

```typescript
// GET /kyc/all?status=PENDING
interface KycDocumentResponse {
  id: string;
  userId: string;
  fileUrl: string;           // ⭐ ex: "/uploads/kyc/abc123.jpg"
  documentType: 'ID_CARD' | 'PASSPORT' | 'OTHER';
  status: 'PENDING' | 'VERIFIED' | 'REJECTED';
  verifiedById: string | null;
  submittedAt: string;
  rejectionReason: string | null;
}
```

**⚠️ Important** : `fileUrl` est un chemin relatif (commence par `/uploads/`). Il faut le préfixer avec `API_BASE_URL` pour le rendre absolu.

```typescript
function getFileUrl(relativePath: string): string {
  if (relativePath.startsWith('http')) return relativePath;
  return `${API_BASE}${relativePath}`;
}
```

## Layout

```
┌──────────────────────────────────────────────────────────────┐
│ Vérification KYC                                             │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ Filtres: [Tous▾] [PENDING▾] 42 en attente               │ │
│ └──────────────────────────────────────────────────────────┘ │
│ ┌──────────┬─────────┬──────────┬────────┬─────┬──────────┐│
│ │ User     │ Type    │ Soumis   │ Statut │Voir │ Actions  ││
│ ├──────────┼─────────┼──────────┼────────┼─────┼──────────┤│
│ │ Jean D.  │ ID_CARD │ 12/05/26 │ ⏳     │ 👁  │ ✓ ✗     ││
│ │ [avatar] │         │          │        │     │          ││
│ └──────────┴─────────┴──────────┴────────┴─────┴──────────┘│
└──────────────────────────────────────────────────────────────┘
```

## Vue détail (modal/side panel)

```
┌─────────────────────────────────────────────────────────────┐
│ Document KYC de Jean Dupont                          [✕]   │
│ ┌─────────────────┐  ┌──────────────────────────────────┐  │
│ │                 │  │ Type : Carte d'identité          │  │
│ │   [Image KYC]   │  │ Soumis le : 12 mai 2026 à 14:32 │  │
│ │   (zoomable)    │  │ Statut : ⏳ En attente           │  │
│ │                 │  │                                  │  │
│ │  [←] [→]        │  │ Motif (si rejet) :               │  │
│ │  1/3 images     │  │ [_______________________]       │  │
│ └─────────────────┘  │                                  │  │
│                      │ [✓ Approuver]  [✗ Rejeter]      │  │
│                      └──────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Code

```tsx
// pages/KycPage.tsx
import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Card, CardContent } from '@/components/ui/card';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Eye, Check, X } from 'lucide-react';
import { api } from '@/lib/axios';
import { KycDetailDialog } from '@/components/admin/KycDetailDialog';
import { toast } from 'sonner';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export function KycPage() {
  const [statusFilter, setStatusFilter] = useState<'all' | 'PENDING' | 'VERIFIED' | 'REJECTED'>('PENDING');
  const [selectedDocId, setSelectedDocId] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const { data: docs = [], isLoading } = useQuery({
    queryKey: ['admin-kyc', statusFilter],
    queryFn: async () => {
      const url = statusFilter === 'all' ? '/kyc/all' : `/kyc/all?status=${statusFilter}`;
      return (await api.get<KycDocumentResponse[]>(url)).data;
    },
  });

  const approveMutation = useMutation({
    mutationFn: async (docId: string) => (await api.post<KycDocumentResponse>(`/kyc/${docId}/approve`)).data,
    onSuccess: () => {
      toast.success('KYC approuvé');
      queryClient.invalidateQueries({ queryKey: ['admin-kyc'] });
      queryClient.invalidateQueries({ queryKey: ['admin-stats'] });
      setSelectedDocId(null);
    },
  });

  const rejectMutation = useMutation({
    mutationFn: async ({ docId, reason }: { docId: string; reason: string }) =>
      (await api.post<KycDocumentResponse>(`/kyc/${docId}/reject`, { reason })).data,
    onSuccess: () => {
      toast.success('KYC rejeté');
      queryClient.invalidateQueries({ queryKey: ['admin-kyc'] });
      queryClient.invalidateQueries({ queryKey: ['admin-stats'] });
      setSelectedDocId(null);
    },
  });

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold">Vérification KYC</h1>
      </div>

      <Tabs value={statusFilter} onValueChange={(v) => setStatusFilter(v as any)}>
        <TabsList>
          <TabsTrigger value="PENDING">⏳ En attente</TabsTrigger>
          <TabsTrigger value="VERIFIED">✓ Vérifiés</TabsTrigger>
          <TabsTrigger value="REJECTED">✗ Rejetés</TabsTrigger>
          <TabsTrigger value="all">Tous</TabsTrigger>
        </TabsList>
      </Tabs>

      <Card>
        <CardContent className="p-0">
          <table className="w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left p-4">Document</th>
                <th className="text-left p-4">Type</th>
                <th className="text-left p-4">Soumis le</th>
                <th className="text-left p-4">Statut</th>
                <th className="text-right p-4">Actions</th>
              </tr>
            </thead>
            <tbody>
              {docs.map((doc) => (
                <tr key={doc.id} className="border-t hover:bg-muted/30">
                  <td className="p-4">
                    <button onClick={() => setSelectedDocId(doc.id)} className="flex items-center gap-2 text-left">
                      <img
                        src={getFileUrl(doc.fileUrl)}
                        alt="KYC"
                        className="h-12 w-12 object-cover rounded border"
                      />
                      <div>
                        <div className="text-sm font-medium">User {doc.userId.slice(0, 8)}...</div>
                        <div className="text-xs text-muted-foreground">Doc #{doc.id.slice(0, 8)}</div>
                      </div>
                    </button>
                  </td>
                  <td className="p-4 text-sm">
                    {doc.documentType === 'ID_CARD' ? '🪪 Carte d\'identité' :
                     doc.documentType === 'PASSPORT' ? '📕 Passeport' : '📄 Autre'}
                  </td>
                  <td className="p-4 text-sm">{new Date(doc.submittedAt).toLocaleString('fr-FR')}</td>
                  <td className="p-4">
                    <KycStatusBadge status={doc.status} reason={doc.rejectionReason} />
                  </td>
                  <td className="p-4 text-right">
                    <div className="flex justify-end gap-2">
                      <Button size="sm" variant="outline" onClick={() => setSelectedDocId(doc.id)}>
                        <Eye className="h-4 w-4" />
                      </Button>
                      {doc.status === 'PENDING' && (
                        <>
                          <Button size="sm" variant="default" className="bg-success hover:bg-success/90"
                            onClick={() => approveMutation.mutate(doc.id)}>
                            <Check className="h-4 w-4" />
                          </Button>
                          <Button size="sm" variant="destructive"
                            onClick={() => {
                              const reason = prompt('Motif du rejet :');
                              if (reason) rejectMutation.mutate({ docId: doc.id, reason });
                            }}>
                            <X className="h-4 w-4" />
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {selectedDocId && (
        <KycDetailDialog
          docId={selectedDocId}
          open={!!selectedDocId}
          onOpenChange={(o) => !o && setSelectedDocId(null)}
          onApprove={(id) => approveMutation.mutate(id)}
          onReject={(id, reason) => rejectMutation.mutate({ docId: id, reason })}
        />
      )}
    </div>
  );
}

function getFileUrl(relativePath: string): string {
  if (relativePath.startsWith('http')) return relativePath;
  return `${API_BASE}${relativePath}`;
}

function KycStatusBadge({ status, reason }: { status: string; reason: string | null }) {
  if (status === 'VERIFIED') return <Badge className="bg-success/10 text-success">✓ Vérifié</Badge>;
  if (status === 'PENDING') return <Badge className="bg-warning/10 text-warning">⏳ En attente</Badge>;
  if (status === 'REJECTED') return (
    <div className="space-y-1">
      <Badge className="bg-error/10 text-error">✗ Rejeté</Badge>
      {reason && <p className="text-xs text-muted-foreground">"{reason}"</p>}
    </div>
  );
  return null;
}
```

```tsx
// components/admin/KycDetailDialog.tsx
// ⭐ Dialog avec visualisation d'image plein écran + zoom ⭐

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Check, X, ZoomIn, ZoomOut, Download, Loader2 } from 'lucide-react';
import { api } from '@/lib/axios';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export function KycDetailDialog({ docId, open, onOpenChange, onApprove, onReject }: {
  docId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onApprove: (docId: string) => void;
  onReject: (docId: string, reason: string) => void;
}) {
  const [zoom, setZoom] = useState(1);
  const [rejectMode, setRejectMode] = useState(false);
  const [reason, setReason] = useState('');

  const { data: doc, isLoading } = useQuery({
    queryKey: ['admin-kyc-doc', docId],
    queryFn: async () => (await api.get<KycDocumentResponse[]>(`/kyc/all`)).data.find((d) => d.id === docId),
    enabled: open,
  });

  if (!doc) return null;

  const fileUrl = doc.fileUrl.startsWith('http') ? doc.fileUrl : `${API_BASE}${doc.fileUrl}`;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-5xl h-[90vh] p-0">
        <div className="grid grid-cols-1 md:grid-cols-3 h-full">
          {/* Image viewer */}
          <div className="md:col-span-2 bg-black/95 flex flex-col items-center justify-center relative p-4">
            {isLoading ? (
              <Loader2 className="h-8 w-8 animate-spin text-white" />
            ) : (
              <>
                <div className="flex-1 flex items-center justify-center overflow-auto w-full">
                  <img
                    src={fileUrl}
                    alt="KYC document"
                    className="max-w-full max-h-full object-contain transition-transform"
                    style={{ transform: `scale(${zoom})` }}
                  />
                </div>
                {/* Toolbar */}
                <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex gap-2 bg-black/60 rounded-full px-3 py-2">
                  <Button size="icon" variant="ghost" className="text-white" onClick={() => setZoom((z) => Math.max(0.5, z - 0.25))}>
                    <ZoomOut className="h-4 w-4" />
                  </Button>
                  <span className="text-white text-sm self-center min-w-[50px] text-center">{Math.round(zoom * 100)}%</span>
                  <Button size="icon" variant="ghost" className="text-white" onClick={() => setZoom((z) => Math.min(3, z + 0.25))}>
                    <ZoomIn className="h-4 w-4" />
                  </Button>
                  <a href={fileUrl} download target="_blank" rel="noopener noreferrer">
                    <Button size="icon" variant="ghost" className="text-white"><Download className="h-4 w-4" /></Button>
                  </a>
                </div>
              </>
            )}
          </div>

          {/* Sidebar */}
          <div className="p-6 space-y-4 overflow-y-auto">
            <DialogHeader>
              <DialogTitle>Document KYC</DialogTitle>
              <DialogDescription>Vérifiez l'authenticité du document</DialogDescription>
            </DialogHeader>

            <div className="space-y-3 text-sm">
              <Row label="ID document" value={doc.id} />
              <Row label="User ID" value={doc.userId} />
              <Row label="Type" value={
                doc.documentType === 'ID_CARD' ? '🪪 Carte d\'identité' :
                doc.documentType === 'PASSPORT' ? '📕 Passeport' : '📄 Autre'
              } />
              <Row label="Soumis le" value={new Date(doc.submittedAt).toLocaleString('fr-FR')} />
              <div>
                <Label className="text-muted-foreground">Statut</Label>
                <div className="mt-1">
                  {doc.status === 'VERIFIED' && <Badge className="bg-success/10 text-success">✓ Vérifié</Badge>}
                  {doc.status === 'PENDING' && <Badge className="bg-warning/10 text-warning">⏳ En attente</Badge>}
                  {doc.status === 'REJECTED' && <Badge className="bg-error/10 text-error">✗ Rejeté</Badge>}
                </div>
              </div>
              {doc.rejectionReason && (
                <div>
                  <Label className="text-muted-foreground">Motif du rejet</Label>
                  <p className="text-sm bg-error/5 border border-error/20 rounded p-2 mt-1">{doc.rejectionReason}</p>
                </div>
              )}
            </div>

            {doc.status === 'PENDING' && (
              <div className="space-y-2 pt-4 border-t">
                {!rejectMode ? (
                  <>
                    <Button className="w-full bg-success hover:bg-success/90" onClick={() => onApprove(doc.id)}>
                      <Check className="h-4 w-4 mr-2" /> Approuver
                    </Button>
                    <Button variant="destructive" className="w-full" onClick={() => setRejectMode(true)}>
                      <X className="h-4 w-4 mr-2" /> Rejeter
                    </Button>
                  </>
                ) : (
                  <>
                    <Label>Motif du rejet *</Label>
                    <Textarea
                      placeholder="Document flou, expiré, falsifié..."
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      rows={3}
                    />
                    <div className="flex gap-2">
                      <Button variant="outline" onClick={() => { setRejectMode(false); setReason(''); }}>Annuler</Button>
                      <Button
                        variant="destructive"
                        className="flex-1"
                        disabled={!reason.trim()}
                        onClick={() => onReject(doc.id, reason)}
                      >
                        Confirmer le rejet
                      </Button>
                    </div>
                  </>
                )}
              </div>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <Label className="text-muted-foreground">{label}</Label>
      <p className="font-mono text-xs break-all">{value}</p>
    </div>
  );
}
```

---

# PAGE 4 : Catégories (CRUD complet avec hiérarchie)

## Endpoints

| Méthode | Endpoint | Body | Description |
|---|---|---|---|
| `GET` | `/admin/categories` | — | Liste plate (toutes les catégories) |
| `POST` | `/admin/categories` | `CreateCategoryRequest` | Créer |
| `PUT` | `/admin/categories/{id}` | `CreateCategoryRequest` | Modifier |
| `DELETE` | `/admin/categories/{id}` | — | Supprimer |

```typescript
interface CreateCategoryRequest {
  name: string;        // ex: "Plomberie"
  description?: string; // ex: "Réparation, installation, dépannage"
  icon?: string;       // ex: "🔧" ou nom d'icône Lucide
  parentId?: string;   // null = catégorie racine
  displayOrder: number; // 0, 1, 2 pour tri
}
```

## Layout (Tree view)

```
┌──────────────────────────────────────────────────────────────┐
│ Catégories                                  [+ Nouvelle cat.]│
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ 🔧 Bâtiment                        [ordre: 0] [✏️] [🗑] │ │
│ │   ├─ Plomberie                     [ordre: 0] [✏️] [🗑] │ │
│ │   ├─ Électricité                   [ordre: 1] [✏️] [🗑] │ │
│ │   └─ Maçonnerie                    [ordre: 2] [✏️] [🗑] │ │
│ │ 💻 Tech                            [ordre: 1] [✏️] [🗑] │ │
│ │   ├─ Dev web                       [ordre: 0] [✏️] [🗑] │ │
│ │   └─ Dev mobile                    [ordre: 1] [✏️] [🗑] │ │
│ │ 🎉 Événementiel                    [ordre: 2] [✏️] [🗑] │ │
│ └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

## Code

```tsx
// pages/CategoriesPage.tsx
import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ChevronRight, ChevronDown, Edit, Trash2, Plus, GripVertical } from 'lucide-react';
import { api } from '@/lib/axios';
import { toast } from 'sonner';

interface Category {
  id: string;
  name: string;
  description: string | null;
  icon: string | null;
  parentId: string | null;
  displayOrder: number;
}

export function CategoriesPage() {
  const [editing, setEditing] = useState<Category | null>(null);
  const [creating, setCreating] = useState<{ parentId: string | null } | null>(null);
  const queryClient = useQueryClient();

  const { data: categories = [], isLoading } = useQuery({
    queryKey: ['admin-categories'],
    queryFn: async () => (await api.get<Category[]>('/admin/categories')).data,
  });

  const createMutation = useMutation({
    mutationFn: async (data: Omit<Category, 'id'>) =>
      (await api.post<Category>('/admin/categories', data)).data,
    onSuccess: () => {
      toast.success('Catégorie créée');
      queryClient.invalidateQueries({ queryKey: ['admin-categories'] });
      setCreating(null);
    },
    onError: (e: any) => toast.error(e.response?.data?.error || 'Erreur'),
  });

  const updateMutation = useMutation({
    mutationFn: async ({ id, data }: { id: string; data: Omit<Category, 'id'> }) =>
      (await api.put<Category>(`/admin/categories/${id}`, data)).data,
    onSuccess: () => {
      toast.success('Catégorie modifiée');
      queryClient.invalidateQueries({ queryKey: ['admin-categories'] });
      setEditing(null);
    },
    onError: (e: any) => toast.error(e.response?.data?.error || 'Erreur'),
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: string) => (await api.delete(`/admin/categories/${id}`)).data,
    onSuccess: () => {
      toast.success('Catégorie supprimée');
      queryClient.invalidateQueries({ queryKey: ['admin-categories'] });
    },
    onError: (e: any) => toast.error(e.response?.data?.error || 'Erreur'),
  });

  // Construction de l'arbre
  const rootCategories = categories
    .filter((c) => !c.parentId)
    .sort((a, b) => a.displayOrder - b.displayOrder);

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold">Catégories</h1>
        <Button onClick={() => setCreating({ parentId: null })}>
          <Plus className="h-4 w-4 mr-2" /> Nouvelle catégorie
        </Button>
      </div>

      <Card>
        <CardContent className="p-6">
          {isLoading ? (
            <p>Chargement...</p>
          ) : rootCategories.length === 0 ? (
            <p className="text-center text-muted-foreground py-8">Aucune catégorie. Créez-en une.</p>
          ) : (
            <div className="space-y-1">
              {rootCategories.map((cat) => (
                <CategoryTreeNode
                  key={cat.id}
                  category={cat}
                  allCategories={categories}
                  level={0}
                  onEdit={setEditing}
                  onDelete={(c) => {
                    if (confirm(`Supprimer "${c.name}" ?`)) deleteMutation.mutate(c.id);
                  }}
                  onAddChild={(parentId) => setCreating({ parentId })}
                />
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {(creating || editing) && (
        <CategoryFormDialog
          category={editing}
          defaultParentId={creating?.parentId}
          allCategories={categories}
          open={!!(creating || editing)}
          onOpenChange={(o) => { if (!o) { setCreating(null); setEditing(null); } }}
          onSubmit={(data) => {
            if (editing) updateMutation.mutate({ id: editing.id, data });
            else createMutation.mutate(data);
          }}
        />
      )}
    </div>
  );
}

function CategoryTreeNode({ category, allCategories, level, onEdit, onDelete, onAddChild }: any) {
  const [expanded, setExpanded] = useState(true);
  const children = allCategories
    .filter((c: Category) => c.parentId === category.id)
    .sort((a: Category, b: Category) => a.displayOrder - b.displayOrder);

  return (
    <>
      <div
        className="flex items-center gap-2 p-2 rounded hover:bg-muted/50 group"
        style={{ paddingLeft: `${level * 24 + 8}px` }}
      >
        <button
          onClick={() => setExpanded(!expanded)}
          className="h-6 w-6 flex items-center justify-center"
        >
          {children.length > 0 ? (
            expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />
          ) : (
            <span className="h-4 w-4" />
          )}
        </button>
        <span className="text-lg">{category.icon || '📁'}</span>
        <span className="font-medium flex-1">{category.name}</span>
        <span className="text-xs text-muted-foreground">ordre: {category.displayOrder}</span>
        <div className="opacity-0 group-hover:opacity-100 flex gap-1 transition-opacity">
          <Button size="sm" variant="ghost" onClick={() => onAddChild(category.id)}>
            <Plus className="h-3 w-3" />
          </Button>
          <Button size="sm" variant="ghost" onClick={() => onEdit(category)}>
            <Edit className="h-3 w-3" />
          </Button>
          <Button size="sm" variant="ghost" onClick={() => onDelete(category)}>
            <Trash2 className="h-3 w-3 text-error" />
          </Button>
        </div>
      </div>
      {expanded && children.map((child: Category) => (
        <CategoryTreeNode
          key={child.id}
          category={child}
          allCategories={allCategories}
          level={level + 1}
          onEdit={onEdit}
          onDelete={onDelete}
          onAddChild={onAddChild}
        />
      ))}
    </>
  );
}

function CategoryFormDialog({ category, defaultParentId, allCategories, open, onOpenChange, onSubmit }: any) {
  const [form, setForm] = useState({
    name: category?.name || '',
    description: category?.description || '',
    icon: category?.icon || '',
    parentId: category?.parentId ?? defaultParentId ?? null,
    displayOrder: category?.displayOrder ?? 0,
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{category ? 'Modifier la catégorie' : 'Nouvelle catégorie'}</DialogTitle>
          <DialogDescription>
            {category ? 'Mettez à jour les informations' : 'Créez une nouvelle catégorie'}
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            onSubmit({ ...form, parentId: form.parentId || null });
          }}
          className="space-y-4"
        >
          <div>
            <Label>Nom *</Label>
            <Input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </div>
          <div>
            <Label>Description</Label>
            <Textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={2} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Icône (emoji ou nom Lucide)</Label>
              <Input value={form.icon} onChange={(e) => setForm({ ...form, icon: e.target.value })} placeholder="🔧 ou 'wrench'" />
            </div>
            <div>
              <Label>Ordre d'affichage</Label>
              <Input type="number" value={form.displayOrder} onChange={(e) => setForm({ ...form, displayOrder: +e.target.value })} />
            </div>
          </div>
          <div>
            <Label>Catégorie parente</Label>
            <Select
              value={form.parentId || 'none'}
              onValueChange={(v) => setForm({ ...form, parentId: v === 'none' ? null : v })}
            >
              <SelectTrigger><SelectValue placeholder="Aucune (catégorie racine)" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="none">Aucune (catégorie racine)</SelectItem>
                {allCategories
                  .filter((c: Category) => c.id !== category?.id)
                  .map((c: Category) => (
                    <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                  ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground mt-1">⚠️ Maximum 2 niveaux de profondeur recommandés</p>
          </div>
          <div className="flex justify-end gap-2 pt-4">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Annuler</Button>
            <Button type="submit">{category ? 'Enregistrer' : 'Créer'}</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
```

---

# Pages secondaires (audit, jobs, transactions)

```tsx
// pages/AuditPage.tsx — table simple avec filtres
import { useQuery } from '@tanstack/react-query';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Select } from '@/components/ui/select';
import { api } from '@/lib/axios';
import { useState } from 'react';

export function AuditPage() {
  const [action, setAction] = useState('all');
  const [search, setSearch] = useState('');
  const { data: logs = [] } = useQuery({
    queryKey: ['admin-audit'],
    queryFn: async () => (await api.get<ActionLogResponse[]>('/admin/audit')).data,
  });

  const actions = Array.from(new Set(logs.map((l) => l.action)));
  const filtered = logs.filter((l) =>
    (action === 'all' || l.action === action) &&
    (!search || `${l.username} ${l.details}`.toLowerCase().includes(search.toLowerCase()))
  );

  return (
    <div className="p-8 space-y-6">
      <h1 className="text-3xl font-bold">Journal d'audit</h1>
      <div className="flex gap-3">
        <Input placeholder="Rechercher..." value={search} onChange={(e) => setSearch(e.target.value)} className="max-w-sm" />
        <Select value={action} onValueChange={setAction}>
          <SelectTrigger className="w-64"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">Toutes les actions</SelectItem>
            {actions.map((a) => <SelectItem key={a} value={a}>{a}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      <Card>
        <table className="w-full">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left p-4">Date</th>
              <th className="text-left p-4">User</th>
              <th className="text-left p-4">Action</th>
              <th className="text-left p-4">Détails</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((log) => (
              <tr key={log.id} className="border-t hover:bg-muted/30">
                <td className="p-4 text-sm">{new Date(log.timestamp).toLocaleString('fr-FR')}</td>
                <td className="p-4">{log.username}</td>
                <td className="p-4"><Badge>{log.action}</Badge></td>
                <td className="p-4 text-sm font-mono">{log.details}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
```

```tsx
// pages/TransactionsPage.tsx — table des paiements
import { useQuery } from '@tanstack/react-query';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { api } from '@/lib/axios';

export function TransactionsPage() {
  const { data: tx = [] } = useQuery({
    queryKey: ['admin-transactions'],
    queryFn: async () => (await api.get<PaymentResponse[]>('/admin/transactions')).data,
  });

  return (
    <div className="p-8 space-y-6">
      <h1 className="text-3xl font-bold">Transactions</h1>
      <Card>
        <table className="w-full">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left p-4">Date</th>
              <th className="text-left p-4">Job</th>
              <th className="text-left p-4">Acheteur</th>
              <th className="text-left p-4">Vendeur</th>
              <th className="text-right p-4">Montant</th>
              <th className="text-right p-4">Commission</th>
              <th className="text-right p-4">Net</th>
              <th className="text-left p-4">Statut</th>
            </tr>
          </thead>
          <tbody>
            {tx.map((t) => (
              <tr key={t.id} className="border-t hover:bg-muted/30">
                <td className="p-4 text-sm">{new Date(t.createdAt).toLocaleString('fr-FR')}</td>
                <td className="p-4 text-sm">{t.jobTitle}</td>
                <td className="p-4 text-sm">{t.buyerUsername}</td>
                <td className="p-4 text-sm">{t.sellerUsername}</td>
                <td className="p-4 text-right font-mono">{t.amount}€</td>
                <td className="p-4 text-right font-mono text-muted-foreground">{t.commissionAmount}€ ({t.commissionPercentage}%)</td>
                <td className="p-4 text-right font-mono font-semibold">{t.netAmount}€</td>
                <td className="p-4">
                  <Badge variant={t.status === 'COMPLETED' ? 'default' : t.status === 'HELD' ? 'secondary' : 'destructive'}>
                    {t.status}
                  </Badge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
```

---

# Sidebar / Layout

```tsx
// components/admin/AdminLayout.tsx
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { LayoutDashboard, Users, FileCheck, Briefcase, Wallet, ScrollText, Tag, LogOut } from 'lucide-react';
import { useAuthStore } from '@/stores/authStore';
import { Button } from '@/components/ui/button';

export function AdminLayout() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();

  const navItems = [
    { to: '/', label: 'Dashboard', icon: LayoutDashboard },
    { to: '/users', label: 'Utilisateurs', icon: Users },
    { to: '/kyc', label: 'KYC', icon: FileCheck },
    { to: '/jobs', label: 'Annonces', icon: Briefcase },
    { to: '/transactions', label: 'Paiements', icon: Wallet },
    { to: '/audit', label: 'Audit', icon: ScrollText },
    { to: '/categories', label: 'Catégories', icon: Tag },
  ];

  return (
    <div className="flex h-screen bg-background">
      <aside className="w-64 bg-white border-r flex flex-col">
        <div className="p-6 border-b">
          <h1 className="text-2xl font-bold text-primary">Jobylo Admin</h1>
        </div>
        <nav className="flex-1 p-4 space-y-1">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2 rounded-lg transition-colors ${
                  isActive ? 'bg-primary text-white' : 'text-foreground hover:bg-muted'
                }`
              }
            >
              <item.icon className="h-5 w-5" />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="p-4 border-t">
          <div className="flex items-center gap-3 mb-3">
            <Avatar>
              <AvatarFallback>{user?.firstName?.[0]}{user?.lastName?.[0]}</AvatarFallback>
            </Avatar>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">{user?.firstName} {user?.lastName}</p>
              <p className="text-xs text-muted-foreground truncate">{user?.email}</p>
            </div>
          </div>
          <Button variant="outline" className="w-full" onClick={() => { logout(); navigate('/login'); }}>
            <LogOut className="h-4 w-4 mr-2" /> Déconnexion
          </Button>
        </div>
      </aside>
      <main className="flex-1 overflow-y-auto">
        <Outlet />
      </main>
    </div>
  );
}
```

---

# Récapitulatif des endpoints utilisés

| Page | Endpoints |
|------|-----------|
| Login | `POST /auth/login` + `GET /auth/me` |
| Dashboard | `GET /admin/stats` |
| Utilisateurs | `GET /admin/users`, `GET /admin/users/{id}`, `PUT /admin/users/{id}/role`, `PUT /admin/users/{id}/kyc`, `DELETE /admin/users/{id}` |
| KYC | `GET /kyc/all?status=...`, `POST /kyc/{id}/approve`, `POST /kyc/{id}/reject` |
| Annonces | `GET /admin/jobs` |
| Transactions | `GET /admin/transactions` |
| Audit | `GET /admin/audit` |
| Catégories | `GET /admin/categories`, `POST /admin/categories`, `PUT /admin/categories/{id}`, `DELETE /admin/categories/{id}` |

# Points d'attention

1. **Images KYC** : `fileUrl` est un chemin relatif `/uploads/kyc/xxx.jpg`. Préfixer avec `API_BASE_URL` pour l'afficher.
2. **CORS** : s'assurer que le backend autorise `http://localhost:5173` (Vite) en dev.
3. **Refresh token** : implémenter la même logique que dans `prompt-frontend-error-handling.md` (queue + retry).
4. **Erreur 401** : rediriger vers `/login` automatiquement.
5. **Erreur 403** : afficher une page "Accès refusé" (un user essaie d'accéder à `/admin`).
6. **KYC en attente** : badge pulsant sur la sidebar quand `kycPending > 0`.
7. **Création d'admin** : 2 modes — promouvoir un user existant OU créer un nouveau compte avec `POST /auth/register` + `PUT /admin/users/{id}/role`.
8. **Hiérarchie catégories** : tree view avec expand/collapse, drag & drop (optionnel), maximum 2 niveaux.
9. **Audit** : très verbeux, prévoir filtres par action et recherche full-text.
10. **Pagination** : pas d'endpoint paginé côté backend pour `/admin/*` → implémenter côté client si > 100 entrées.
