"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Button, EmptyState, Field, Input, Modal, Select, Spinner, useConfirm } from "@/components/ui";
import { ApiError, api, canShareFiles, download, shareProtectedFile } from "@/lib/api";
import { DEBT_TYPE_LABEL, METHOD_LABEL, formatDate, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import { useAuth } from "@/lib/auth";
import { PaymentCancelModal, PaymentEditModal } from "@/components/payments/PaymentEditModals";
import type { Debt, DebtType, LedgerEntry, Party, Payment, PaymentMethod } from "@/lib/types";
import { paymentConfirmationText, waLink } from "@/lib/whatsapp";

const TODAY = todayBaku;
const METHODS: PaymentMethod[] = ["CASH", "CARD", "TRANSFER", "OTHER"];

export function WhatsAppIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor" aria-hidden="true">
      <path d="M12 2a10 10 0 0 0-8.6 15.1L2 22l5-1.3A10 10 0 1 0 12 2Zm0 18.2a8.2 8.2 0 0 1-4.2-1.2l-.3-.2-3 .8.8-2.9-.2-.3A8.2 8.2 0 1 1 12 20.2Zm4.5-6.1c-.2-.1-1.5-.7-1.7-.8-.2-.1-.4-.1-.6.1l-.8 1c-.1.2-.3.2-.5.1a6.7 6.7 0 0 1-3.3-2.9c-.2-.4.2-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.6-.4h-.5a1 1 0 0 0-.7.3 3 3 0 0 0-.9 2.2 5.2 5.2 0 0 0 1.1 2.7 11.8 11.8 0 0 0 4.5 4c1.7.7 2.3.8 3.2.6.5-.1 1.5-.6 1.7-1.2.2-.6.2-1.1.2-1.2l-.5-.2Z" />
    </svg>
  );
}

export function DebtFormModal({ type, initialPartyId = "", onClose, onSaved }: { type: DebtType; initialPartyId?: string; onClose: () => void; onSaved: () => void }) {
  const isCustomer = type === "CUSTOMER_DEBT";
  const [parties, setParties] = useState<Party[] | null>(null);
  const [form, setForm] = useState({ partyId: initialPartyId, amount: "", debtDate: TODAY(), dueDate: "", description: "", note: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Party[]>(isCustomer ? "/api/customers" : "/api/companies").then(setParties).catch(() => setParties([]));
  }, [isCustomer]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setErrors({});
    try {
      // Adds to the party's account: an existing balance grows instead of a separate new debt appearing.
      await api(`/api/${isCustomer ? "customers" : "companies"}/${form.partyId}/charge`, {
        method: "POST",
        json: {
          debtType: type,
          amount: form.amount,
          date: form.debtDate,
          receiptNumber: form.note || null,
          note: form.description || null,
        },
      });
      onSaved();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrors(err.fields ?? {});
      } else setError("Əməliyyat alınmadı");
    } finally {
      setBusy(false);
    }
  }

  const footer = (
    <>
      <Button variant="ghost" onClick={onClose}>Ləğv et</Button>
      <Button type="submit" form="debt-form" disabled={busy || !parties?.length}>{busy ? "Saxlanılır…" : "Saxla"}</Button>
    </>
  );

  return (
    <Modal open title={`Borc əlavə et — ${DEBT_TYPE_LABEL[type]}`} onClose={onClose} footer={footer}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {parties && parties.length === 0 ? (
        <EmptyState title={`Əvvəlcə ${isCustomer ? "müştəri" : "şirkət"} əlavə edin`} text="Müştəri və şirkət bölməsindən qeyd yaradın." />
      ) : (
        <form id="debt-form" onSubmit={submit} className="space-y-4">
          <Field label={isCustomer ? "Müştəri" : "Şirkət"} error={errors.partyId}>
            <Select required value={form.partyId} onChange={(e) => setForm({ ...form, partyId: e.target.value })}>
              <option value="">Seçin…</option>
              {parties?.map((p) => <option key={p.id} value={p.id}>{p.name}{p.phone ? ` · ${p.phone}` : ""}</option>)}
            </Select>
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Məbləğ (₼)" error={errors.amount}>
              <Input required type="number" min="0.01" step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
            </Field>
            <Field label="Borc tarixi" error={errors.debtDate}>
              <Input required type="date" value={form.debtDate} onChange={(e) => setForm({ ...form, debtDate: e.target.value })} />
            </Field>
          </div>
          <Field label="Çek nömrəsi" hint="İstəyə bağlı">
            <Input maxLength={60} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          </Field>
          <Field label="Qeyd">
            <Input maxLength={255} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Məs: 2 kisə un" />
          </Field>
          <p className="text-xs text-slate-500">Müştərinin artıq borcu varsa, məbləğ onun ümumi borcunun üstünə gəlir.</p>
        </form>
      )}
    </Modal>
  );
}

