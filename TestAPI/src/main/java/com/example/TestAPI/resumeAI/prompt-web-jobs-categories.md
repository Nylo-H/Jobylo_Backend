# Guide Web Frontend : Annonces (Jobs) & Catégories

## Stack
React + TypeScript + Vite + Shadcn/ui + Tailwind + React Query + Zustand + Axios

**API base :** `http://localhost:8080/api` (configurable via `VITE_API_URL`)
**Uploads :** préfixer les URLs relatives avec `API_BASE_URL`

---

## 1. Modèles TypeScript

```typescript
// types/job.ts

export type JobStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'EXPIRED';

export interface JobResponse {
  id: string;
  title: string;
  description: string;
  location: string;
  price: number;
  creatorId: string;
  creatorUsername: string;
  workerId: string | null;
  workerUsername: string | null;
  status: JobStatus;
  createdAt: string;   // ISO 8601
  updatedAt: string;   // ISO 8601
  images: string[];     // ⭐ URLs relatives ex: "/uploads/jobs/abc.jpg"
  categoryId: string | null;
  categoryName: string | null;
  applicationDeadline: string | null; // ISO 8601
}

export interface CreateJobRequest {
  title: string;
  description?: string;
  location?: string;
  price: number;
  images?: string[];
  categoryId?: string;
  applicationDeadline?: string; // ISO 8601, optionnel
}

// idem pour UpdateJobRequest (tous les champs optionnels)
``` 

```typescript
// types/category.ts

export interface CategoryResponse {
  id: string;
  name: string;
  description: string | null;
  icon: string | null;       // emoji ou nom Lucide
  parentId: string | null;
  displayOrder: number;
}

export interface CategoryTreeResponse {
  id: string;
  name: string;
  description: string | null;
  icon: string | null;
  displayOrder: number;
  subcategories: CategoryTreeResponse[];
}
```

```typescript
// types/application.ts

export type ApplicationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';

export interface ApplicationResponse {
  id: string;
  jobId: string;
  jobTitle: string;
  workerId: string;
  workerUsername: string;
  coverLetter: string | null;
  status: ApplicationStatus;
  createdAt: string;
}
```

---

## 2. Routes

```tsx
// routes.ts
<Routes>
  {/* 🔓 Publiques */}
  <Route path="/jobs" element={<AvailableJobsPage />} />              // liste des offres dispo
  <Route path="/jobs/:id" element={<JobDetailPage />} />              // fiche détaillée

  {/* 🔒 Authentifiées */}
  <Route path="/mes-annonces" element={<MyCreatedJobsPage />} />      // mes créations
  <Route path="/mes-missions" element={<MyAssignedJobsPage />} />     // mes missions
  <Route path="/jobs/new" element={<CreateJobPage />} />              // créer une annonce
  <Route path="/jobs/:id/edit" element={<EditJobPage />} />           // modifier
  <Route path="/jobs/:id/applicants" element={<ApplicantsPage />} />  // candidatures reçues
  <Route path="/applications" element={<MyApplicationsPage />} />     // mes candidatures
</Routes>
```

---

## 3. Appel API & Hook React Query

```typescript
// api/jobs.ts
import { api } from '@/lib/axios'; // axios avec intercepteur auth

export const jobsApi = {
  // Publiques
  getAvailable: (params?: JobFilters) =>
    api.get<JobResponse[]>('/jobs/available', { params }),

  getById: (id: string) =>
    api.get<JobResponse>(`/jobs/${id}`),

  // Authentifiées
  getMyCreated: () =>
    api.get<JobResponse[]>('/jobs/my-created'),

  getMyAssigned: () =>
    api.get<JobResponse[]>('/jobs/my-assigned'),

  create: (data: CreateJobRequest) =>
    api.post<JobResponse>('/jobs', data),

  update: (id: string, data: Partial<CreateJobRequest>) =>
    api.put<JobResponse>(`/jobs/${id}`, data),

  delete: (id: string) =>
    api.delete(`/jobs/${id}`),

  assign: (jobId: string, workerId: string) =>
    api.post<JobResponse>(`/jobs/${jobId}/assign`, { workerId }),

  updateStatus: (jobId: string, status: JobStatus) =>
    api.patch<JobResponse>(`/jobs/${jobId}/status`, null, { params: { status } }),

  expire: (jobId: string) =>
    api.post<JobResponse>(`/jobs/${jobId}/expire`),

  // Images
  uploadImage: (jobId: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<JobResponse>(`/jobs/${jobId}/images`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  removeImage: (jobId: string, imageUrl: string) =>
    api.delete<JobResponse>(`/jobs/${jobId}/images`, { params: { imageUrl } }),

  // Candidatures
  apply: (jobId: string, coverLetter?: string) =>
    api.post<ApplicationResponse>(`/jobs/${jobId}/apply`, { coverLetter }),

  getApplicants: (jobId: string) =>
    api.get<ApplicationResponse[]>(`/jobs/${jobId}/applicants`),

  rejectApplicant: (jobId: string, workerId: string) =>
    api.post(`/jobs/${jobId}/reject/${workerId}`),

  getApplicationsCount: (jobId: string) =>
    api.get<{ count: number }>(`/jobs/${jobId}/applicants/count`),
};

interface JobFilters {
  categoryId?: string;
  q?: string;
  minPrice?: number;
  maxPrice?: number;
  sort?: 'date_desc' | 'date_asc' | 'price_asc' | 'price_desc';
  location?: string;
}
```

