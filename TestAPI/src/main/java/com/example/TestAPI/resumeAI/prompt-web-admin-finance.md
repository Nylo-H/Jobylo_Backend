# Prompt Frontend Web Admin : Section Finance

## Contexte

Section financière à ajouter au dashboard admin existant. Stack : **React + Vite + TypeScript + Shadcn/ui + Tailwind + React Query + Recharts + Zustand + Axios**.

Le backend expose un nouvel endpoint **`GET /admin/finance`** (protégé ADMIN) qui retourne :

```typescript
interface FinanceStatsResponse {
  totalVolume: number;                    // SUM(amount) des COMPLETED
  heldAmount: number;                     // SUM(amount) des HELD (en attente)
  totalCommissionCollected: number;       // SUM(commissionAmount) des COMPLETED
  totalPaidToWorkers: number;             // SUM(netAmount) des COMPLETED
  totalTransactions: number;              // COUNT(*) toutes
  completedTransactions: number;
  heldTransactions: number;
  cancelledTransactions: number;
  averageTransactionAmount: number;       // totalVolume / completedTransactions
  revenueByMonth: MonthlyRevenue[];       // 12 derniers mois
}

interface MonthlyRevenue {
  year: number;
  month: number;
  volume: number;       // SUM(amount) du mois
  commission: number;   // SUM(commissionAmount) du mois
}
```

## Fichiers à créer

```
src/
  api/
    finance.ts              # API hook
  components/
    dashboard/
      FinanceCards.tsx      # 4 cards récapitulatives
      RevenueChart.tsx      # Graphique barres 12 mois
  pages/
    admin/
      FinancePage.tsx       # Page autonome (optionnelle)
```

## 1. API Hook : `src/api/finance.ts`

```typescript
import { api } from '@/lib/axios';

export interface MonthlyRevenue {
  year: number;
  month: number;
  volume: number;
  commission: number;
}

export interface FinanceStatsResponse {
  totalVolume: number;
  heldAmount: number;
  totalCommissionCollected: number;
  totalPaidToWorkers: number;
  totalTransactions: number;
  completedTransactions: number;
  heldTransactions: number;
  cancelledTransactions: number;
  averageTransactionAmount: number;
  revenueByMonth: MonthlyRevenue[];
}

export const financeApi = {
  getStats: () =>
    api.get<FinanceStatsResponse>('/admin/finance').then((r) => r.data),
};
```

## 2. React Query Hook

Dans un fichier type `src/hooks/useFinance.ts` :

```typescript
import { useQuery } from '@tanstack/react-query';
import { financeApi } from '@/api/finance';

export function useFinanceStats() {
  return useQuery({
    queryKey: ['admin', 'finance'],
    queryFn: financeApi.getStats,
    refetchInterval: 60_000, // refresh auto toutes les 60s
  });
}
```

## 3. Composant : `FinanceCards.tsx`

```tsx
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useFinanceStats } from '@/hooks/useFinance';
import { Euro, Banknote, TrendingUp, Percent, ArrowUpRight, Clock, XCircle } from 'lucide-react';

function formatEur(value: number): string {
  return new Intl.NumberFormat('fr-FR', {
    style: 'currency',
    currency: 'EUR',
    maximumFractionDigits: 0,
  }).format(value);
}

export function FinanceCards() {
  const { data, isLoading } = useFinanceStats();

  if (isLoading) {
    return (
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Card key={i}>
            <CardHeader className="pb-2"><Skeleton className="h-4 w-24" /></CardHeader>
            <CardContent><Skeleton className="h-8 w-32" /></CardContent>
          </Card>
        ))}
      </div>
    );
  }

  if (!data) return null;

  const cards = [
    {
      title: 'Volume total',
      value: formatEur(data.totalVolume),
      sub: `${data.completedTransactions} transactions complétées`,
      icon: Euro,
      color: 'text-green-600',
      bg: 'bg-green-100',
    },
    {
      title: 'Commission prélevée',
      value: formatEur(data.totalCommissionCollected),
      sub: `${((data.totalCommissionCollected / data.totalVolume) * 100).toFixed(1)}% du volume`,
      icon: TrendingUp,
      color: 'text-blue-600',
      bg: 'bg-blue-100',
    },
    {
      title: 'Net versé aux workers',
      value: formatEur(data.totalPaidToWorkers),
      sub: `${data.completedTransactions} paiements effectués`,
      icon: Banknote,
      color: 'text-purple-600',
      bg: 'bg-purple-100',
    },
    {
      title: 'Montant moyen / job',
      value: formatEur(data.averageTransactionAmount),
      sub: 'toutes transactions confondues',
      icon: ArrowUpRight,
      color: 'text-orange-600',
      bg: 'bg-orange-100',
    },
  ];

  return (
    <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
      {cards.map((card) => (
        <Card key={card.title}>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">
              {card.title}
            </CardTitle>
            <div className={`rounded-lg ${card.bg} p-2`}>
              <card.icon className={`h-4 w-4 ${card.color}`} />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{card.value}</div>
            <p className="mt-1 text-xs text-muted-foreground">{card.sub}</p>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
```

