"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Alert, Button, Field, Input, Modal, Spinner } from "@/components/ui";
import { WhatsAppIcon } from "@/components/debts/DebtModals";
import { ApiError, api, download } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { METHOD_LABEL, formatMoney, todayBaku } from "@/lib/format";
import type { DebtType, Payment, PaymentMethod, PartyProfile } from "@/lib/types";
import { paymentConfirmationText, waLink } from "@/lib/whatsapp";

const METHODS: PaymentMethod[] = ["CASH", "CARD", "TRANSFER", "OTHER"];
const PERM: Record<DebtType, "CUSTOMER_DEBTS" | "COMPANY_RECEIVABLES" | "COMPANY_PAYABLES"> = {
  CUSTOMER_DEBT: "CUSTOMER_DEBTS",
  COMPANY_RECEIVABLE: "COMPANY_RECEIVABLES",
  COMPANY_PAYABLE: "COMPANY_PAYABLES",
};
export const DIRECTION_LABEL: Record<DebtType, string> = {
  CUSTOMER_DEBT: "Müştəri borcu",
  COMPANY_RECEIVABLE: "Şirkətin bizə borcu",
  COMPANY_PAYABLE: "Bizim şirkətə borcumuz",
};
const MONEY = /^\d+(\.\d{1,2})?$/;

type Mode = "charge" | "pay";
type Props = { mode: Mode; isCustomer: boolean; partyId: string; initialType?: DebtType; onClose: () => void; onSaved: (message: string) => void };

/** Remaining balance per section for this party, loaded fresh when the dialog opens. */
function useBalances(isCustomer: boolean, partyId: string) {
  const [profile, setProfile] = useState<PartyProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api<PartyProfile>(`/api/${isCustomer ? "customers" : "companies"}/${partyId}/profile`)
      .then(setProfile)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Məlumat yüklənmədi"));
  }, [isCustomer, partyId]);
  const balances = useMemo(() => {
    const out: Partial<Record<DebtType, number>> = {};
    for (const d of profile?.debts ?? []) out[d.debtType] = Math.round(((out[d.debtType] ?? 0) + d.remainingAmount) * 100) / 100;
    return out;
  }, [profile]);
  return { profile, balances, error };
}