---

## 4. Liste des offres disponibles

### Layout

```
┌────────────────────────────────────────────────────────────────┐
│ 🔍 [Rechercher...]    [Catégorie ▾]    [Ville ▾]    [Tri ▾]   │
│ Prix: [min] - [max]                                            │
│                                                                │
│ ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│ │ 🔧           │  │ 💻           │  │ 📦           │         │
│ │ Plomberie    │  │ Dev Web      │  │ Livraison    │         │
│ │ salle de bain│  │ React/Spring │  │ Colis urgent │         │
│ │ 250€         │  │ 1500€        │  │ 50€          │         │
│ │ Douala       │  │ Yaoundé      │  │ Bafoussam    │         │
│ │ ⭐ 4.5       │  │ ⭐ 3.8       │  │ ⭐ 5.0       │         │
│ │ [📅 3j rest] │  │              │  │ [📅 1j rest] │         │
│ └──────────────┘  └──────────────┘  └──────────────┘         │
│                                                                │
│ 1 2 3 ... 12                                                   │
└────────────────────────────────────────────────────────────────┘
```

### Code

```tsx
// pages/AvailableJobsPage.tsx
import { useState, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search, MapPin, Clock, Euro, SlidersHorizontal, Grid3X3, List } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
import { useDebounce } from '@/hooks/useDebounce';
import { JobCard } from '@/components/jobs/JobCard';
import { useCategories } from '@/hooks/useCategories';
import { jobsApi } from '@/api/jobs';

export function AvailableJobsPage() {
  const [search, setSearch] = useState('');
  const [categoryId, setCategoryId] = useState<string>('');
  const [location, setLocation] = useState('');
  const [sort, setSort] = useState('date_desc');
  const [priceRange, setPriceRange] = useState<[number, number]>([0, 10000]);
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');

  const debouncedSearch = useDebounce(search, 300);

  const { data: jobs, isLoading } = useQuery({
    queryKey: ['available-jobs', { categoryId, q: debouncedSearch, sort, location, minPrice: priceRange[0], maxPrice: priceRange[1] }],
    queryFn: () =>
      jobsApi.getAvailable({
        categoryId: categoryId || undefined,
        q: debouncedSearch || undefined,
        sort: sort as any,
        location: location || undefined,
        minPrice: priceRange[0] > 0 ? priceRange[0] : undefined,
        maxPrice: priceRange[1] < 10000 ? priceRange[1] : undefined,
      }).then((r) => r.data),
  });

  const { categories } = useCategories();

  return (
    <div className="max-w-7xl mx-auto p-6 space-y-6">
      <h1 className="text-3xl font-bold">Offres disponibles</h1>

      {/* Filtres */}
      <Card className="p-4">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
          {/* Recherche */}
          <div className="relative">
            <Search className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Rechercher un job..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-10"
            />
          </div>

          {/* Catégorie */}
          <Select value={categoryId} onValueChange={setCategoryId}>
            <SelectTrigger><SelectValue placeholder="Toutes les catégories" /></SelectTrigger>
            <SelectContent>
              <SelectItem value="all">Toutes</SelectItem>
              {categories.map((cat) => (
                <SelectItem key={cat.id} value={cat.id}>
                  {cat.icon} {cat.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {/* Localisation */}
          <div className="relative">
            <MapPin className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Ville..."
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              className="pl-10"
            />
          </div>

          {/* Tri */}
          <Select value={sort} onValueChange={setSort}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="date_desc">Plus récentes</SelectItem>
              <SelectItem value="date_asc">Plus anciennes</SelectItem>
              <SelectItem value="price_asc">Prix croissant</SelectItem>
              <SelectItem value="price_desc">Prix décroissant</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {/* Prix (slider) */}
        <div className="mt-3 flex items-center gap-3">
          <Euro className="h-4 w-4 text-muted-foreground" />
          <Slider
            value={priceRange}
            onValueChange={(v) => setPriceRange(v as [number, number])}
            min={0}
            max={10000}
            step={50}
            className="flex-1"
          />
          <span className="text-sm text-muted-foreground min-w-[120px] text-right">
            {priceRange[0]}€ - {priceRange[1]}€
          </span>
          <Button variant="ghost" size="icon" onClick={() => setViewMode(viewMode === 'grid' ? 'list' : 'grid')}>
            {viewMode === 'grid' ? <List className="h-4 w-4" /> : <Grid3X3 className="h-4 w-4" />}
          </Button>
        </div>
      </Card>

      {/* Résultats */}
      {isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => <JobCardSkeleton key={i} />)}
        </div>
      ) : jobs?.length === 0 ? (
        <div className="text-center py-16 text-muted-foreground">
          <Search className="h-12 w-12 mx-auto mb-4 opacity-50" />
          <p className="text-lg">Aucune annonce trouvée</p>
          <p>Essayez de modifier vos filtres</p>
        </div>
      ) : (
        <div className={viewMode === 'grid'
          ? 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4'
          : 'space-y-3'
        }>
          {jobs?.map((job) => (
            <JobCard key={job.id} job={job} variant={viewMode} />
          ))}
        </div>
      )}
    </div>
  );
}
```

---

## 5. Fiche détaillée d'un job

### Layout