/** "Borc artır": the party took more on credit; kept as its own history entry. */
export function DebtIncreaseModal({ debt, onClose, onSaved }: { debt: Debt; onClose: () => void; onSaved: (updated: Debt) => void }) {
  const [form, setForm] = useState({ amount: "", date: TODAY(), receiptNumber: "", note: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const add = Number(form.amount);
  const valid = add > 0 && /^\d+(\.\d{1,2})?$/.test(form.amount.trim());

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    setErrors({});
    try {
      const updated = await api<Debt>(`/api/debts/${debt.id}/increase`, {
        method: "POST",
        json: {
          amount: form.amount.trim(),
          date: form.date || null,
          receiptNumber: form.receiptNumber.trim() || null,
          note: form.note.trim() || null,
          version: debt.version,
        },
      });
      onSaved(updated);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.code === "VERSION_CONFLICT" ? "Bu borc artıq dəyişdirilib. Pəncərəni bağlayıb səhifəni yeniləyin." : err.message);
        setErrors(err.fields ?? {});
      } else setError("Əməliyyat alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Borc artır — ${debt.partyName}`}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="debt-increase" disabled={busy || !valid}>{busy ? "Saxlanılır…" : "Borcu artır"}</Button></>}
    >
      <div className="mb-5 grid grid-cols-2 gap-3 rounded-2xl bg-navy-800 px-5 py-4 text-white">
        <div>
          <p className="text-xs text-white/50">İndiki qalıq</p>
          <p className="font-serif text-xl font-semibold tabular-nums text-gold-400">{formatMoney(debt.remainingAmount)}</p>
        </div>
        <div className="text-right">
          <p className="text-xs text-white/50">Artımdan sonra</p>
          <p className="font-serif text-xl font-semibold tabular-nums">{formatMoney(debt.remainingAmount + (valid ? add : 0))}</p>
        </div>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="debt-increase" onSubmit={submit} className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Əlavə məbləğ (₼)" error={errors.amount}>
            <Input required autoFocus type="number" min="0.01" step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
          </Field>
          <Field label="Tarix" error={errors.date}>
            <Input required type="date" max={TODAY()} value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} />
          </Field>
        </div>
        <Field label="Çek nömrəsi" hint="İstəyə bağlı" error={errors.receiptNumber}>
          <Input maxLength={60} value={form.receiptNumber} onChange={(e) => setForm({ ...form, receiptNumber: e.target.value })} />
        </Field>
        <Field label="Qeyd" hint="Məs: 2 kisə un" error={errors.note}>
          <Input maxLength={500} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </Field>
        <p className="text-xs text-slate-500">Artım tarixçədə ayrıca sətir kimi qalır; əvvəlki məlumatlar silinmir.</p>
      </form>
    </Modal>
  );
}