/** "Borc əlavə et" and "Borc ödə" on the customer / company account. */
export function AccountModal({ mode, isCustomer, partyId, initialType, onClose, onSaved }: Props) {
  const { can, me } = useAuth();
  const { profile, balances, error: loadError } = useBalances(isCustomer, partyId);
  const candidates: DebtType[] = isCustomer ? ["CUSTOMER_DEBT"] : ["COMPANY_RECEIVABLE", "COMPANY_PAYABLE"];
  const types = candidates.filter((t) => can(PERM[t]));
  const [type, setType] = useState<DebtType | null>(initialType ?? null);
  const current: DebtType | undefined = type ?? (mode === "pay" ? types.find((t) => (balances[t] ?? 0) > 0) : undefined) ?? types[0];
  const balance = current ? balances[current] ?? 0 : 0;

  const [form, setForm] = useState({ amount: "", date: todayBaku(), dueDate: "", receiptNumber: "", note: "", method: "CASH" as PaymentMethod });
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [paid, setPaid] = useState<{ payments: Payment[]; remainingAfter: number } | null>(null);

  const value = Number(form.amount);
  const valid = MONEY.test(form.amount.trim()) && value > 0;
  const exceeds = mode === "pay" && value > balance + 1e-9;
  const name = profile?.party.name ?? "";

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!current || !valid || exceeds || busy) return;
    setBusy(true);
    setError(null);
    setErrors({});
    const base = `/api/${isCustomer ? "customers" : "companies"}/${partyId}`;
    try {
      if (mode === "charge") {
        const res = await api<{ remainingAfter: number }>(`${base}/charge`, {
          method: "POST",
          json: {
            debtType: current,
            amount: form.amount.trim(),
            date: form.date || null,
            receiptNumber: form.receiptNumber.trim() || null,
            note: form.note.trim() || null,
            dueDate: form.dueDate || null,
            expectedRemaining: balance.toFixed(2),
          },
        });
        onSaved(`Borc əlavə edildi. ${name} — qalıq borc: ${formatMoney(res.remainingAfter)}`);
      } else {
        const res = await api<{ payments: Payment[]; remainingAfter: number }>(`${base}/pay`, {
          method: "POST",
          json: {
            debtType: current,
            amount: form.amount.trim(),
            paymentMethod: form.method,
            description: form.note.trim() || null,
            expectedRemaining: balance.toFixed(2),
          },
        });
        setPaid(res);
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrors(err.fields ?? {});
      } else setError("Əməliyyat alınmadı");
    } finally {
      setBusy(false);
    }
  }

  if (paid) {
    const total = paid.payments.reduce((s, p) => s + p.amount, 0);
    const last = paid.payments[paid.payments.length - 1];
    const canNotify = current !== "COMPANY_PAYABLE";
    const wa = canNotify ? waLink(profile?.party.phone, paymentConfirmationText(me?.storeName ?? "", name, total, paid.remainingAfter, last?.receiptUrl)) : null;
    const done = () => onSaved(`Ödəniş qeydə alındı: ${formatMoney(total)}. Qalıq borc: ${formatMoney(paid.remainingAfter)}`);
    return (
      <Modal open title="Ödəniş qeydə alındı" onClose={done} footer={<Button variant="primary" onClick={done}>Bağla</Button>}>
        <div className="mb-5 rounded-2xl bg-emerald-50 px-5 py-4 text-center ring-1 ring-emerald-600/20">
          <p className="text-sm text-emerald-700">{name} · {METHOD_LABEL[form.method]}</p>
          <p className="mt-1 font-serif text-3xl font-semibold text-emerald-700">{formatMoney(total)}</p>
          <p className="mt-1 text-sm text-slate-600">{paid.remainingAfter > 0 ? `Qalıq borc: ${formatMoney(paid.remainingAfter)}` : "Borc tam ödənildi"}</p>
        </div>
        {error && <div className="mb-4"><Alert>{error}</Alert></div>}
        <div className="grid gap-2 sm:grid-cols-2">
          {last && (
            <Button variant="outline" onClick={() => download(`/api/payments/${last.id}/receipt`, "cek.pdf").catch((e) => setError(e instanceof ApiError ? e.message : "Çek yüklənmədi"))}>
              Çeki yüklə (PDF)
            </Button>
          )}
          {wa ? (
            <a href={wa} target="_blank" rel="noopener noreferrer" className="inline-flex items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-emerald-500">
              <WhatsAppIcon /> WhatsApp-da mesajı aç
            </a>
          ) : (
            <p className="self-center text-center text-xs text-slate-400">{canNotify ? "WhatsApp üçün telefon nömrəsi yoxdur" : ""}</p>
          )}
        </div>
        {wa && <p className="mt-3 text-xs text-slate-400">WhatsApp yalnız hazır mətnlə açılır — mesajı WhatsApp-da özünüz göndərirsiniz.</p>}
      </Modal>
    );
  }

  const title = `${mode === "charge" ? "Borc əlavə et" : "Borc ödə"}${name ? ` — ${name}` : ""}`;
  return (
    <Modal
      open
      title={title}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Ləğv et</Button>
          <Button type="submit" form="account-form" variant={mode === "charge" ? "gold" : "primary"} disabled={busy || !profile || !current || !valid || exceeds || (mode === "pay" && balance <= 0)}>
            {busy ? "Saxlanılır…" : mode === "charge" ? "Borcu əlavə et" : "Ödənişi qeydə al"}
          </Button>
        </>
      }
    >
      {loadError && <Alert>{loadError}</Alert>}
      {!profile && !loadError && <Spinner />}
      {profile && (
        <>
          {types.length === 0 && <Alert>Bu əməliyyat üçün icazəniz yoxdur.</Alert>}
          {!isCustomer && types.length > 0 && (
            <div className="mb-4 grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="İstiqamət">
              {types.map((t) => (
                <button
                  type="button"
                  key={t}
                  role="radio"
                  aria-checked={current === t}
                  onClick={() => setType(t)}
                  className={`rounded-xl border px-3 py-2.5 text-left text-sm transition-all ${current === t ? "border-gold-500 bg-gold-500/10 shadow-glow" : "border-slate-300 hover:border-slate-400"}`}
                >
                  <span className="block font-medium text-navy-800">{DIRECTION_LABEL[t]}</span>
                  <span className="text-xs text-slate-500">Qalıq: {formatMoney(balances[t] ?? 0)}</span>
                </button>
              ))}
            </div>
          )}
          <div className="mb-5 grid grid-cols-2 gap-3 rounded-2xl bg-navy-800 px-5 py-4 text-white">
            <div>
              <p className="text-xs text-white/50">İndiki qalıq</p>
              <p className="font-serif text-xl font-semibold tabular-nums text-gold-400">{formatMoney(balance)}</p>
            </div>
            <div className="text-right">
              <p className="text-xs text-white/50">{mode === "charge" ? "Əlavədən sonra" : "Ödənişdən sonra"}</p>
              <p className="font-serif text-xl font-semibold tabular-nums">
                {formatMoney(Math.max(0, balance + (valid ? (mode === "charge" ? value : -value) : 0)))}
              </p>
            </div>
          </div>
          {mode === "pay" && balance <= 0 && <div className="mb-4"><Alert kind="info">Bu hesab üzrə ödəniləcək borc yoxdur.</Alert></div>}
          {error && <div className="mb-4"><Alert>{error}</Alert></div>}
          <form id="account-form" onSubmit={submit} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Məbləğ (₼)" error={exceeds ? "Qalıq borcdan çox ola bilməz" : errors.amount}>
                <Input required autoFocus type="number" min="0.01" step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
              </Field>
              {mode === "charge" ? (
                <Field label="Tarix" error={errors.date}>
                  <Input required type="date" max={todayBaku()} value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} />
                </Field>
              ) : (
                <Field label="Sürətli seçim">
                  <div className="flex gap-2 pt-1">
                    <button type="button" className="rounded-full bg-slate-100 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-200" onClick={() => setForm({ ...form, amount: balance.toFixed(2) })}>Tam ödəniş</button>
                    <button type="button" className="rounded-full bg-slate-100 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-200" onClick={() => setForm({ ...form, amount: (Math.round(balance * 50) / 100).toFixed(2) })}>Yarısı</button>
                  </div>
                </Field>
              )}
            </div>
            {mode === "pay" && (
              <Field label="Ödəniş üsulu">
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" role="radiogroup" aria-label="Ödəniş üsulu">
                  {METHODS.map((m) => (
                    <button
                      type="button"
                      key={m}
                      role="radio"
                      aria-checked={form.method === m}
                      onClick={() => setForm({ ...form, method: m })}
                      className={`rounded-xl border px-3 py-2.5 text-sm font-medium transition-all ${form.method === m ? "border-gold-500 bg-gold-500/10 text-navy-800 shadow-glow" : "border-slate-300 text-slate-600 hover:border-slate-400"}`}
                    >
                      {METHOD_LABEL[m]}
                    </button>
                  ))}
                </div>
              </Field>
            )}
            {mode === "charge" && (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Çek nömrəsi" hint="İstəyə bağlı" error={errors.receiptNumber}>
                  <Input maxLength={60} value={form.receiptNumber} onChange={(e) => setForm({ ...form, receiptNumber: e.target.value })} />
                </Field>
                <Field label="Son ödəmə tarixi" hint="İstəyə bağlı — keçəndə gecikmiş sayılır" error={errors.dueDate}>
                  <Input type="date" min={form.date} value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
                </Field>
              </div>
            )}
            <Field label="Qeyd" hint={mode === "charge" ? "Məs: 2 kisə un" : "İstəyə bağlı"} error={errors.note ?? errors.description}>
              <Input maxLength={255} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
            </Field>
          </form>
        </>
      )}
    </Modal>
  );
}