```
┌──────────────────────────────────────────────────────────────┐
│ ← Retour aux offres                                 Badge    │
│ ┌─────────────────────────┐  ┌────────────────────────────┐ │
│ │                         │  │ Plomberie salle de bain    │ │
│ │  [Galerie d'images]     │  │ 🔧 Bâtiment                │ │
│ │                         │  │ 📍 Douala                  │ │
│ │  ○ ○ ○ ○ (miniatures)   │  │ 💰 250€                    │ │
│ └─────────────────────────┘  │                             │ │
│                              │ 🧑‍💼 Créé par Jean Dupont    │ │
│                              │ ⭐ 4.5 (23 avis)            │ │
│                              │ 📅 Publié le 28 mai 2026    │ │
│                              │ ⏳ 3 jours restants pour    │ │
│                              │   candidater                │ │
│                              │                             │ │
│                              │ ┌──────────────────────┐   │ │
│                              │ │  [Postuler]           │   │ │
│                              │ │  ou [Discuter]        │   │ │
│                              │ └──────────────────────┘   │ │
│                              └────────────────────────────┘ │
│                                                              │
│ ## Description                                               │
│ Lorem ipsum dolor sit amet...                                │
│                                                              │
│ ## Candidatures (3)                                          │
│ ┌─ Avatar ─┬─ Nom ────┬─ Date ────┬─ Lettre ──┬─ Actions ┐│
│ │ [img]    │ Paul M.  │ 30/05/26  │ "Bonjour" │ ✓ ✗     ││
│ └──────────┴──────────┴───────────┴───────────┴──────────┘│
└──────────────────────────────────────────────────────────────┘
```

### Code

```tsx
// pages/JobDetailPage.tsx
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { jobsApi } from '@/api/jobs';
import { useAuthStore } from '@/stores/authStore';
import { JobImageGallery } from '@/components/jobs/JobImageGallery';
import { JobActions } from '@/components/jobs/JobActions';
import { ApplicantsList } from '@/components/applications/ApplicantsList';
import { Badge } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar';
import { MapPin, Calendar, Euro, Clock, User, Star } from 'lucide-react';
import { toast } from 'sonner';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export function JobDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const queryClient = useQueryClient();

  const { data: job, isLoading } = useQuery({
    queryKey: ['job', id],
    queryFn: async () => (await jobsApi.getById(id!)).data,
    enabled: !!id,
  });

  const { data: applicants } = useQuery({
    queryKey: ['applicants', id],
    queryFn: async () => (await jobsApi.getApplicants(id!)).data,
    enabled: !!id && job?.creatorId === user?.id, // seulement pour le créateur
  });

  const applyMutation = useMutation({
    mutationFn: (coverLetter?: string) => jobsApi.apply(id!, coverLetter),
    onSuccess: () => {
      toast.success('Candidature envoyée');
      queryClient.invalidateQueries({ queryKey: ['my-applications'] });
    },
    onError: (e: any) => toast.error(e.response?.data?.error || 'Erreur'),
  });

  if (isLoading) return <JobDetailSkeleton />;
  if (!job) return <NotFound />;

  const isCreator = user?.id === job.creatorId;
  const isWorker = user?.id === job.workerId;
  const isExpiredOrDeadlinePassed = job.status === 'EXPIRED' ||
    (job.applicationDeadline && new Date(job.applicationDeadline) < new Date());

  return (
    <div className="max-w-5xl mx-auto p-6 space-y-6">
      {/* Fil d'Ariane */}
      <button onClick={() => navigate(-1)} className="text-sm text-muted-foreground hover:text-primary">
        ← Retour
      </button>

      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        {/* Galerie */}
        <div className="lg:col-span-3">
          <JobImageGallery images={job.images} title={job.title} />
        </div>

        {/* Sidebar */}
        <div className="lg:col-span-2 space-y-4">
          <div className="flex items-start justify-between">
            <h1 className="text-2xl font-bold">{job.title}</h1>
            <StatusBadge status={job.status} deadline={job.applicationDeadline} />
          </div>

          <div className="space-y-2 text-sm">
            {job.categoryName && (
              <div className="flex items-center gap-2 text-muted-foreground">
                <span>{job.categoryName}</span>
              </div>
            )}
            <div className="flex items-center gap-2 text-muted-foreground">
              <MapPin className="h-4 w-4" /> {job.location || 'Non spécifié'}
            </div>
            <div className="flex items-center gap-2 text-2xl font-bold text-primary">
              <Euro className="h-5 w-5" /> {job.price}€
            </div>
            <div className="flex items-center gap-2 text-muted-foreground">
              <Calendar className="h-4 w-4" /> Publié le {formatDate(job.createdAt)}
            </div>
            {job.applicationDeadline && (
              <div className="flex items-center gap-2">
                <Clock className="h-4 w-4" />
                <Countdown deadline={job.applicationDeadline} />
              </div>
            )}
          </div>

          {/* Créateur */}
          <Card className="p-4">
            <div className="flex items-center gap-3">
              <Avatar>
                <AvatarFallback>{job.creatorUsername[0]?.toUpperCase()}</AvatarFallback>
              </Avatar>
              <div>
                <p className="font-medium">{job.creatorUsername}</p>
                <p className="text-xs text-muted-foreground">Créateur de l'annonce</p>
              </div>
            </div>
          </Card>

          {/* Actions */}
          <JobActions
            job={job}
            isCreator={isCreator}
            isWorker={isWorker}
            currentUserId={user?.id}
            onApply={() => applyMutation.mutate()}
            onEdit={() => navigate(`/jobs/${job.id}/edit`)}
            onDelete={() => {
              if (confirm('Supprimer cette annonce ?')) {
                jobsApi.delete(job.id).then(() => navigate('/mes-annonces'));
              }
            }}
            onExpire={() => {
              if (confirm('Expirer cette annonce ?')) {
                jobsApi.expire(job.id).then(() => queryClient.invalidateQueries({ queryKey: ['job', id] }));
              }
            }}
          />

          {/* Worker assigné */}
          {job.workerId && (
            <Card className="p-4 bg-success/5 border-success/20">
              <div className="flex items-center gap-3">
                <User className="h-5 w-5 text-success" />
                <div>
                  <p className="font-medium">Assigné à {job.workerUsername}</p>
                  <p className="text-xs text-muted-foreground">En cours</p>
                </div>
              </div>
            </Card>
          )}
        </div>
      </div>

      {/* Description */}
      <Card className="p-6">
        <h2 className="text-lg font-semibold mb-3">Description</h2>
        <p className="whitespace-pre-wrap text-muted-foreground">{job.description}</p>
      </Card>

      {/* Candidatures (visible seulement pour le créateur, si PENDING) */}
      {isCreator && job.status === 'PENDING' && applicants && (
        <ApplicantsList
          jobId={job.id}
          applicants={applicants}
          onAssign={(workerId) => {
            jobsApi.assign(job.id, workerId).then(() => {
              toast.success('Worker assigné');
              queryClient.invalidateQueries({ queryKey: ['job', id] });
            });
          }}
          onReject={(workerId) => {
            jobsApi.rejectApplicant(job.id, workerId).then(() => {
              queryClient.invalidateQueries({ queryKey: ['applicants', id] });
            });
          }}
        />
      )}
    </div>
  );
}

// Composant badge statut
function StatusBadge({ status, deadline }: { status: JobStatus; deadline?: string | null }) {
  if (status === 'EXPIRED') return <Badge variant="outline" className="text-muted-foreground">Expirée</Badge>;
  if (deadline && new Date(deadline) < new Date()) return <Badge variant="outline" className="text-warning">Date limite passée</Badge>;
  switch (status) {
    case 'PENDING':     return <Badge className="bg-primary">Disponible</Badge>;
    case 'IN_PROGRESS': return <Badge className="bg-info">En cours</Badge>;
    case 'DONE':        return <Badge className="bg-success">Terminé</Badge>;
    default:            return null;
  }
}

// Compte à rebours
function Countdown({ deadline }: { deadline: string }) {
  const remaining = new Date(deadline).getTime() - Date.now();
  if (remaining <= 0) return <span className="text-error">Expiré</span>;
  const days = Math.floor(remaining / 86400000);
  const hours = Math.floor((remaining % 86400000) / 3600000);
  return <span className="text-warning">{days}j {hours}h restants</span>;
}
```

