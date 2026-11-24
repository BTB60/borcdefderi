"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Field, Input, Modal } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, formatMoney } from "@/lib/format";
import type { Contact } from "@/lib/types";

export type CreditRequest = {
  id: string; storeId: string; storeName: string; quantity: number; amount: number; status: "PENDING" | "APPROVED" | "REJECTED" | "GRANT";
  paymentMethod: string | null; payerReference: string | null; note: string | null; hasReceipt: boolean; rejectReason: string | null;
  requestedAt: string; reviewedAt: string | null;
};
export type CreditBalance = { credits: number; pricePerCard: number; packages: number[]; minQuantity: number; pending: CreditRequest | null; history: CreditRequest[] };

export const CREDIT_STATUS: Record<CreditRequest["status"], [string, "amber" | "emerald" | "rose" | "sky"]> = {
  PENDING: ["Təsdiq gözləyir", "amber"], APPROVED: ["Təsdiqləndi", "emerald"], REJECTED: ["Rədd edildi", "rose"], GRANT: ["Admin əlavə etdi", "sky"],
};

/** The store's card balance line on Bonus kartları: how many paid cards are left and "Kart paketi al". */
export function CardCreditBar({ balance, isOwner, onBuy }: { balance: CreditBalance | null; isOwner: boolean; onBuy: () => void }) {
  if (!balance) return null;
  const empty = balance.credits <= 0;
  return (
    <div className={`mb-4 flex flex-wrap items-center gap-3 rounded-2xl px-4 py-3 ring-1 ${empty ? "bg-rose-50 ring-rose-200" : "bg-violet-50 ring-violet-200"}`}>
      <div className="flex-1">
        <p className={`font-semibold ${empty ? "text-rose-700" : "text-violet-800"}`}>
          Kart balansı: {balance.credits} kart
        </p>
        <p className="text-xs text-slate-600">
          Hər yeni bonus kartı balansdan 1 kart istifadə edir · 1 kart = {formatMoney(balance.pricePerCard)}
          {balance.pending && <> · <b>{balance.pending.quantity} kartlıq sorğunuz təsdiq gözləyir</b></>}
        </p>
      </div>
      {isOwner && <Button variant="gold" onClick={onBuy}>{balance.pending ? "Sorğunu yenilə" : "Kart paketi al"}</Button>}
    </div>
  );
}

