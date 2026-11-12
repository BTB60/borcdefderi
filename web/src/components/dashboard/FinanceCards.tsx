"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Alert, Spinner } from "@/components/ui";
import { DebtShortcuts } from "@/components/dashboard/DebtShortcuts";
import { ApiError, api } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { DashboardListKind, DashboardOverview } from "@/lib/types";

const ICONS = {
  wallet: "M3 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1h1a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Zm13 5h3",
  paid: "M4 7h16v10H4V7Zm0 3h16M8 14h3",
  active: "M12 8v4l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z",
  done: "M5 12l4 4L19 6",
  users: "M16 11a4 4 0 1 0-8 0M3 21a9 9 0 0 1 18 0",
  building: "M4 21V5l8-3 8 3v16M9 21v-5h6v5M8 8h.01M12 8h.01M16 8h.01M8 12h.01M12 12h.01M16 12h.01",
  today: "M7 3v3M17 3v3M4 8h16M5 5h14v15H5V5Zm4 7h2v2H9v-2Z",
  alert: "M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z",
  in: "M12 5v14m0 0-6-6m6 6 6-6",
} as const;

function Icon({ d, className = "h-6 w-6" }: { d: string; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

/** Big coloured card: the whole card opens its list; an optional action button sits on top. */
function BigCard({ href, gradient, label, value, hint, icon, action }: { href: string; gradient: string; label: string; value: string; hint: string; icon: string; action?: ReactNode }) {
  return (
    <div className={`group relative overflow-hidden rounded-3xl bg-gradient-to-br ${gradient} p-5 text-white shadow-lg transition-all duration-300 hover:-translate-y-0.5 hover:shadow-xl`}>
      <Link href={href} className="absolute inset-0 z-0" aria-label={`${label} — siyahını aç`} />
      <div className="pointer-events-none absolute -right-6 -top-6 h-28 w-28 rounded-full bg-white/10 transition-transform duration-500 group-hover:scale-125" />
      <div className="pointer-events-none relative flex items-start justify-between">
        <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-white/20"><Icon d={icon} /></span>
        {action && <div className="pointer-events-auto relative z-10">{action}</div>}
      </div>
      <p className="pointer-events-none relative mt-4 text-sm font-medium text-white/80">{label}</p>
      <p className="pointer-events-none relative mt-1 font-serif text-3xl font-semibold tabular-nums">{value}</p>
      <p className="pointer-events-none relative mt-1 text-xs text-white/70">{hint}</p>
    </div>
  );
}

function SmallCard({ href, label, value, hint, icon, tone, action }: { href: string; label: string; value: string; hint: string; icon: string; tone: string; action?: ReactNode }) {
  return (
    <div className="group relative flex items-center gap-4 rounded-2xl border border-slate-200/70 bg-white p-4 shadow-card transition-all hover:-translate-y-0.5 hover:shadow-lg">
      <Link href={href} className="absolute inset-0 z-0 rounded-2xl" aria-label={`${label} — siyahını aç`} />
      <span className={`pointer-events-none relative flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl ${tone}`}><Icon d={icon} className="h-5 w-5" /></span>
      <span className="pointer-events-none relative min-w-0 flex-1">
        <span className="block text-xs font-medium uppercase tracking-wider text-slate-500">{label}</span>
        <span className="block truncate font-serif text-xl font-semibold tabular-nums text-navy-800">{value}</span>
        <span className="block text-xs text-slate-400">{hint}</span>
      </span>
      {action && <div className="relative z-10 shrink-0">{action}</div>}
    </div>
  );
}

const listHref = (k: DashboardListKind) => `/dashboard/siyahi/${k}`;

/** HesabPro 2.0 cards. Every figure comes from /api/dashboard/overview and equals the total of its list. */
export function FinanceCards() {
  const [o, setO] = useState<DashboardOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    api<DashboardOverview>("/api/dashboard/overview")
      .then(setO)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : "Kartlar yüklənə bilmədi"));
  }, []);
  useEffect(load, [load]);

  if (error) return <div className="mb-6"><Alert>{error}</Alert></div>;
  if (!o) return <Spinner />;

  const saved = (msg: string) => {
    setNotice(msg);
    load();
  };

  return (
    <section className="mb-8 space-y-5" aria-label="Maliyyə kartları">
      {notice && <Alert kind="success">{notice}</Alert>}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <BigCard
          href={listHref("company-payables")}
          gradient="from-violet-600 to-purple-800"
          label="Toplam borcum"
          value={formatMoney(o.companyPayables.sum)}
          hint={`${o.companyPayables.count} şirkətə açıq öhdəlik`}
          icon={ICONS.wallet}
          action={<DebtShortcuts section="payable" tone="onColor" onSaved={saved} />}
        />
        <BigCard href={listHref("payments-out")} gradient="from-teal-500 to-cyan-700" label="Ödədiyim" value={formatMoney(o.paymentsOut.sum)} hint={`${o.paymentsOut.count} ödəniş etmişəm`} icon={ICONS.paid} />
        <BigCard href={listHref("active-debts")} gradient="from-orange-400 to-orange-600" label="Aktiv borclar" value={String(o.activeDebts.count)} hint={`Qalıq: ${formatMoney(o.activeDebts.sum)}`} icon={ICONS.active} />
        <BigCard href={listHref("closed-debts")} gradient="from-emerald-500 to-green-700" label="Bitmiş borc" value={String(o.closedDebts.count)} hint={`Cəmi bağlanmış: ${formatMoney(o.closedDebts.sum)}`} icon={ICONS.done} />
      </div>

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <SmallCard href={listHref("customer-debts")} label="Müştəri borcu" value={formatMoney(o.customerDebts.sum)} hint={`${o.customerDebts.count} borclu müştəri`} icon={ICONS.users} tone="bg-navy-50 text-navy-800"
          action={<DebtShortcuts section="customer" onSaved={saved} />} />
        <SmallCard href={listHref("company-receivables")} label="Şirkətlər bizə borcludur" value={formatMoney(o.companyReceivables.sum)} hint={`${o.companyReceivables.count} şirkət`} icon={ICONS.building} tone="bg-sky-50 text-sky-700"
          action={<DebtShortcuts section="receivable" onSaved={saved} />} />
        <SmallCard href={listHref("today-debts")} label="Bu gün yazılan borclar" value={formatMoney(o.todayDebts.sum)} hint={`${o.todayDebts.count} əməliyyat`} icon={ICONS.today} tone="bg-violet-50 text-violet-700" />
        <SmallCard href={listHref("overdue-debts")} label="Gecikmiş borclar" value={String(o.overdueDebts.count)} hint={`Qalıq: ${formatMoney(o.overdueDebts.sum)}`} icon={ICONS.alert} tone="bg-rose-50 text-rose-700" />
        <SmallCard href={listHref("payments-in")} label="Alınan ödənişlər" value={formatMoney(o.paymentsIn.sum)} hint={`${o.paymentsIn.count} ödəniş qəbul edilib`} icon={ICONS.in} tone="bg-emerald-50 text-emerald-700" />
      </div>
    </section>
  );
}