/** Pick a customer or company first (dashboard "+ ƏLAVƏ ET" and quick actions), then the account dialog opens. */
export function PickPartyModal({ isCustomer, title, onPick, onClose }: { isCustomer: boolean; title: string; onPick: (id: string) => void; onClose: () => void }) {
  const [q, setQ] = useState("");
  const [items, setItems] = useState<{ id: string; name: string; phone: string | null; code?: string | null; openBalance: number }[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const t = setTimeout(() => {
      api<{ id: string; name: string; phone: string | null; code?: string | null; openBalance: number }[]>(
        `/api/${isCustomer ? "customers" : "companies"}${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ""}`,
      )
        .then(setItems)
        .catch((e) => setError(e instanceof ApiError ? e.message : "Siyahı yüklənmədi"));
    }, 250);
    return () => clearTimeout(t);
  }, [q, isCustomer]);
  return (
    <Modal open title={title} onClose={onClose}>
      <Input autoFocus placeholder={isCustomer ? "Ad, telefon və ya müştəri kodu" : "Şirkət adı və ya telefon"} value={q} onChange={(e) => setQ(e.target.value)} aria-label="Axtarış" />
      {error && <div className="mt-3"><Alert>{error}</Alert></div>}
      {!items && !error && <Spinner />}
      {items && items.length === 0 && <p className="mt-4 text-sm text-slate-500">Tapılmadı. Əvvəlcə “Müştəri və şirkətlər” bölməsində əlavə edin.</p>}
      <ul className="mt-3 max-h-80 divide-y divide-slate-100 overflow-y-auto">
        {items?.map((p) => (
          <li key={p.id}>
            <button type="button" onClick={() => onPick(p.id)} className="flex w-full items-center justify-between gap-3 rounded-lg px-2 py-2.5 text-left hover:bg-slate-50">
              <span>
                <span className="block font-medium text-navy-800">{p.name}</span>
                <span className="text-xs text-slate-500">{[p.code, p.phone].filter(Boolean).join(" · ") || "—"}</span>
              </span>
              <span className="text-sm tabular-nums text-slate-600">{formatMoney(p.openBalance)}</span>
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}
