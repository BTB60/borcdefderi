"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, PageHeader, Spinner, StatCard } from "@/components/ui";
import { Countdown, WARN_DAYS } from "@/components/Countdown";
import { PlanCard } from "@/components/PlanCard";
import { METHOD_TEXT, REQUEST_STATUS_LABEL, REQUEST_STATUS_TONE } from "@/components/admin/PaymentRequestsTab";
import { ApiError, api, openProtected } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { SUBSCRIPTION_STATUS_LABEL, formatDate, formatDateTime, formatMoney } from "@/lib/format";
import { PERIOD_LABEL } from "@/lib/plans";
import type { PeriodCode, Plan, SubscriptionRecord, SubscriptionSummary } from "@/lib/types";

export default function SubscriptionPage() {
  const router = useRouter();
  const { me, isOwner, refresh } = useAuth();
  const [summary, setSummary] = useState<SubscriptionSummary | null>(null);
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [history, setHistory] = useState<SubscriptionRecord[] | null>(null);
  const [periods, setPeriods] = useState<Record<string, PeriodCode>>({});
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    const [s, p, h] = await Promise.all([
      api<SubscriptionSummary>("/api/subscriptions/current"),
      api<Plan[]>("/api/public/plans"),
      api<SubscriptionRecord[]>("/api/subscriptions/history"),
    ]);
    setSummary(s);
    setPlans(p);
    setHistory(h);
  };

  useEffect(() => {
    load().catch((e) => setError(e instanceof ApiError ? e.message : "Məlumat yüklənmədi"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function checkStatus() {
    await Promise.all([load(), refresh()]).catch((e) => setError(e instanceof ApiError ? e.message : "Yenilənmədi"));
  }

  function choose(plan: Plan) {
    const period = periods[plan.id] ?? plan.options[0]?.period;
    router.push(`/abunelik/odenis?plan=${encodeURIComponent(plan.code)}&period=${period}`);
  }

  if (!summary || !plans || !history) {
    return error ? <Alert>{error}</Alert> : <Spinner />;
  }

  const pending = history.find((h) => h.status === "PENDING");

  return (
    <>
      <PageHeader
        title="Abunəlik"
        subtitle={`${me?.storeName ?? ""} — aktiv abunəlik, paketlər və ödəniş sorğuları`}
        actions={<>
          <Button variant="outline" onClick={checkStatus}>Statusu yenilə</Button>
          <a href="#paketler" className="rounded-xl bg-gold-500 px-4 py-2.5 text-sm font-semibold text-navy-900 transition hover:bg-gold-400">Abunəliyimi artır</a>
        </>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {pending && (
        <div className="mb-4">
          <Alert kind="info">
            <strong>{pending.planName}</strong> ({pending.periodCode ? PERIOD_LABEL[pending.periodCode] : "—"}, {formatMoney(pending.amount)}) üçün ödəniş sorğunuz yoxlanılır. Administrator təsdiqləyəndən sonra abunəlik avtomatik aktivləşəcək.{" "}
            <Link className="font-semibold underline" href={`/abunelik/odenis?plan=${encodeURIComponent(pending.planCode)}&period=${pending.periodCode ?? ""}`}>Ödəniş məlumatları</Link>
          </Alert>
        </div>
      )}

      <h2 className="mb-3 font-serif text-xl font-semibold text-navy-800">Aktiv abunəliyim</h2>
      <section className="mb-10 grid gap-4 sm:grid-cols-3">
        <StatCard label="Status" value={SUBSCRIPTION_STATUS_LABEL[summary.status] ?? summary.status} tone={summary.active ? "emerald" : "amber"} />
        <StatCard label="Paket" value={summary.planName ?? "—"} tone="navy" hint={summary.endDate ? `Bitmə tarixi: ${formatDate(summary.endDate)}` : undefined} />
        <div className={`rounded-2xl border border-l-4 p-5 shadow-card ${summary.active && summary.daysRemaining >= WARN_DAYS ? "border-slate-200/70 border-l-emerald-500 bg-white" : "border-rose-200 border-l-rose-500 bg-rose-50"}`}>
          <p className="text-sm text-slate-500">Qalan vaxt</p>
          <p className="mt-2 text-xl">
            <Countdown expiresAt={summary.expiresAt} emptyText="Aktiv abunəlik yoxdur" />
          </p>
        </div>
      </section>

      <h2 id="paketler" className="mb-1 scroll-mt-24 font-serif text-xl font-semibold text-navy-800">Abunəlik paketləri</h2>
      <p className="mb-4 text-sm text-slate-500">Paketi və müddəti seçin, sonra ödəniş məlumatlarını görəcəksiniz. Aktiv abunəlik varsa, yeni müddət cari müddətin sonuna əlavə olunur.</p>
      {plans.length === 0 ? (
        <Card><EmptyState title="Hazırda satışda paket yoxdur" text="Administratorla əlaqə saxlayın." /></Card>
      ) : (
        <div className="mb-8 grid gap-5 md:grid-cols-2 xl:grid-cols-3">
          {plans.map((p) => {
            const period = periods[p.id] ?? p.options[0]?.period ?? null;
            return (
              <PlanCard
                key={p.id}
                plan={p}
                period={period}
                selected={pending?.planCode === p.code}
                onPeriod={(v) => setPeriods((x) => ({ ...x, [p.id]: v }))}
                action={{
                  label: pending?.planCode === p.code ? "Sorğu göndərilib — bax" : "Paketi seç",
                  onClick: () => choose(p),
                  disabled: !isOwner,
                  title: !isOwner ? "Yalnız mağaza sahibi abunə ola bilər" : undefined,
                }}
              />
            );
          })}
        </div>
      )}
      {!isOwner && <div className="mb-8"><Alert kind="info">Abunəliyi yalnız mağaza sahibi ala bilər.</Alert></div>}

      <Card>
        <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Ödəniş sorğularım</h2>
        {history.length === 0 ? (
          <EmptyState title="Hələ sorğu yoxdur" />
        ) : (
          <ul className="grid gap-3 md:grid-cols-2">
            {history.map((h) => (
              <li key={h.id} className="rounded-xl border border-slate-100 p-4 transition hover:border-gold-500/40">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-medium text-navy-800">{h.planName}{h.periodCode ? ` · ${h.periodCode === "CUSTOM" ? `${h.periodDays} gün` : PERIOD_LABEL[h.periodCode]}` : ""}</p>
                  <Badge tone={REQUEST_STATUS_TONE[h.requestStatus]}>{REQUEST_STATUS_LABEL[h.requestStatus]}</Badge>
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  {formatDateTime(h.requestedAt)} · {formatMoney(h.amount)}{h.paymentMethod ? ` · ${METHOD_TEXT[h.paymentMethod]}` : ""}
                </p>
                {h.startDate && <p className="text-xs text-slate-500">Dövr: {formatDate(h.startDate)} – {formatDate(h.endDate)}{h.status === "EXPIRED" ? " (bitib)" : ""}</p>}
                {h.rejectReason && <p className="mt-1 text-xs text-rose-600">Səbəb: {h.rejectReason}</p>}
                {h.hasReceipt && (
                  <button onClick={() => openProtected(`/api/subscriptions/${h.id}/receipt`).catch((e) => setError(e.message))} className="mt-2 text-xs font-medium text-gold-600 hover:text-gold-500">
                    Çekə bax →
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <p className="mt-8 text-xs text-slate-400">Aktiv abunəlik olmadan mağazanın borc və ödəniş modulları bağlı qalır.</p>
    </>
  );
}
