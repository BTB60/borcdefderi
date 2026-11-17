"use client";

import { useEffect, useState } from "react";
import { Card, Spinner, cx } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { TodayPanel } from "@/components/admin/TodayPanel";

export type AdminOverview = {
  stores: { total: number; active: number; expired: number; neverPaid: number; suspended: number; new7: number; new30: number; expiring: number };
  pendingRequests: number;
  monthRevenue: number;
  totalRevenue: number;
  monthlyRecurring: number;
  signups: { date: string; count: number }[];
  revenueByMonth: { month: string; amount: number }[];
  planMix: { name: string; stores: number }[];
};

const MONTHS = ["Yan", "Fev", "Mar", "Apr", "May", "İyn", "İyl", "Avq", "Sen", "Okt", "Noy", "Dek"];

function Kpi({ label, value, hint, tone, onClick }: { label: string; value: string; hint?: string; tone: "navy" | "emerald" | "rose" | "amber" | "gold" | "sky"; onClick?: () => void }) {
  const tones = {
    navy: "from-navy-800 to-navy-600 text-white",
    emerald: "from-emerald-600 to-emerald-500 text-white",
    rose: "from-rose-600 to-rose-500 text-white",
    amber: "from-amber-500 to-amber-400 text-navy-900",
    gold: "from-gold-500 to-gold-400 text-navy-900",
    sky: "from-sky-600 to-sky-500 text-white",
  }[tone];
  const Tag = onClick ? "button" : "div";
  return (
    <Tag onClick={onClick} className={cx("rounded-2xl bg-gradient-to-br p-5 text-left shadow-card transition", tones, onClick && "hover:-translate-y-0.5 hover:shadow-lg")}>
      <p className="text-xs font-medium uppercase tracking-wider opacity-80">{label}</p>
      <p className="mt-2 font-serif text-3xl font-semibold tabular-nums">{value}</p>
      {hint && <p className="mt-1 text-xs opacity-80">{hint}</p>}
    </Tag>
  );
}

function Bars({ data, format, color }: { data: { label: string; value: number }[]; format: (v: number) => string; color: string }) {
  const max = Math.max(1, ...data.map((d) => d.value));
  return (
    <div className="flex h-44 gap-1.5">
      {data.map((d, i) => (
        <div key={i} className="group relative flex h-full flex-1 flex-col items-center" title={`${d.label}: ${format(d.value)}`}>
          <div className="relative flex w-full flex-1 items-end">
            <span className="pointer-events-none absolute -top-1 left-1/2 z-10 hidden -translate-x-1/2 -translate-y-full whitespace-nowrap rounded bg-navy-800 px-1.5 py-0.5 text-[10px] text-white group-hover:block">{format(d.value)}</span>
            <div className={cx("w-full rounded-t-md transition-all", color)} style={{ height: `${Math.max(d.value > 0 ? 4 : 1.5, (d.value / max) * 100)}%`, opacity: d.value > 0 ? 1 : 0.25 }} />
          </div>
          <span className="mt-1 h-3 text-[10px] text-slate-400">{d.label}</span>
        </div>
      ))}
    </div>
  );
}

