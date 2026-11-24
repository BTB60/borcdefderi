"use client";

import { useState, type FormEvent } from "react";
import { Alert, Button, Modal } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatMoney } from "@/lib/format";

export type PhoneMatch = {
  cardId: string; customerId: string; number: string; fullName: string; phone: string; percent: number; balance: number; active: boolean; smsOn: boolean;
  discountPercent?: number;
};

/** "Kart yoxdur?": the customer left the card at home or the phone is dead — find the bonus card by phone number. */
export function BonusByPhoneModal({ onClose, onPick }: { onClose: () => void; onPick: (m: PhoneMatch) => void }) {
  const [phone, setPhone] = useState("");
  const [found, setFound] = useState<PhoneMatch[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function search(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null); setFound(null);
    try {
      setFound(await api<PhoneMatch[]>(`/api/loyalty-cards/lookup?phone=${encodeURIComponent(phone)}`));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Axtarış alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title="🎫 Bonus kartı — telefon nömrəsi ilə" onClose={onClose}
      footer={<Button variant="ghost" onClick={onClose}>Bağla</Button>}>
      <p className="mb-3 text-sm text-slate-600">Müştərinin kartı yanında deyil və ya telefonu sönüb? Telefon nömrəsini yazın — kartı tapılacaq.</p>
      <form onSubmit={search} className="flex gap-2">
        <input autoFocus inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="050 123 45 67"
          className="h-11 flex-1 rounded-xl border border-slate-200 px-3 text-lg tracking-wide outline-none focus:border-gold-500" />
        <Button type="submit" variant="gold" disabled={busy || phone.replace(/\D/g, "").length < 9}>{busy ? "…" : "Tap"}</Button>
      </form>
      {error && <div className="mt-3"><Alert>{error}</Alert></div>}
      {found && found.length === 0 && (
        <div className="mt-3"><Alert kind="info">Bu nömrədə bonus kartı yoxdur. “Bonus kartları” bölməsində yeni kart yarada bilərsiniz.</Alert></div>
      )}
      {found && found.length > 0 && (
        <div className="mt-4 space-y-2">
          {found.map((m) => (
            <div key={m.cardId} className={`flex items-center gap-3 rounded-2xl p-3 ring-1 ${m.active ? "bg-violet-50 ring-violet-200" : "bg-slate-50 ring-slate-200 opacity-70"}`}>
              <div className="min-w-0 flex-1">
                <p className="truncate font-semibold text-navy-800">{m.fullName}</p>
                <p className="text-xs text-slate-500">{m.number} · {m.phone} · {Number(m.percent)}%</p>
                <p className="text-sm font-semibold text-emerald-700">Bonus: {formatMoney(m.balance)}</p>
              </div>
              {m.active
                ? <Button onClick={() => onPick(m)}>Bu müştəridir</Button>
                : <span className="text-xs font-semibold text-rose-600">Kart bloklanıb</span>}
            </div>
          ))}
          <p className="text-xs text-slate-500">
            {found[0].smsOn
              ? "Bonus yığmaq üçün kod lazım deyil. Bonusla ödəmək üçün müştərinin telefonuna SMS kod göndəriləcək."
              : "SMS qoşulmayıb: bonus kodsuz istifadə olunacaq və bu, kassirin adı ilə jurnala yazılır."}
          </p>
        </div>
      )}
    </Modal>
  );
}

/** Paying with the bonus of a card found by phone: a 4-digit SMS code from the customer unlocks it. */
export function BonusCodeGate({ cardId, onUnlocked }: { cardId: string; onUnlocked: () => void }) {
  const [sent, setSent] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function send() {
    setBusy(true); setError(null);
    try {
      const r = await api<{ sent: boolean; smsOn: boolean; phone?: string }>(`/api/loyalty-cards/${cardId}/bonus-code`, { method: "POST", json: {} });
      if (!r.smsOn) onUnlocked();
      else setSent(r.phone ?? "");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Kod göndərilmədi");
    } finally {
      setBusy(false);
    }
  }

  async function verify() {
    setBusy(true); setError(null);
    try {
      await api(`/api/loyalty-cards/${cardId}/bonus-code/verify`, { method: "POST", json: { code } });
      onUnlocked();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Kod səhvdir");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="text-right">
      {sent == null ? (
        <button type="button" disabled={busy} onClick={send}
          className="rounded-md bg-white px-2 py-1 text-xs font-semibold text-violet-700 ring-1 ring-violet-200 disabled:opacity-50">
          {busy ? "…" : "📩 Bonus üçün SMS kod"}
        </button>
      ) : (
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-slate-500">{sent} nömrəsinə</span>
          <input value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 4))} inputMode="numeric" placeholder="kod" aria-label="SMS kodu"
            className="h-8 w-16 rounded-lg border border-slate-200 bg-white px-2 text-center text-sm tracking-widest outline-none focus:border-gold-500" />
          <button type="button" disabled={busy || code.length !== 4} onClick={verify}
            className="rounded-md bg-violet-600 px-2 py-1 text-xs font-semibold text-white disabled:opacity-50">OK</button>
          <button type="button" disabled={busy} onClick={send} className="text-xs text-slate-500 underline">yenə</button>
        </div>
      )}
      {error && <p className="mt-1 text-xs text-rose-600">{error}</p>}
    </div>
  );
}