export function DebtEditModal({ debt, onClose, onSaved }: { debt: Debt; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({
    amount: String(debt.amount),
    debtDate: debt.debtDate,
    dueDate: debt.dueDate ?? "",
    description: debt.description ?? "",
    note: debt.note ?? "",
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api(`/api/debts/${debt.id}`, {
        method: "PUT",
        json: { ...form, dueDate: form.dueDate || null, description: form.description || null, note: form.note || null, version: debt.version },
      });
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Yadda saxlanılmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Redaktə — ${debt.partyName}`}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="debt-edit" disabled={busy}>Yadda saxla</Button></>}
    >
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="debt-edit" onSubmit={submit} className="space-y-4">
        <p className="rounded-xl bg-slate-50 px-4 py-3 text-xs text-slate-500">
          Artıq ödənilmiş məbləğ: <strong className="text-navy-800">{formatMoney(debt.paidAmount)}</strong>. Borc məbləği bundan az ola bilməz.
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Məbləğ (₼)">
            <Input required type="number" min="0.01" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
          </Field>
          <Field label="Borc tarixi">
            <Input required type="date" value={form.debtDate} onChange={(e) => setForm({ ...form, debtDate: e.target.value })} />
          </Field>
        </div>
        <Field label="Son ödəmə tarixi">
          <Input type="date" value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
        </Field>
        <Field label="Təsvir">
          <Input maxLength={255} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </Field>
        <Field label="Qeyd">
          <Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </Field>
      </form>
    </Modal>
  );
}

export function PaymentModal({ debt, storeName, canNotify, onClose, onSaved }: { debt: Debt; storeName: string; canNotify: boolean; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ amount: String(debt.remainingAmount), paymentMethod: "CASH" as PaymentMethod, description: "", note: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<Payment | null>(null);
  const amountNumber = Number(form.amount);
  const exceeds = amountNumber > debt.remainingAmount + 1e-9;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setDone(await api<Payment>(`/api/debts/${debt.id}/payments`, { method: "POST", json: { ...form, description: form.description || null, note: form.note || null } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Ödəniş qeydə alınmadı");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    const remaining = done.remainingAfter ?? 0;
    const text = paymentConfirmationText(storeName, debt.partyName, done.amount, remaining, done.receiptUrl);
    const wa = canNotify ? waLink(debt.partyPhone, text) : null;
    const shareFile = async () => {
      setError(null);
      try {
        const shared = await shareProtectedFile(`/api/payments/${done.id}/receipt`, `cek-${done.id.slice(0, 8)}.pdf`, text);
        if (!shared) setError("Bu brauzer faylı paylaşa bilmir — “WhatsApp ilə çek göndər” düyməsindən istifadə edin");
      } catch (e) {
        setError(e instanceof ApiError ? e.message : "Çek paylaşılmadı");
      }
    };
    return (
      <Modal open title="Ödəniş qeydə alındı" onClose={onSaved} footer={<Button variant="primary" onClick={onSaved}>Bağla</Button>}>
        <div className="mb-5 rounded-2xl bg-emerald-50 px-5 py-4 text-center ring-1 ring-emerald-600/20">
          <p className="text-sm text-emerald-700">{debt.partyName} · {METHOD_LABEL[done.paymentMethod]}</p>
          <p className="mt-1 font-serif text-3xl font-semibold text-emerald-700">{formatMoney(done.amount)}</p>
          <p className="mt-1 text-sm text-slate-600">{remaining > 0 ? `Qalıq borc: ${formatMoney(remaining)}` : "Borc tam ödənildi"}</p>
        </div>
        {error && <div className="mb-4"><Alert>{error}</Alert></div>}
        <div className="grid gap-2 sm:grid-cols-2">
          <Button variant="outline" onClick={() => download(`/api/payments/${done.id}/receipt`, "cek.pdf").catch((e) => setError(e instanceof ApiError ? e.message : "Çek yüklənmədi"))}>
            Çeki yüklə (PDF)
          </Button>
          {wa ? (
            <a href={wa} target="_blank" rel="noopener noreferrer" className="inline-flex items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-emerald-500">
              <WhatsAppIcon /> WhatsApp ilə çek göndər
            </a>
          ) : (
            <p className="self-center text-center text-xs text-slate-400">{canNotify ? "WhatsApp üçün telefon nömrəsi yoxdur" : ""}</p>
          )}
          {canNotify && canShareFiles() && (
            <Button variant="outline" className="sm:col-span-2" onClick={shareFile}>PDF faylı paylaş (WhatsApp və s.)</Button>
          )}
        </div>
        {wa && <p className="mt-3 text-xs text-slate-400">Mesajda müştərinin çeki açması üçün təhlükəsiz link olur — müştəri basanda çek PDF kimi açılır.</p>}
      </Modal>
    );
  }

  return (
    <Modal
      open
      title="Ödəniş qəbulu"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="payment-form" disabled={busy || exceeds || !(amountNumber > 0)}>{busy ? "Saxlanılır…" : "Ödənişi qeydə al"}</Button></>}
    >
      <div className="mb-5 flex items-center justify-between rounded-2xl bg-navy-800 px-5 py-4 text-white">
        <div>
          <p className="text-xs text-white/60">{debt.partyName}</p>
          <p className="text-xs text-white/50">Qalıq borc</p>
        </div>
        <p className="font-serif text-2xl font-semibold text-gold-400">{formatMoney(debt.remainingAmount)}</p>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="payment-form" onSubmit={submit} className="space-y-4">
        <Field label="Məbləğ (₼)" error={exceeds ? "Qalıq borcdan çox ola bilməz" : undefined}>
          <Input required type="number" min="0.01" max={debt.remainingAmount} step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
        </Field>
        <Field label="Ödəniş üsulu">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" role="radiogroup" aria-label="Ödəniş üsulu">
            {METHODS.map((m) => (
              <button
                type="button"
                key={m}
                role="radio"
                aria-checked={form.paymentMethod === m}
                onClick={() => setForm({ ...form, paymentMethod: m })}
                className={`rounded-xl border px-3 py-2.5 text-sm font-medium transition-all ${form.paymentMethod === m ? "border-gold-500 bg-gold-500/10 text-navy-800 shadow-glow" : "border-slate-300 text-slate-600 hover:border-slate-400"}`}
              >
                {METHOD_LABEL[m]}
              </button>
            ))}
          </div>
        </Field>
        <Field label="Təsvir">
          <Input maxLength={255} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder={form.paymentMethod === "TRANSFER" ? "Məs: M10 / bank əməliyyat nömrəsi" : "Məs: Qismən ödəniş"} />
        </Field>
        <Field label="Qeyd">
          <Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </Field>
        <div className="flex flex-wrap gap-2 pt-1">
          <span className="text-xs text-slate-500">Sürətli seçim:</span>
          <button type="button" className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-200" onClick={() => setForm({ ...form, amount: String(debt.remainingAmount) })}>Tam ödəniş</button>
          <button type="button" className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-200" onClick={() => setForm({ ...form, amount: String(Math.round(debt.remainingAmount / 2 * 100) / 100) })}>Yarısı</button>
        </div>
      </form>
    </Modal>
  );
}

export function HistoryModal({ debt, onClose, onChanged }: { debt: Debt; onClose: () => void; onChanged?: () => void }) {
  const { isOwner } = useAuth();
  const [entries, setEntries] = useState<LedgerEntry[] | null>(null);
  const [payments, setPayments] = useState<Record<string, Payment>>({});
  const [remaining, setRemaining] = useState(debt.remainingAmount);
  const [action, setAction] = useState<{ kind: "edit" | "cancel"; payment: Payment } | null>(null);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  async function cancelIncrease(e: LedgerEntry) {
    const ok = await confirm(
      `${formatMoney(e.amount)} borc artımı ləğv edilsin? Borc bu məbləğ qədər azalacaq (artımdan əvvəlki vəziyyətə qayıdacaq). Əməliyyat tarixçədə “ləğv edildi” kimi qalır.`,
      { title: "Borc artımını ləğv et", confirmLabel: "Ləğv et", danger: true },
    );
    if (!ok) return;
    try {
      const updated = await api<Debt>(`/api/debts/${debt.id}/history/${e.id}/cancel`, { method: "POST", json: {} });
      setRemaining(updated.remainingAmount);
      setEntries(null);
      setReload((n) => n + 1);
      onChanged?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Əməliyyat ləğv edilmədi");
    }
  }
  useEffect(() => {
    api<LedgerEntry[]>(`/api/debts/${debt.id}/history`).then(setEntries).catch((e) => setError(e instanceof ApiError ? e.message : "Tarixçə yüklənmədi"));
    if (isOwner) {
      api<Payment[]>(`/api/payments?debtId=${debt.id}&limit=500&includeVoided=true`)
        .then((list) => setPayments(Object.fromEntries(list.map((p) => [p.id, p]))))
        .catch(() => setPayments({}));
    }
  }, [debt.id, isOwner, reload]);

  // A payment entry is still in effect when no REVERSAL of the same payment follows it.
  const live = new Set<string>();
  entries?.forEach((e, i) => {
    if (e.paymentId && e.type.startsWith("PAYMENT") && !entries.slice(i + 1).some((x) => x.type === "REVERSAL" && x.paymentId === e.paymentId)) {
      live.add(e.id);
    }
  });

  function changed(p: Payment) {
    setAction(null);
    if (p.remainingAfter != null) setRemaining(p.remainingAfter);
    setEntries(null);
    setReload((n) => n + 1);
    onChanged?.();
  }

  if (action) {
    return action.kind === "edit" ? (
      <PaymentEditModal payment={action.payment} maxAmount={remaining + action.payment.amount} onClose={() => setAction(null)} onSaved={changed} />
    ) : (
      <PaymentCancelModal payment={action.payment} onClose={() => setAction(null)} onSaved={changed} />
    );
  }

  const labels: Record<LedgerEntry["type"], string> = {
    DEBT_CREATED: "Borc yaradıldı",
    DEBT_ADJUSTED: "Məbləğ dəyişdirildi",
    DEBT_INCREASED: "Borc artırıldı",
    PAYMENT_CASH: "Nağd ödəniş",
    PAYMENT_CARD: "Kart ödənişi",
    PAYMENT_TRANSFER: "Köçürmə ilə ödəniş",
    PAYMENT_OTHER: "Digər ödəniş",
    REVERSAL: "Ödəniş ləğvi",
  };

  return (
    <Modal open title={`Tarixçə — ${debt.partyName}`} onClose={onClose}>
      {confirmDialog}
      {error && <Alert>{error}</Alert>}
      {!entries && !error && <Spinner />}
      {entries && (
        <ol className="relative space-y-5 border-l-2 border-slate-200 pl-6">
          {entries.map((e) => (
            <li key={e.id} className="relative">
              <span className={`absolute -left-[31px] top-1 h-3.5 w-3.5 rounded-full ring-4 ring-white ${e.type.startsWith("PAYMENT") ? "bg-emerald-500" : "bg-navy-800"}`} />
              <p className="text-sm font-medium text-navy-800">
                {e.reversesEntryId ? "Borc artımı ləğv edildi" : labels[e.type]}
                {e.cancelled && <span className="ml-2 rounded-full bg-rose-50 px-2 py-0.5 text-[11px] font-medium text-rose-700">ləğv edilib</span>}
              </p>
              <p className="text-xs text-slate-500">{formatDateTime(e.createdAt)} · {e.actorName}</p>
              {(e.entryDate || e.receiptNumber || e.note) && (
                <p className="text-xs text-slate-500">
                  {[e.entryDate && `Tarix: ${formatDate(e.entryDate)}`, e.receiptNumber && `Çek: ${e.receiptNumber}`, e.note].filter(Boolean).join(" · ")}
                </p>
              )}
              <p className="mt-1 text-sm">
                <span className={`font-semibold tabular-nums ${e.type.startsWith("PAYMENT") ? "text-emerald-700" : "text-rose-700"}`}>{e.type === "DEBT_INCREASED" ? "+" : ""}{formatMoney(e.amount)}</span>
                <span className="ml-2 text-xs text-slate-500">qalıq: {formatMoney(e.balanceAfter)}</span>
              </p>
              {isOwner && e.type === "DEBT_INCREASED" && !e.cancelled && (
                <button className="mt-1 text-xs font-medium text-rose-600 hover:text-rose-500" onClick={() => void cancelIncrease(e)}>
                  Ləğv et
                </button>
              )}
              {e.paymentId && e.type !== "REVERSAL" && (
                <div className="mt-1 flex flex-wrap gap-3">
                  <button
                    className="text-xs font-medium text-gold-600 hover:text-gold-500"
                    onClick={() => download(`/api/payments/${e.paymentId}/receipt`, "cek.pdf").catch((err) => setError(err instanceof ApiError ? err.message : "Çek yüklənmədi"))}
                  >
                    Çek (PDF) →
                  </button>
                  {isOwner && live.has(e.id) && payments[e.paymentId] && !payments[e.paymentId].voidedAt && (
                    <>
                      <button className="text-xs font-medium text-navy-700 hover:text-navy-500" onClick={() => setAction({ kind: "edit", payment: payments[e.paymentId!] })}>
                        Düzəlt
                      </button>
                      <button className="text-xs font-medium text-rose-600 hover:text-rose-500" onClick={() => setAction({ kind: "cancel", payment: payments[e.paymentId!] })}>
                        Ləğv et
                      </button>
                    </>
                  )}
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}