---

## 6. Composant Galerie d'images

```tsx
// components/jobs/JobImageGallery.tsx
import { useState } from 'react';
import { ChevronLeft, ChevronRight, ImageIcon, X } from 'lucide-react';
import { Dialog, DialogContent } from '@/components/ui/dialog';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

function getImageUrl(path: string): string {
  if (path.startsWith('http')) return path;
  return `${API_BASE}${path}`;
}

export function JobImageGallery({ images, title }: { images: string[]; title: string }) {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [lightboxOpen, setLightboxOpen] = useState(false);

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
      {/* Image principale */}
      <div className="relative w-full h-80 rounded-lg overflow-hidden cursor-pointer group"
           onClick={() => setLightboxOpen(true)}>
        <img
          src={getImageUrl(images[currentIndex])}
          alt={`${title} - ${currentIndex + 1}`}
          className="w-full h-full object-cover transition-transform group-hover:scale-105"
        />
        {images.length > 1 && (
          <>
            <div className="absolute top-3 right-3 bg-black/60 text-white text-xs px-2 py-1 rounded-full">
              {currentIndex + 1} / {images.length}
            </div>
            <button onClick={(e) => { e.stopPropagation(); setCurrentIndex(i => (i === 0 ? images.length - 1 : i - 1)); }}
                    className="absolute left-2 top-1/2 -translate-y-1/2 bg-black/40 hover:bg-black/60 text-white p-1.5 rounded-full opacity-0 group-hover:opacity-100">
              <ChevronLeft className="h-5 w-5" />
            </button>
            <button onClick={(e) => { e.stopPropagation(); setCurrentIndex(i => (i === images.length - 1 ? 0 : i + 1)); }}
                    className="absolute right-2 top-1/2 -translate-y-1/2 bg-black/40 hover:bg-black/60 text-white p-1.5 rounded-full opacity-0 group-hover:opacity-100">
              <ChevronRight className="h-5 w-5" />
            </button>
          </>
        )}
      </div>

      {/* Miniatures */}
      {images.length > 1 && (
        <div className="flex gap-2 mt-3 overflow-x-auto pb-2">
          {images.map((img, idx) => (
            <button key={idx} onClick={() => setCurrentIndex(idx)}
                    className={`relative flex-shrink-0 w-20 h-16 rounded-md overflow-hidden border-2 transition-all ${
                      idx === currentIndex ? 'border-primary ring-2 ring-primary/30' : 'border-transparent hover:border-muted-foreground/50'
                    }`}>
              <img src={getImageUrl(img)} alt={`Miniature ${idx + 1}`}
                   className="w-full h-full object-cover" />
            </button>
          ))}
        </div>
      )}

      {/* Lightbox */}
      <Dialog open={lightboxOpen} onOpenChange={setLightboxOpen}>
        <DialogContent className="max-w-5xl h-[90vh] p-0 bg-black/95">
          <div className="relative w-full h-full flex items-center justify-center">
            <button onClick={() => setLightboxOpen(false)}
                    className="absolute top-4 right-4 z-10 bg-black/60 hover:bg-black/80 text-white p-2 rounded-full">
              <X className="h-6 w-6" />
            </button>
            <div className="relative w-full h-full p-12">
              <img src={getImageUrl(images[currentIndex])}
                   alt={`${title} - ${currentIndex + 1}`}
                   className="w-full h-full object-contain" />
            </div>
            {images.length > 1 && (
              <>
                <button onClick={() => setCurrentIndex(i => (i === 0 ? images.length - 1 : i - 1))}
                        className="absolute left-4 top-1/2 -translate-y-1/2 bg-black/60 hover:bg-black/80 text-white p-3 rounded-full">
                  <ChevronLeft className="h-6 w-6" />
                </button>
                <button onClick={() => setCurrentIndex(i => (i === images.length - 1 ? 0 : i + 1))}
                        className="absolute right-4 top-1/2 -translate-y-1/2 bg-black/60 hover:bg-black/80 text-white p-3 rounded-full">
                  <ChevronRight className="h-6 w-6" />
                </button>
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

---

## 7. Mes annonces (créateur)

```tsx
// pages/MyCreatedJobsPage.tsx
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Plus, Clock, CheckCircle2, XCircle, Eye } from 'lucide-react';
import { jobsApi } from '@/api/jobs';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

