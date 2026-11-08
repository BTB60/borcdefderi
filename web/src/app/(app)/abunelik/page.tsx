"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Spinner, StatCard } from "@/components/ui";
import { Countdown, WARN_DAYS } from "@/components/Countdown";
import { ApiError, api, openProtected } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { SUBSCRIPTION_STATUS_LABEL, formatDate, formatDateTime, formatMoney, planPeriodLabel } from "@/lib/format";
import type { Contact, Plan, SubscriptionRecord, SubscriptionSummary } from "@/lib/types";

const STATUS_TONE: Record<string, "emerald" | "amber" | "rose" | "slate"> = {
  ACTIVE: "emerald",
  PENDING: "amber",
  REJECTED: "rose",
  EXPIRED: "slate",
  NONE: "slate",
};

export default function SubscriptionPage() {
  const { me, isOwner, refresh } = useAuth();
  const [summary, setSummary] = useState<SubscriptionSummary | null>(null);
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [history, setHistory] = useState<SubscriptionRecord[] | null>(null);
  const [contact, setContact] = useState<Contact | null>(null);
  const [chosen, setChosen] = useState<Plan | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    const [s, p, h, c] = await Promise.all([
      api<SubscriptionSummary>("/api/subscriptions/current"),
      api<Plan[]>("/api/public/plans"),
      api<SubscriptionRecord[]>("/api/subscriptions/history"),
      api<Contact>("/api/public/contact"),
    ]);
    setSummary(s);
    setPlans(p);
    setHistory(h);
    setContact(c);
  };

  useEffect(() => {
    load().catch((e) => setError(e instanceof ApiError ? e.message : "Məlumat yüklənmədi"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function subscribe(plan: Plan) {
    setError(null);
    setChosen(plan);
  }

  async function checkStatus() {
    await Promise.all([load(), refresh()]).catch((e) => setError(e instanceof ApiError ? e.message : "Yenilənmədi"));
  }

  if (!summary || !plans || !history) {
    return error ? <Alert>{error}</Alert> : <Spinner />;
  }

  const pending = history.find((h) => h.status === "PENDING");
  const cheapestMonthly = plans.find((p) => p.durationMonths === 1)?.price;

  return (
    <>
      <PageHeader
        title="Abunəlik"
        subtitle={`${me?.storeName ?? ""} — abunəlik statusu və paketlər`}
        actions={<Button variant="outline" onClick={checkStatus}>Statusu yenilə</Button>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {pending && (
        <div className="mb-4">
          <Alert kind="info">
            <strong>{pending.planName}</strong> paketi üçün ödəniş təsdiq sorğunuz administratora göndərilib{pending.payerReference ? ` (istinad: ${pending.payerReference})` : ""}. Administrator ödənişi yoxlayıb təsdiqləyəndən sonra mağazanız avtomatik aktiv olacaq.{" "}
            <button className="font-semibold underline" onClick={() => setChosen(plans.find((p) => p.code === pending.planCode) ?? null)}>Ödəniş məlumatları</button>
          </Alert>
        </div>
      )}

      <section className="mb-8 grid gap-4 sm:grid-cols-3">
        <StatCard label="Status" value={SUBSCRIPTION_STATUS_LABEL[summary.status] ?? summary.status} tone={summary.active ? "emerald" : "amber"} />
        <StatCard label="Paket" value={summary.planName ?? "—"} tone="navy" hint={summary.endDate ? `Bitmə tarixi: ${formatDate(summary.endDate)}` : undefined} />
        <div className={`rounded-2xl border border-l-4 p-5 shadow-card ${summary.active && summary.daysRemaining >= WARN_DAYS ? "border-slate-200/70 border-l-emerald-500 bg-white" : "border-rose-200 border-l-rose-500 bg-rose-50"}`}>
          <p className="text-sm text-slate-500">Qalan vaxt</p>
          <p className="mt-2 text-xl">
            <Countdown expiresAt={summary.expiresAt} emptyText="Aktiv abunəlik yoxdur" />
          </p>
        </div>
      </section>

      <h2 className="mb-4 font-serif text-xl font-semibold text-navy-800">Paketlər</h2>
      <div className="mb-8 grid gap-4 md:grid-cols-3">
        {plans.map((p) => {
          const best = p.durationMonths === 12;
          const saving = cheapestMonthly && p.durationMonths > 1 ? Math.round((1 - p.price / (cheapestMonthly * p.durationMonths)) * 100) : 0;
          return (
            <div
              key={p.id}
              className={`relative flex flex-col overflow-hidden rounded-2xl border p-6 transition-all duration-300 hover:-translate-y-1 hover:shadow-lg ${best ? "border-gold-500 bg-navy-800 text-white shadow-2xl" : "border-slate-200 bg-white text-navy-800 shadow-card"}`}
            >
              {best && <span className="absolute right-3 top-3 rounded-full bg-gold-500 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-navy-900">Ən sərfəli</span>}
              <p className={`text-xs font-semibold uppercase tracking-widest ${best ? "text-gold-400" : "text-slate-500"}`}>{planPeriodLabel(p.durationMonths)}</p>
              <p className="mt-1 font-serif text-lg font-semibold">{p.name}</p>
              <p className="mt-4 font-serif text-4xl font-semibold">{formatMoney(p.price)}</p>
              <p className={`mt-1 text-sm ${best ? "text-white/60" : "text-slate-500"}`}>
                {p.durationMonths} ay · ayda {formatMoney(p.price / p.durationMonths)}
              </p>
              {saving > 0 && <p className={`mt-2 text-xs font-semibold ${best ? "text-gold-400" : "text-emerald-600"}`}>{saving}% qənaət</p>}
              <div className="mt-6 flex-1" />
              <Button
                variant={best ? "gold" : "primary"}
                className="w-full py-3"
                disabled={!isOwner}
                onClick={() => subscribe(p)}
                title={!isOwner ? "Yalnız mağaza sahibi abunə ola bilər" : undefined}
              >
                {pending?.planCode === p.code ? "Sorğu göndərilib" : "Abunə ol"}
              </Button>
            </div>
          );
        })}
      </div>
      {!isOwner && <div className="mb-8"><Alert kind="info">Abunəliyi yalnız mağaza sahibi ala bilər.</Alert></div>}

      <Card>
        <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Abunəlik tarixçəsi</h2>
        {history.length === 0 ? (
          <EmptyState title="Hələ sorğu yoxdur" />
        ) : (
          <ul className="grid gap-3 md:grid-cols-2">
            {history.map((h) => (
              <li key={h.id} className="rounded-xl border border-slate-100 p-4 transition hover:border-gold-500/40">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-medium text-navy-800">{h.planName}</p>
                  <Badge tone={STATUS_TONE[h.status] ?? "slate"}>{SUBSCRIPTION_STATUS_LABEL[h.status]}</Badge>
                </div>
                <p className="mt-1 text-xs text-slate-500">{formatDateTime(h.requestedAt)} · {formatMoney(h.amount)}</p>
                {h.startDate && <p className="text-xs text-slate-500">Dövr: {formatDate(h.startDate)} – {formatDate(h.endDate)}</p>}
                {h.rejectReason && <p className="mt-1 text-xs text-rose-600">Səbəb: {h.rejectReason}</p>}
                {h.hasReceipt && (
                  <button onClick={() => openProtected(`/api/subscriptions/${h.id}/receipt`).catch((e) => setError(e.message))} className="mt-2 text-xs font-medium text-gold-600 hover:text-gold-500">
                    Qəbzə bax →
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <p className="mt-8 text-xs text-slate-400">Aktiv abunəlik olmadan mağazanın borc və ödəniş modulları bağlı qalır.</p>

      {chosen && (
        <SubscribeModal
          plan={chosen}
          contact={contact}
          storeName={me?.storeName ?? ""}
          alreadySent={pending?.planCode === chosen.code}
          onClose={() => setChosen(null)}
          onSent={async () => {
            await load().catch(() => undefined);
          }}
        />
      )}
    </>
  );
}

const MAX_RECEIPT_BYTES = 5 * 1024 * 1024;

function SubscribeModal({ plan, contact, storeName, alreadySent, onClose, onSent }: {
  plan: Plan;
  contact: Contact | null;
  storeName: string;
  alreadySent: boolean;
  onClose: () => void;
  onSent: () => Promise<void>;
}) {
  const [copied, setCopied] = useState<string | null>(null);
  const [reference, setReference] = useState("");
  const [receipt, setReceipt] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const message = `Salam! "${storeName}" mağazası üçün ${plan.name} (${formatMoney(plan.price)}) abunəliyinin ödənişini etdim. Çeki göndərirəm.`;
  const phone = contact?.phone;

  async function copy(value: string, key: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(key);
    } catch {
      setCopied(null);
    }
  }

  async function send(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (receipt && receipt.size > MAX_RECEIPT_BYTES) {
      setError("Faylın ölçüsü 5 MB-dan çox ola bilməz");
      return;
    }
    const form = new FormData();
    form.append("planCode", plan.code);
    if (reference.trim()) form.append("payerReference", reference.trim());
    if (receipt) form.append("receipt", receipt);
    setBusy(true);
    try {
      await api("/api/subscriptions", { method: "POST", body: form });
      setSent(true);
      await onSent();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Sorğu göndərilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title={`Abunə ol — ${plan.name}`} onClose={onClose} footer={<Button variant="ghost" onClick={onClose}>Bağla</Button>}>
      <div className="mb-5 flex items-baseline justify-between gap-3 rounded-2xl bg-navy-800 px-5 py-4 text-white">
        <span className="font-serif text-lg font-semibold">{plan.name} · {plan.durationMonths} ay</span>
        <span className="font-serif text-2xl font-semibold text-gold-400">{formatMoney(plan.price)}</span>
      </div>

      <h3 className="mb-2 text-sm font-semibold text-navy-800">1. Ödənişi edin</h3>
      {contact?.m10Account ? (
        <div className="mb-4 rounded-xl border border-gold-500/40 bg-gold-500/5 px-4 py-3 text-sm">
          <p className="text-xs uppercase tracking-wider text-slate-500">M10 ilə ödəniş</p>
          <p className="mt-1 flex flex-wrap items-center gap-2">
            <span className="font-mono text-lg font-semibold text-navy-800">{contact.m10Account}</span>
            <button type="button" onClick={() => copy(contact.m10Account ?? "", "m10")} className="rounded-full bg-white px-2.5 py-0.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200 hover:bg-slate-50">
              {copied === "m10" ? "Kopyalandı ✓" : "Kopyala"}
            </button>
          </p>
          {contact.m10Recipient && <p className="text-slate-600">Alan: <strong>{contact.m10Recipient}</strong></p>}
          <p className="text-slate-600">Məbləğ: <strong>{formatMoney(plan.price)}</strong></p>
          {contact.paymentNote && <p className="mt-1 text-xs text-slate-500">{contact.paymentNote}</p>}
        </div>
      ) : (
        <div className="mb-4"><Alert kind="info">M10 ödəniş məlumatları hələ əlavə edilməyib. Ödəniş üçün administratorla əlaqə saxlayın.</Alert></div>
      )}
      {phone && (
        <div className="mb-5 grid grid-cols-1 gap-2 sm:grid-cols-3">
          <a href={`tel:${phone.replace(/[^+\d]/g, "")}`} className="rounded-xl bg-navy-800 px-4 py-2.5 text-center text-sm font-medium text-white transition hover:bg-navy-700">Zəng et · {phone}</a>
          {contact?.whatsapp && (
            <a href={`https://wa.me/${contact.whatsapp}?text=${encodeURIComponent(message)}`} target="_blank" rel="noopener noreferrer" className="rounded-xl bg-emerald-600 px-4 py-2.5 text-center text-sm font-medium text-white transition hover:bg-emerald-500">
              Çeki WhatsApp-a göndər
            </a>
          )}
          <button type="button" onClick={() => copy(phone, "phone")} className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 transition hover:border-slate-400">
            {copied === "phone" ? "Kopyalandı ✓" : "Nömrəni kopyala"}
          </button>
        </div>
      )}

      <h3 className="mb-2 text-sm font-semibold text-navy-800">2. Təsdiq sorğusu göndərin</h3>
      {sent ? (
        <Alert kind="success">Sorğunuz göndərildi. Administrator ödənişi yoxlayıb təsdiqləyəndən sonra mağazanız avtomatik aktiv olacaq və sayğac başlayacaq.</Alert>
      ) : (
        <form onSubmit={send} className="space-y-3">
          {alreadySent && <Alert kind="info">Bu paket üçün sorğunuz artıq var. Yenidən göndərsəniz, məlumatlar yenilənəcək.</Alert>}
          {error && <Alert>{error}</Alert>}
          <Field label="M10 / bank əməliyyat nömrəsi" hint="Ödəniş çekindəki əməliyyat nömrəsi (istəyə bağlı)">
            <Input maxLength={120} value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Məs: 123456789" />
          </Field>
          <Field label="Ödəniş çeki" hint="Şəkil və ya PDF · maksimum 5 MB (istəyə bağlı — WhatsApp ilə də göndərə bilərsiniz)">
            <input
              type="file"
              accept="image/png,image/jpeg,application/pdf"
              onChange={(e) => setReceipt(e.target.files?.[0] ?? null)}
              className="block w-full text-sm text-slate-600 file:mr-4 file:rounded-xl file:border-0 file:bg-navy-800 file:px-4 file:py-2.5 file:text-sm file:font-medium file:text-white hover:file:bg-navy-700"
            />
          </Field>
          <Button type="submit" variant="gold" className="w-full py-3" disabled={busy}>
            {busy ? "Göndərilir…" : "Ödəniş etdim — təsdiq sorğusu göndər"}
          </Button>
        </form>
      )}
      <p className="mt-3 text-xs text-slate-400">Ödəniş administrator tərəfindən təsdiqlənməyənə qədər mağaza aktivləşdirilmir.</p>
    </Modal>
  );
}
