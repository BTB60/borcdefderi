"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { DEBT_STATUS_LABEL, DEBT_TYPE_LABEL, METHOD_LABEL, METHOD_TONE, formatDate, formatDateTime, formatMoney } from "@/lib/format";
import type { Dashboard } from "@/lib/types";
import { Alert, Badge, Card, EmptyState, PageHeader, Spinner, StatCard, Table, Td, Th } from "@/components/ui";

function BarChart({ data, label }: { data: { label: string; value: number; cash: number; card: number; other: number }[]; label: string }) {
  const max = Math.max(1, ...data.map((d) => d.value));
  return (
    <div role="img" aria-label={label} className="flex h-48 items-end gap-1.5 sm:gap-2">
      {data.map((d) => (
        <div key={d.label} className="group flex flex-1 flex-col items-center gap-2">
          <div className="relative flex w-full flex-1 items-end">
            <div
              className="w-full rounded-t-md bg-gradient-to-t from-navy-800 to-navy-600 transition-all duration-500 group-hover:from-gold-600 group-hover:to-gold-400"
              style={{ height: `${Math.max(3, (d.value / max) * 100)}%` }}
              title={`${d.label}: ${formatMoney(d.value)} (nağd ${formatMoney(d.cash)}, kart ${formatMoney(d.card)}, köçürmə/digər ${formatMoney(d.other)})`}
            />
          </div>
          <span className="text-[10px] text-slate-400 sm:text-xs">{d.label}</span>
        </div>
      ))}
    </div>
  );
}

export default function DashboardPage() {
  const { can, isOwner } = useAuth();
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Dashboard>("/api/dashboard")
      .then(setData)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : "Panel yüklənə bilmədi"));
  }, []);

  if (!isOwner && !can("REPORTS")) {
    return (
      <>
        <PageHeader title="Panel" />
        <Card>
          <EmptyState title="Panel sizin üçün açıq deyil" text="Mağaza sahibi sizə hesabat icazəsi verərsə, burada məlumatları görə bilərsiniz." />
        </Card>
      </>
    );
  }
  if (error) return <Alert>{error}</Alert>;
  if (!data) return <Spinner />;

  const s = data.summary;
  const daily = data.daily.map((d) => ({
    label: d.date.slice(8, 10) + "." + d.date.slice(5, 7),
    value: d.total,
    cash: d.cash,
    card: d.card,
    other: d.other ?? 0,
  }));
  const monthly = data.monthly.map((m) => ({
    label: m.month.slice(5) + "/" + m.month.slice(2, 4),
    value: m.total,
    cash: m.cash,
    card: m.card,
    other: m.other ?? 0,
  }));

  return (
    <>
      <PageHeader title="Panel" subtitle="Bütün rəqəmlər birbaşa veritabanından hesablanır" />

      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="Əsas göstəricilər">
        <StatCard label="Müştəri borcları" value={formatMoney(s.customerDebts)} hint="Qalıq məbləğ" tone="navy" />
        <StatCard label="Şirkət alacaqları" value={formatMoney(s.companyReceivables)} hint="Qalıq məbləğ" tone="emerald" />
        <StatCard label="Şirkət borcları" value={formatMoney(s.companyPayables)} hint="Ödənilməli qalıq" tone="rose" />
        <StatCard label="Nağd ödənişlər (cəmi)" value={formatMoney(s.cashReceived)} tone="gold" />
        <StatCard label="Kart ödənişləri (cəmi)" value={formatMoney(s.cardReceived)} tone="sky" />
        <StatCard label="Köçürmə və digər (cəmi)" value={formatMoney(s.otherReceived ?? 0)} tone="navy" />
        <StatCard label="Bu gün" value={formatMoney(s.todayTotal)} hint={`Nağd ${formatMoney(s.todayCash)} · Kart ${formatMoney(s.todayCard)} · Digər ${formatMoney(s.todayOther ?? 0)}`} tone="amber" />
        <StatCard label="Bu ay" value={formatMoney(s.monthTotal)} hint={`Nağd ${formatMoney(s.monthCash)} · Kart ${formatMoney(s.monthCard)} · Digər ${formatMoney(s.monthOther ?? 0)}`} tone="emerald" />
      </section>

      <section className="mt-6 grid gap-6 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <div className="mb-4 flex items-center justify-between">
            <div>
              <h2 className="font-serif text-lg font-semibold text-navy-800">Son 14 gün</h2>
              <p className="text-xs text-slate-500">Günlük ödənişlər</p>
            </div>
            <Badge tone="navy">Bu ay: {formatMoney(s.monthTotal)}</Badge>
          </div>
          {daily.every((d) => d.value === 0) ? (
            <EmptyState title="Bu dövrdə ödəniş yoxdur" />
          ) : (
            <BarChart data={daily} label="Son 14 gün üzrə günlük ödənişlər" />
          )}
        </Card>

        <Card>
          <h2 className="font-serif text-lg font-semibold text-navy-800">Aylıq dinamika</h2>
          <p className="mb-4 text-xs text-slate-500">Son 6 ay</p>
          <BarChart data={monthly} label="Son 6 ay üzrə aylıq ödənişlər" />
        </Card>
      </section>

      <section className="mt-6 grid gap-6 xl:grid-cols-2">
        <Card>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="font-serif text-lg font-semibold text-navy-800">Son əməliyyatlar</h2>
            <Link href="/odenisler" className="text-sm font-medium text-gold-600 hover:text-gold-500">Hamısına bax →</Link>
          </div>
          {data.recentTransactions.length === 0 ? (
            <EmptyState title="Hələ ödəniş qeydə alınmayıb" />
          ) : (
            <Table>
              <thead className="bg-slate-50/70">
                <tr><Th>Tarix</Th><Th>Ad</Th><Th>Üsul</Th><Th className="text-right">Məbləğ</Th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.recentTransactions.map((p) => (
                  <tr key={p.id} className="transition hover:bg-slate-50/60">
                    <Td>{formatDateTime(p.paidAt)}</Td>
                    <Td className="font-medium text-navy-800">{p.partyName}</Td>
                    <Td><Badge tone={METHOD_TONE[p.paymentMethod]}>{METHOD_LABEL[p.paymentMethod]}</Badge></Td>
                    <Td className="text-right font-medium tabular-nums">{formatMoney(p.amount)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <Card>
          <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Açıq balanslar</h2>
          {data.outstanding.length === 0 ? (
            <EmptyState title="Açıq balans yoxdur" text="Bütün borclar ödənilib." />
          ) : (
            <Table>
              <thead className="bg-slate-50/70">
                <tr><Th>Ad</Th><Th>Bölmə</Th><Th>Son tarix</Th><Th className="text-right">Qalıq</Th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.outstanding.map((o) => (
                  <tr key={o.id} className="transition hover:bg-slate-50/60">
                    <Td className="font-medium text-navy-800">{o.partyName}</Td>
                    <Td className="text-slate-500">{DEBT_TYPE_LABEL[o.debtType]}</Td>
                    <Td>{o.dueDate ? <Badge tone={o.overdue ? "rose" : "slate"}>{formatDate(o.dueDate)}</Badge> : "—"}</Td>
                    <Td className="text-right font-semibold tabular-nums">{formatMoney(o.remaining)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
          <p className="mt-4 text-xs text-slate-400">Status: {Object.values(DEBT_STATUS_LABEL).join(" · ")}</p>
        </Card>
      </section>
    </>
  );
}