const statusTabs = [
  { value: 'PENDING', label: 'Disponibles', icon: Clock },
  { value: 'IN_PROGRESS', label: 'En cours', icon: Eye },
  { value: 'DONE', label: 'Terminées', icon: CheckCircle2 },
  { value: 'EXPIRED', label: 'Expirées', icon: XCircle },
  { value: 'all', label: 'Toutes' },
];

export function MyCreatedJobsPage() {
  const [tab, setTab] = useState('PENDING');
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data: jobs = [] } = useQuery({
    queryKey: ['my-created-jobs'],
    queryFn: async () => (await jobsApi.getMyCreated()).data,
  });

  const filtered = tab === 'all' ? jobs : jobs.filter((j) => j.status === tab);

  const expireMutation = useMutation({
    mutationFn: (jobId: string) => jobsApi.expire(jobId),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['my-created-jobs'] }); toast.success('Annonce expirée'); },
  });

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold">Mes annonces</h1>
        <Button onClick={() => navigate('/jobs/new')}><Plus className="h-4 w-4 mr-2" /> Nouvelle annonce</Button>
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          {statusTabs.map((t) => (
            <TabsTrigger key={t.value} value={t.value} className="gap-2">
              <t.icon className="h-4 w-4" /> {t.label} ({jobs.filter((j) => t.value === 'all' || j.status === t.value).length})
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value={tab}>
          {filtered.length === 0 ? (
            <div className="text-center py-16 text-muted-foreground">
              <p className="text-lg">Aucune annonce</p>
              <Button variant="link" onClick={() => navigate('/jobs/new')}>Créer une annonce</Button>
            </div>
          ) : (
            <div className="space-y-3">
              {filtered.map((job) => (
                <JobListItem
                  key={job.id}
                  job={job}
                  onView={() => navigate(`/jobs/${job.id}`)}
                  onEdit={() => job.status === 'PENDING' && navigate(`/jobs/${job.id}/edit`)}
                  onExpire={() => job.status === 'PENDING' && expireMutation.mutate(job.id)}
                  onApplicants={() => navigate(`/jobs/${job.id}/applicants`)}
                />
              ))}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
```

```tsx
// components/jobs/JobListItem.tsx
export function JobListItem({ job, onView, onEdit, onExpire, onApplicants }: {
  job: JobResponse;
  onView: () => void;
  onEdit?: () => void;
  onExpire?: () => void;
  onApplicants?: () => void;
}) {
  return (
    <Card className="p-4 hover:shadow-md transition-shadow">
      <div className="flex items-start gap-4">
        {/* Miniature */}
        <div className="w-24 h-20 rounded-lg overflow-hidden flex-shrink-0 bg-muted">
          {job.images?.[0] ? (
            <img src={getImageUrl(job.images[0])} alt="" className="w-full h-full object-cover" />
          ) : (
            <div className="w-full h-full flex items-center justify-center text-muted-foreground text-xs">Pas d'image</div>
          )}
        </div>

        {/* Infos */}
        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between">
            <div>
              <h3 className="font-semibold truncate">{job.title}</h3>
              <p className="text-sm text-muted-foreground">{job.location} · {job.categoryName}</p>
            </div>
            <div className="text-right flex-shrink-0">
              <p className="font-bold text-primary">{job.price}€</p>
              <StatusBadge status={job.status} />
            </div>
          </div>
          <p className="text-sm text-muted-foreground line-clamp-1 mt-1">{job.description}</p>

          {/* Stats */}
          <div className="flex items-center gap-4 mt-2 text-xs text-muted-foreground">
            <span>📅 {formatDate(job.createdAt)}</span>
            {job.workerId && <span>👤 {job.workerUsername}</span>}
            {job.status === 'PENDING' && (
              <button onClick={onApplicants} className="hover:text-primary">👥 Voir les candidatures</button>
            )}
          </div>
        </div>

        {/* Actions */}
        <div className="flex gap-2 flex-shrink-0">
          <Button size="sm" variant="outline" onClick={onView}><Eye className="h-4 w-4" /></Button>
          {job.status === 'PENDING' && (
            <>
              <Button size="sm" variant="outline" onClick={onEdit}>Modifier</Button>
              <Button size="sm" variant="outline" onClick={onExpire} className="text-warning">Expirer</Button>
            </>
          )}
        </div>
      </div>
    </Card>
  );
}
```

---

## 8. Mes missions (worker)

```tsx
// pages/MyAssignedJobsPage.tsx
export function MyAssignedJobsPage() {
  const { data: jobs = [] } = useQuery({
    queryKey: ['my-assigned-jobs'],
    queryFn: async () => (await jobsApi.getMyAssigned()).data,
  });

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-6">
      <h1 className="text-3xl font-bold">Mes missions</h1>

      <Tabs defaultValue="IN_PROGRESS">
        <TabsList>
          {['IN_PROGRESS', 'DONE', 'all'].map((tab) => (
            <TabsTrigger key={tab} value={tab}>
              {tab === 'all' ? 'Toutes' : tab === 'IN_PROGRESS' ? 'En cours' : 'Terminées'}
              ({jobs.filter((j) => tab === 'all' || j.status === tab).length})
            </TabsTrigger>
          ))}
        </TabsList>
        {/* ... liste identique à my-created mais sans bouton Modifier/Expirer */}
      </Tabs>
    </div>
  );
}
```

---

## 9. Créer / Modifier une annonce

```tsx
// pages/CreateJobPage.tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { DatePicker } from '@/components/ui/date-picker';
import { CategorySelect } from '@/components/categories/CategorySelect';
import { JobImageUploader } from '@/components/jobs/JobImageUploader';
import { useCategories } from '@/hooks/useCategories';
import { jobsApi } from '@/api/jobs';
import { toast } from 'sonner';

export function CreateJobPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { categories } = useCategories();

  const [form, setForm] = useState({
    title: '',
    description: '',
    location: '',
    price: 0,
    categoryId: '',
    images: [] as string[],
    applicationDeadline: null as Date | null,
  });

  const createMutation = useMutation({
    mutationFn: () => jobsApi.create({
      ...form,
      price: form.price,
      categoryId: form.categoryId || undefined,
      applicationDeadline: form.applicationDeadline?.toISOString(),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['available-jobs'] });
      queryClient.invalidateQueries({ queryKey: ['my-created-jobs'] });
      toast.success('Annonce créée !');
      navigate('/mes-annonces');
    },
    onError: (e: any) => toast.error(e.response?.data?.error || 'Erreur'),
  });

  return (
    <div className="max-w-3xl mx-auto p-6 space-y-6">
      <h1 className="text-3xl font-bold">Nouvelle annonce</h1>

      <Card className="p-6 space-y-5">
        {/* Images */}
        <div>
          <Label>Photos du projet</Label>
          <JobImageUploader
            images={form.images}
            onChange={(imgs) => setForm({ ...form, images: imgs })}
            onUpload={async (file) => {
              // Upload via l'endpoint d'upload global s'il existe
              // Sinon upload après création via POST /jobs/{id}/images
              return ''; // sera géré après création
            }}
          />
        </div>

        {/* Titre */}
        <div>
          <Label htmlFor="title">Titre *</Label>
          <Input id="title" required value={form.title}
                 onChange={(e) => setForm({ ...form, title: e.target.value })} />
        </div>

        {/* Description */}
        <div>
          <Label htmlFor="description">Description</Label>
          <Textarea id="description" rows={5} value={form.description}
                    onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </div>

        {/* Localisation */}
        <div>
          <Label htmlFor="location">Localisation</Label>
          <Input id="location" value={form.location}
                 onChange={(e) => setForm({ ...form, location: e.target.value })} />
        </div>

        {/* Prix + Catégorie */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <Label htmlFor="price">Prix (€) *</Label>
            <Input id="price" type="number" min={0} step={0.01} required value={form.price || ''}
                   onChange={(e) => setForm({ ...form, price: parseFloat(e.target.value) || 0 })} />
          </div>
          <div>
            <Label>Catégorie</Label>
            <CategorySelect
              categories={categories}
              value={form.categoryId}
              onChange={(v) => setForm({ ...form, categoryId: v })}
            />
          </div>
        </div>

        {/* Date limite */}
        <div>
          <Label>Date limite de candidature (optionnelle)</Label>
          <DatePicker
            selected={form.applicationDeadline}
            onSelect={(date) => setForm({ ...form, applicationDeadline: date })}
            minDate={new Date(Date.now() + 86400000)} // au moins demain
            placeholder="Pas de date limite"
          />
        </div>

        <div className="flex justify-end gap-3 pt-4">
          <Button variant="outline" onClick={() => navigate(-1)}>Annuler</Button>
          <Button onClick={() => createMutation.mutate()} disabled={!form.title || !form.price || createMutation.isPending}>
            {createMutation.isPending ? 'Publication...' : 'Publier l\'annonce'}
          </Button>
        </div>
      </Card>
    </div>
  );
}
```

---

## 10. Catégories (sélecteur + arbre)

```typescript
// hooks/useCategories.ts
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/axios';

export function useCategories() {
  const { data: categories = [], isLoading } = useQuery({
    queryKey: ['categories'],
    queryFn: async () => (await api.get<CategoryResponse[]>('/categories')).data,
    staleTime: 1000 * 60 * 10, // cache 10 minutes
  });

  const { data: tree = [] } = useQuery({
    queryKey: ['categories-tree'],
    queryFn: async () => (await api.get<CategoryTreeResponse[]>('/categories/tree')).data,
    staleTime: 1000 * 60 * 10,
  });

  return { categories, tree, isLoading };
}
```

```tsx
// components/categories/CategorySelect.tsx
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { CategoryResponse } from '@/types/category';

export function CategorySelect({ categories, value, onChange, placeholder = 'Choisir une catégorie' }: {
  categories: CategoryResponse[];
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  // Grouper par parent pour l'affichage hiérarchique
  const roots = categories.filter((c) => !c.parentId).sort((a, b) => a.displayOrder - b.displayOrder);

  return (
    <Select value={value || ''} onValueChange={onChange}>
      <SelectTrigger>
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="">Non catégorisé</SelectItem>
        {roots.map((root) => (
          <CategoryGroup key={root.id} root={root} allCategories={categories} depth={0} />
        ))}
      </SelectContent>
    </Select>
  );
}

function CategoryGroup({ root, allCategories, depth }: { root: CategoryResponse; allCategories: CategoryResponse[]; depth: number }) {
  const children = allCategories.filter((c) => c.parentId === root.id).sort((a, b) => a.displayOrder - b.displayOrder);
  const prefix = depth > 0 ? '\u00A0\u00A0\u00A0\u00A0'.repeat(depth) : '';

  return (
    <>
      <SelectItem value={root.id}>
        {prefix}{root.icon || '📁'} {root.name}
      </SelectItem>
      {children.map((child) => (
        <CategoryGroup key={child.id} root={child} allCategories={allCategories} depth={depth + 1} />
      ))}
    </>
  );
}
```

```tsx
// components/categories/CategoryTree.tsx
// Pour affichage arbre dans les filtres ou footer
export function CategoryTree({ tree, onSelect }: { tree: CategoryTreeResponse[]; onSelect?: (id: string) => void }) {
  return (
    <div className="space-y-1">
      {tree.sort((a, b) => a.displayOrder - b.displayOrder).map((cat) => (
        <CategoryNode key={cat.id} node={cat} level={0} onSelect={onSelect} />
      ))}
    </div>
  );
}

function CategoryNode({ node, level, onSelect }: { node: CategoryTreeResponse; level: number; onSelect?: (id: string) => void }) {
  const [expanded, setExpanded] = useState(true);

  return (
    <div>
      <button
        onClick={() => (onSelect?.(node.id), setExpanded(!expanded))}
        className={`flex items-center gap-2 p-2 w-full text-left rounded hover:bg-muted transition-colors ${level > 0 ? 'ml-6' : ''}`}
      >
        {node.subcategories?.length > 0 && (
          <ChevronRight className={`h-4 w-4 transition-transform ${expanded ? 'rotate-90' : ''}`} />
        )}
        <span className="text-lg">{node.icon || '📁'}</span>
        <span className="font-medium">{node.name}</span>
      </button>
      {expanded && node.subcategories?.map((sub) => (
        <CategoryNode key={sub.id} node={sub} level={level + 1} onSelect={onSelect} />
      ))}
    </div>
  );
}
```

---

## 11. Uploader d'images

```tsx
// components/jobs/JobImageUploader.tsx
// Version simplifiée multi-upload avec preview

import { useRef, useState } from 'react';
import { Upload, X } from 'lucide-react';
import { Button } from '@/components/ui/button';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export function JobImageUploader({ images, onChange, onUpload }: {
  images: string[];
  onChange: (images: string[]) => void;
  onUpload: (file: File) => Promise<string>;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  const handleSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    if (files.length === 0) return;
    setUploading(true);
    try {
      const urls = await Promise.all(files.map((f) => onUpload(f)));
      onChange([...images, ...urls]);
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  return (
    <div className="flex flex-wrap gap-3">
      {images.map((url, i) => (
        <div key={i} className="relative group w-28 h-28 rounded-lg overflow-hidden border">
          <img src={url.startsWith('http') ? url : `${API_BASE}${url}`} alt=""
               className="w-full h-full object-cover" />
          <button onClick={() => onChange(images.filter((_, j) => j !== i))}
                  className="absolute top-1 right-1 bg-destructive text-destructive-foreground p-1 rounded-full opacity-0 group-hover:opacity-100 transition-opacity">
            <X className="h-4 w-4" />
          </button>
          <div className="absolute bottom-1 left-1 bg-black/60 text-white text-xs px-1.5 py-0.5 rounded">{i + 1}</div>
        </div>
      ))}
      <label className={`w-28 h-28 border-2 border-dashed rounded-lg flex flex-col items-center justify-center gap-1 cursor-pointer hover:border-primary hover:bg-primary/5 ${uploading ? 'animate-pulse opacity-50' : ''}`}>
        <Upload className="h-6 w-6 text-muted-foreground" />
        <span className="text-xs text-muted-foreground">{uploading ? 'Upload...' : 'Ajouter'}</span>
        <input ref={fileRef} type="file" accept="image/*" multiple className="hidden" onChange={handleSelect} disabled={uploading} />
      </label>
    </div>
  );
}
```

---

## 12. Tri intelligent (frontend + backend combiné)

Le backend gère déjà 4 sorts (date, prix) sur `/jobs/available`. Pour aller plus loin :

```tsx
// Tri "intelligent" qui combine plusieurs critères
const sortOptions = [
  { value: 'date_desc', label: '📅 Plus récentes' },
  { value: 'price_asc',  label: '💰 Prix croissant' },
  { value: 'price_desc', label: '💰 Prix décroissant' },
  // ⭐ Les tris suivants sont faits côté FRONTEND sur les résultats déjà chargés
];

// Fonction utilitaire pour tri local
function sortJobs(jobs: JobResponse[], sortKey: string): JobResponse[] {
  const copy = [...jobs];
  switch (sortKey) {
    case 'rating_desc':
      // Tri par note du créateur (nécessite d'avoir chargé les notes)
      // optionnel : interroger GET /users/{creatorId} pour chaque job
      return copy;
    case 'deadline_asc':
      // Tri par date limite la plus proche
      return copy.sort((a, b) => {
        if (!a.applicationDeadline) return 1;
        if (!b.applicationDeadline) return -1;
        return new Date(a.applicationDeadline).getTime() - new Date(b.applicationDeadline).getTime();
      });
    case 'relevance':
      // Tri par pertinence : deadline proche + prix bas + note haute
      return copy.sort((a, b) => {
        const scoreA = computeRelevance(a);
        const scoreB = computeRelevance(b);
        return scoreB - scoreA;
      });
    default:
      return copy;
  }
}

function computeRelevance(job: JobResponse): number {
  let score = 100;
  // Poids prix : plus c'est cher, moins c'est "pertinent"
  score -= job.price * 0.01;
  // Poids deadline : plus c'est urgent, plus c'est pertinent
  if (job.applicationDeadline) {
    const daysLeft = (new Date(job.applicationDeadline).getTime() - Date.now()) / 86400000;
    if (daysLeft < 3) score += 30; // urgent
    else if (daysLeft < 7) score += 15;
  }
  return Math.max(0, score);
}
```

---

## 13. Récapitulatif des endpoints

| Action | Méthode | Endpoint | Auth |
|--------|---------|----------|------|
| **Lister les offres disponibles** | GET | `/jobs/available?categoryId=&q=&minPrice=&maxPrice=&sort=&location=` | ❌ |
| **Détail d'une offre** | GET | `/jobs/{id}` | ❌ |
| **Mes annonces créées** | GET | `/jobs/my-created` | ✅ |
| **Mes missions** | GET | `/jobs/my-assigned` | ✅ |
| **Créer une annonce** | POST | `/jobs` | ✅ |
| **Modifier une annonce** | PUT | `/jobs/{id}` | ✅ |
| **Supprimer une annonce** | DELETE | `/jobs/{id}` | ✅ |
| **Expirer une annonce** | POST | `/jobs/{id}/expire` | ✅ |
| **Changer statut** | PATCH | `/jobs/{id}/status?status=DONE` | ✅ |
| **Assigner un worker** | POST | `/jobs/{id}/assign` | ✅ |
| **Postuler** | POST | `/jobs/{id}/apply` | ✅ |
| **Candidatures reçues** | GET | `/jobs/{id}/applicants` | ✅ |
| **Rejeter un candidat** | POST | `/jobs/{id}/reject/{workerId}` | ✅ |
| **Nb candidatures** | GET | `/jobs/{id}/applicants/count` | ✅ |
| **Upload image** | POST | `/jobs/{id}/images` (multipart) | ✅ |
| **Supprimer image** | DELETE | `/jobs/{id}/images?imageUrl=` | ✅ |
| **Mes candidatures** | GET | `/applications/mine` | ✅ |
| **Catégories (plat)** | GET | `/categories` | ❌ |
| **Catégories (arbre)** | GET | `/categories/tree` | ❌ |
| **Sous-catégories** | GET | `/categories/{parentId}/subcategories` | ❌ |
| **Détail catégorie** | GET | `/categories/{id}` | ❌ |

## 14. Points d'attention

1. **Images** : les `fileUrl` sont relatives (`/uploads/jobs/xxx.jpg`). Toujours préfixer avec `API_BASE_URL`.
2. **Création job** : les images sont stockées en 2 temps — 1) upload via `POST /jobs/{id}/images` (multipart), 2) ajout de l'URL retournée dans le tableau `images` de `CreateJobRequest` ou via `addImages`.
3. **Date limite** : envoyer au format ISO 8601 UTC (`2026-06-15T23:59:59.000Z`). Optionnelle.
4. **Statuts** : PENDING (avec deadline future) → assigment → IN_PROGRESS → DONE. EXPIRED = terminal (pas de retour possible).
5. **Pagination** : `/jobs/available` retourne une liste simple (pas paginée côté backend). Si > 100 résultats, implémenter la pagination côté frontend (charge tout et pagine localement) ou demander un endpoint paginé.
6. **Candidatures** : un worker ne peut postuler qu'une fois par job. Vérifier le statut 409 (CONFLICT) pour l'afficher élégamment.
7. **Permissions** : les mutations sur un job nécessitent d'être le créateur. Les endpoints retournent 403 si unauthorized.