## 4. Composant : `RevenueChart.tsx`

```tsx
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Legend,
} from 'recharts';
import { useFinanceStats, MonthlyRevenue } from '@/hooks/useFinance';

const MONTHS = ['Jan','Fév','Mar','Avr','Mai','Juin','Juil','Aoû','Sep','Oct','Nov','Déc'];

function formatMonth(m: MonthlyRevenue): string {
  return `${MONTHS[m.month - 1]} ${m.year}`;
}

export function RevenueChart() {
  const { data, isLoading } = useFinanceStats();

  if (isLoading) {
    return (
      <Card>
        <CardHeader><Skeleton className="h-5 w-48" /></CardHeader>
        <CardContent><Skeleton className="h-72 w-full" /></CardContent>
      </Card>
    );
  }

  if (!data?.revenueByMonth?.length) {
    return (
      <Card>
        <CardHeader><CardTitle>Revenus mensuels</CardTitle></CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground text-center py-12">
            Aucune transaction complétée sur les 12 derniers mois.
          </p>
        </CardContent>
      </Card>
    );
  }

  const chartData = [...data.revenueByMonth].reverse().map((m) => ({
    name: formatMonth(m),
    Volume: m.volume,
    Commission: m.commission,
  }));

  const formatEuro = (v: number) =>
    new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Revenus mensuels (12 derniers mois)</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
              <XAxis dataKey="name" tick={{ fontSize: 11 }} interval={1} />
              <YAxis tickFormatter={formatEuro} tick={{ fontSize: 11 }} />
              <Tooltip formatter={(v: number) => formatEuro(v)} />
              <Legend />
              <Bar dataKey="Volume" fill="#0D47A1" name="Volume" radius={[4, 4, 0, 0]} />
              <Bar dataKey="Commission" fill="#F59E0B" name="Commission" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </CardContent>
    </Card>
  );
}
```

## 5. Section à intégrer dans le Dashboard

Dans la page `AdminDashboardPage`, ajouter entre les stats générales et la liste des dernières transactions :

```tsx
import { FinanceCards } from '@/components/dashboard/FinanceCards';
import { RevenueChart } from '@/components/dashboard/RevenueChart';

export default function AdminDashboardPage() {
  return (
    <div className="space-y-6">
      {/* Stats existantes (users, jobs, etc.) */}
      <StatsCards />

      {/* --- Section Finance --- */}
      <div className="space-y-6">
        <h2 className="text-lg font-semibold">Finances</h2>
        <FinanceCards />
        <RevenueChart />
      </div>

      {/* Dernières transactions (existantes) */}
      <RecentTransactions />
    </div>
  );
}
```

## 6. (Optionnel) Page autonome : `src/pages/admin/FinancePage.tsx`

Si tu préfères une page dédiée plutôt que d'intégrer dans le dashboard :

```tsx
import { FinanceCards } from '@/components/dashboard/FinanceCards';
import { RevenueChart } from '@/components/dashboard/RevenueChart';
import { TransactionList } from '@/components/admin/TransactionList';
import { AdminGuard } from '@/components/auth/AdminGuard';

export default function FinancePage() {
  return (
    <AdminGuard>
      <div className="space-y-6 p-6">
        <h1 className="text-2xl font-bold">Finance</h1>
        <FinanceCards />
        <RevenueChart />
        <TransactionList /> {/* utilise GET /admin/transactions existant */}
      </div>
    </AdminGuard>
  );
}
```

## 7. Palette de couleurs (chart)

| Élément | Couleur | Usage |
|---------|---------|-------|
| Volume (barres) | `#0D47A1` (primary) | Barre principale du graphique |
| Commission (barres) | `#F59E0B` (warning) | Barre secondaire superposée |
| Cards bg icônes | green-100, blue-100, purple-100, orange-100 | Pastel par carte |
| Cards icônes | green-600, blue-600, purple-600, orange-600 | Icone par carte |

## Résumé

| Composant | Fichier | Lignes estimées |
|-----------|---------|-----------------|
| API hook | `src/api/finance.ts` | ~25 |
| React Query | `src/hooks/useFinance.ts` | ~12 |
| FinanceCards | `src/components/dashboard/FinanceCards.tsx` | ~100 |
| RevenueChart | `src/components/dashboard/RevenueChart.tsx` | ~80 |
| Intégration dashboard | modifier `AdminDashboardPage.tsx` | ~6 |

**Total : ~220 lignes de TypeScript/TSX pour une section finance complète avec graphique dynamique Recharts.**