/** Buy a card package: choose the size, pay the platform (M10 / card), send "Ödəniş etdim" with the receipt. */
export function BuyCardsModal({ balance, onClose, onSent }: { balance: CreditBalance; onClose: () => void; onSent: (r: CreditRequest) => void }) {
  const [qty, setQty] = useState(balance.pending?.quantity ?? balance.packages[1] ?? 50);
  const [method, setMethod] = useState<"M10" | "CARD">("M10");
  const [ref, setRef] = useState("");
  const [note, setNote] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [contact, setContact] = useState<Contact | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api<Contact>("/api/public/contact").then((c) => { setContact(c); if (!c.m10Account && c.cardNumber) setMethod("CARD"); }).catch(() => undefined);
  }, []);
  const amount = Math.round(qty * balance.pricePerCard * 100) / 100;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const fd = new FormData();
      fd.append("quantity", String(qty));
      fd.append("paymentMethod", method);
      if (ref.trim()) fd.append("payerReference", ref.trim());
      if (note.trim()) fd.append("note", note.trim());
      if (file) fd.append("receipt", file);
      onSent(await api<CreditRequest>("/api/card-credits/requests", { method: "POST", body: fd }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Göndərilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title="Bonus kart paketi al" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="buy-cards" variant="gold" disabled={busy || qty < balance.minQuantity}>{busy ? "Göndərilir…" : "Ödəniş etdim"}</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <form id="buy-cards" onSubmit={submit} className="space-y-4">
        <div>
          <p className="mb-2 text-sm font-medium text-slate-700">Paket</p>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
            {balance.packages.map((p) => (
              <button key={p} type="button" onClick={() => setQty(p)}
                className={`rounded-2xl border px-2 py-3 text-center transition ${qty === p ? "border-violet-600 bg-violet-50 ring-2 ring-violet-200" : "border-slate-200 hover:border-violet-300"}`}>
                <span className="block text-lg font-bold text-navy-800">{p}</span>
                <span className="block text-xs text-slate-500">{formatMoney(p * balance.pricePerCard)}</span>
              </button>
            ))}
          </div>
          <div className="mt-3 flex items-end gap-3">
            <Field label="Və ya istənilən say">
              <Input type="number" min={balance.minQuantity} max={10000} value={qty} onChange={(e) => setQty(Math.max(0, Math.floor(Number(e.target.value) || 0)))} />
            </Field>
            <div className="pb-2 text-right">
              <p className="text-xs text-slate-500">Ödəniləcək</p>
              <p className="text-2xl font-black text-violet-700">{formatMoney(amount)}</p>
            </div>
          </div>
          {qty < balance.minQuantity && <p className="mt-1 text-xs text-rose-600">Ən az {balance.minQuantity} kart</p>}
        </div>

        <div className="rounded-2xl bg-slate-50 p-4 text-sm">
          <div className="mb-3 flex gap-2">
            {contact?.m10Account && <MethodButton on={method === "M10"} onClick={() => setMethod("M10")}>M10</MethodButton>}
            {contact?.cardNumber && <MethodButton on={method === "CARD"} onClick={() => setMethod("CARD")}>Bank kartı</MethodButton>}
          </div>
          {!contact ? <p className="text-slate-500">Ödəniş məlumatı yüklənir…</p> : method === "M10" && contact.m10Account ? (
            <p>M10 nömrəsi: <b className="font-mono">{contact.m10Account}</b>{contact.m10Recipient && <> · {contact.m10Recipient}</>}{contact.paymentNote && <span className="mt-1 block text-slate-600">{contact.paymentNote}</span>}</p>
          ) : contact.cardNumber ? (
            <p>Kart: <b className="font-mono">{contact.cardNumber}</b>{contact.cardHolder && <> · {contact.cardHolder}</>}{contact.cardBank && <> · {contact.cardBank}</>}{contact.cardNote && <span className="mt-1 block text-slate-600">{contact.cardNote}</span>}</p>
          ) : <p className="text-slate-500">Ödəniş məlumatı üçün administratorla əlaqə saxlayın{contact.phone ? `: ${contact.phone}` : ""}.</p>}
          <p className="mt-2 text-xs text-slate-500">{formatMoney(amount)} ödəyin, sonra aşağıda “Ödəniş etdim” basın. Admin təsdiqləyən kimi {qty} kart balansınıza əlavə olunur.</p>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Ödəniş nömrəsi / istinad (istəyə görə)"><Input value={ref} maxLength={120} onChange={(e) => setRef(e.target.value)} /></Field>
          <Field label="Qəbz (şəkil və ya PDF)"><input type="file" accept="image/png,image/jpeg,application/pdf" className="block w-full text-sm" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Field>
        </div>
        <Field label="Qeyd (istəyə görə)"><Input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} /></Field>
      </form>

      {balance.history.length > 0 && (
        <div className="mt-5 border-t border-slate-100 pt-4">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Əvvəlki sorğular</p>
          <div className="space-y-1.5 text-sm">
            {balance.history.slice(0, 6).map((r) => (
              <div key={r.id} className="flex flex-wrap items-center gap-2">
                <Badge tone={CREDIT_STATUS[r.status][1]}>{CREDIT_STATUS[r.status][0]}</Badge>
                <span className="font-medium">{r.quantity} kart</span>
                <span className="text-slate-500">{formatMoney(r.amount)} · {formatDateTime(r.requestedAt)}</span>
                {r.rejectReason && <span className="text-rose-600">— {r.rejectReason}</span>}
              </div>
            ))}
          </div>
        </div>
      )}
    </Modal>
  );
}

function MethodButton({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick}
      className={`rounded-xl px-3 py-1.5 text-sm font-semibold transition ${on ? "bg-navy-800 text-white" : "bg-white text-navy-800 ring-1 ring-slate-200"}`}>
      {children}
    </button>
  );
}
