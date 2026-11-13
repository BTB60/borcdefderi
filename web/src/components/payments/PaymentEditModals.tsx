"use client";

import { useState, type FormEvent } from "react";
import { Alert, Button, Field, Input, Modal } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { METHOD_LABEL, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import type { Payment, PaymentMethod } from "@/lib/types";

const METHODS: PaymentMethod[] = ["CASH", "CARD", "TRANSFER", "OTHER"];

/** Baku calendar day (YYYY-MM-DD) of an ISO timestamp. */
function bakuDay(iso: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Baku", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(iso));
}

/**
 * Store owner only: corrects a payment. The server moves the debt balance by the difference and books the cash desk
 * movement back and again; the payment keeps its id and receipt link.
 * {@code maxAmount} (remaining debt + this payment) is shown when the caller knows it; the server checks it anyway.
 */
export function PaymentEditModal({ payment, maxAmount, onClose, onSaved }: { payment: Payment; maxAmount?: number; onClose: () => void; onSaved: (p: Payment) => void }) {
  const originalDay = bakuDay(payment.paidAt);
  const [form, setForm] = useState({
    amount: String(payment.amount),
    paymentMethod: payment.paymentMethod,
    day: originalDay,
    description: payment.description ?? "",
    note: payment.note ?? "",
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const amountNumber = Number(form.amount);
  const exceeds = maxAmount !== undefined && amountNumber > maxAmount + 1e-9;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const saved = await api<Payment>(`/api/payments/${payment.id}`, {
        method: "PUT",
        json: {
          amount: form.amount,
          paymentMethod: form.paymentMethod,
          // same day → keep the original time; another day → noon of that day in Baku
          paidAt: form.day === originalDay ? payment.paidAt : `${form.day}T12:00:00+04:00`,
          description: form.description || null,
          note: form.note || null,
        },
      });
      onSaved(saved);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Ödəniş dəyişdirilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title="Ödənişi düzəlt"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Bağla</Button><Button type="submit" form="payment-edit-form" disabled={busy || exceeds || !(amountNumber > 0)}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button></>}
    >
      <div className="mb-5 rounded-2xl bg-slate-50 px-5 py-3 text-sm text-slate-600 ring-1 ring-slate-200">
        <p className="font-medium text-navy-800">{payment.partyName}</p>
        <p>{formatDateTime(payment.paidAt)} · {METHOD_LABEL[payment.paymentMethod]} · {formatMoney(payment.amount)}</p>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="payment-edit-form" onSubmit={submit} className="space-y-4">
        <Field label="Məbləğ (₼)" error={exceeds ? `Borcun qalığından (${formatMoney(maxAmount ?? 0)}) çox ola bilməz` : undefined}>
          <Input required type="number" min="0.01" step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
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
        <Field label="Tarix">
          <Input required type="date" max={todayBaku()} value={form.day} onChange={(e) => setForm({ ...form, day: e.target.value })} />
        </Field>
        <Field label="Təsvir">
          <Input maxLength={255} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </Field>
        <Field label="Qeyd">
          <Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </Field>
        <p className="text-xs text-slate-500">Borcun qalığı və kassa avtomatik düzəldilir. Dəyişiklik tarixçədə və audit jurnalında qalır.</p>
      </form>
    </Modal>
  );
}

/** Store owner only: cancels (ləğv) a payment. It stays in the history as cancelled; debt and cash desk are restored. */
export function PaymentCancelModal({ payment, onClose, onSaved }: { payment: Payment; onClose: () => void; onSaved: (p: Payment) => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(await api<Payment>(`/api/payments/${payment.id}/cancel`, { method: "POST", json: { reason: reason || null } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Ödəniş ləğv edilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title="Ödənişi ləğv et"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Bağla</Button><Button type="submit" form="payment-cancel-form" variant="danger" disabled={busy}>{busy ? "Ləğv edilir…" : "Ödənişi ləğv et"}</Button></>}
    >
      <div className="mb-5 rounded-2xl bg-rose-50 px-5 py-4 text-center ring-1 ring-rose-600/20">
        <p className="text-sm text-rose-700">{payment.partyName} · {METHOD_LABEL[payment.paymentMethod]} · {formatDateTime(payment.paidAt)}</p>
        <p className="mt-1 font-serif text-3xl font-semibold text-rose-700">{formatMoney(payment.amount)}</p>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="payment-cancel-form" onSubmit={submit} className="space-y-4">
        <Field label="Səbəb">
          <Input maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Məs: səhv məbləğ yazılıb" />
        </Field>
        <p className="text-xs text-slate-500">
          Ödəniş silinmir — “Ləğv edildi” kimi tarixçədə qalır və cəmlərə daxil olmur. Borc bu məbləğ qədər geri artır,
          kassaya əks əməliyyat (bu günün tarixi ilə) yazılır. Bu əməliyyat geri qaytarılmır.
        </p>
      </form>
    </Modal>
  );
}