/** Platform dashboard: the numbers that matter at a glance, with links to the lists behind them. */
export function OverviewTab({ onError, go }: { onError: (m: string | null) => void; go: (tab: "stores" | "expiring" | "subscriptions" | "plans") => void }) {
  const [data, setData] = useState<AdminOverview | null>(null);
  useEffect(() => {
    api<AdminOverview>("/api/admin/overview").then(setData).catch((e) => onError(e instanceof ApiError ? e.message : "İcmal yüklənmədi"));
  }, [onError]);
  if (!data) return <Spinner />;
  const s = data.stores;
  const signups = data.signups.map((d, i) => ({ label: i % 5 === 4 ? d.date.slice(8, 10) : "", value: d.count }));
  const revenue = data.revenueByMonth.map((m) => ({ label: MONTHS[Number(m.month.slice(5, 7)) - 1], value: m.amount }));
  const mixTotal = Math.max(1, data.planMix.reduce((a, p) => a + p.stores, 0));
  const MIX_COLORS = ["bg-navy-800", "bg-gold-500", "bg-emerald-500", "bg-sky-500", "bg-rose-500", "bg-violet-500"];

  return (
    <div className="space-y-6">
      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi tone="navy" label="Mağazalar" value={String(s.total)} hint={`${s.new7} yeni (7 gün) · ${s.new30} (30 gün)`} onClick={() => go("stores")} />
        <Kpi tone="emerald" label="Aktiv abunəlik" value={String(s.active)} hint={`${s.total ? Math.round((s.active / s.total) * 100) : 0}% mağaza ödəyir`} onClick={() => go("stores")} />
        <Kpi tone="gold" label="Bu ayın gəliri" value={formatMoney(data.monthRevenue)} hint={`Aylıq təkrarlanan: ~${formatMoney(data.monthlyRecurring)}`} />
        <Kpi tone={data.pendingRequests > 0 ? "amber" : "sky"} label="Gözləyən ödəniş" value={String(data.pendingRequests)} hint={data.pendingRequests > 0 ? "Təsdiq gözləyir — baxın" : "Hamısı baxılıb"} onClick={() => go("subscriptions")} />
      </section>

      <TodayPanel pending={data.pendingRequests} go={go} />

      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {[
          { label: "Az qalıb", value: s.expiring, tone: "text-amber-600", tab: "expiring" as const, hint: "Abunəliyi bitmək üzrə" },
          { label: "Vaxtı bitib", value: s.expired, tone: "text-rose-600", tab: "expiring" as const, hint: "Yeniləməli" },
          { label: "Heç ödəməyib", value: s.neverPaid, tone: "text-slate-700", tab: "stores" as const, hint: "Qeydiyyatdan keçib, abunə yoxdur" },
          { label: "Dayandırılıb", value: s.suspended, tone: "text-slate-700", tab: "stores" as const, hint: "Admin tərəfindən" },
        ].map((k) => (
          <button key={k.label} onClick={() => go(k.tab)} className="rounded-2xl bg-white p-4 text-left shadow-card ring-1 ring-slate-200/70 transition hover:ring-gold-500">
            <p className="text-xs font-medium text-slate-500">{k.label}</p>
            <p className={cx("mt-1 font-serif text-2xl font-semibold tabular-nums", k.tone)}>{k.value}</p>
            <p className="text-xs text-slate-400">{k.hint}</p>
          </button>
        ))}
      </section>

      <section className="grid gap-6 xl:grid-cols-2">
        <Card>
          <div className="mb-4 flex items-baseline justify-between">
            <h2 className="font-serif text-lg font-semibold text-navy-800">Yeni qeydiyyatlar</h2>
            <span className="text-xs text-slate-400">son 30 gün · {data.signups.reduce((a, d) => a + d.count, 0)} mağaza</span>
          </div>
          <Bars data={signups} format={(v) => `${v} mağaza`} color="bg-navy-700" />
        </Card>
        <Card>
          <div className="mb-4 flex items-baseline justify-between">
            <h2 className="font-serif text-lg font-semibold text-navy-800">Gəlir</h2>
            <span className="text-xs text-slate-400">son 6 ay · cəmi {formatMoney(data.totalRevenue)}</span>
          </div>
          <Bars data={revenue} format={formatMoney} color="bg-gold-500" />
        </Card>
      </section>

      <Card>
        <div className="mb-4 flex items-baseline justify-between">
          <h2 className="font-serif text-lg font-semibold text-navy-800">Aktiv mağazalar paketlər üzrə</h2>
          <button onClick={() => go("plans")} className="text-xs font-medium text-gold-600 hover:underline">Paketləri idarə et →</button>
        </div>
        {data.planMix.length === 0 ? <p className="text-sm text-slate-500">Hələ aktiv abunəlik yoxdur.</p> : (
          <>
            <div className="flex h-4 overflow-hidden rounded-full bg-slate-100">
              {data.planMix.map((p, i) => <div key={p.name} className={MIX_COLORS[i % MIX_COLORS.length]} style={{ width: `${(p.stores / mixTotal) * 100}%` }} title={`${p.name}: ${p.stores}`} />)}
            </div>
            <ul className="mt-3 flex flex-wrap gap-x-6 gap-y-2 text-sm">
              {data.planMix.map((p, i) => (
                <li key={p.name} className="flex items-center gap-2">
                  <span className={cx("h-3 w-3 rounded-full", MIX_COLORS[i % MIX_COLORS.length])} />
                  <span className="text-navy-800">{p.name}</span>
                  <span className="tabular-nums text-slate-500">{p.stores} · {Math.round((p.stores / mixTotal) * 100)}%</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </Card>
    </div>
  );
}
