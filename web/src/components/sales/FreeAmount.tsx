"use client";

import { useState } from "react";
import { Button, Modal } from "@/components/ui";
import { formatMoney } from "@/lib/format";

const QUICK = [1, 2, 5, 10];

/**
 * Sərbəst məbləğ: goods with no barcode (or not in the system yet) are sold by amount; the name is optional
 * and shows on the receipt.
 */
export function FreeAmount({ onDone, onClose }: { onDone: (name: string, amount: number, qty: number) => void; onClose: () => void }) {
  const [amount, setAmount] = useState("");
  const [name, setName] = useState("");
  const [qty, setQty] = useState("1");
  const value = Number(amount.replace(",", ".")) || 0;
  const count = Math.max(1, Math.floor(Number(qty) || 1));
  const ok = value > 0;
  const done = () => { if (ok) onDone(name.trim() || "Sərbəst məbləğ", Math.round(value * 100) / 100, count); };

  return (
    <Modal open title="Sərbəst məbləğ" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" disabled={!ok} onClick={done}>Səbətə yaz</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); done(); }} className="space-y-4">
        <p className="text-sm text-slate-500">Barkodu olmayan və ya sistemdə olmayan mal: məbləği yazın, adı istəyə bağlıdır. Anbar qalığı dəyişmir.</p>
        <label className="block text-sm font-medium text-slate-700">Qiymət (1 ədəd), ₼
          <input autoFocus value={amount} inputMode="decimal" aria-label="Məbləğ" placeholder="0.00"
            onChange={(e) => setAmount(e.target.value.replace(/[^\d.,]/g, ""))}
            className="mt-1 w-full rounded-xl border border-slate-300 px-4 py-3 text-right text-3xl font-semibold tabular-nums text-navy-800 outline-none focus:border-gold-500" />
        </label>
        <div className="flex gap-2">
          {QUICK.map((q) => (
            <button key={q} type="button" onClick={() => setAmount(String(q))}
              className="flex-1 rounded-xl border border-slate-200 py-2 text-sm font-medium text-navy-800 hover:border-gold-500">{q} ₼</button>
          ))}
        </div>
        <div className="grid grid-cols-[1fr_110px] gap-3">
          <label className="block text-sm font-medium text-slate-700">Nə satılır (istəyə bağlı)
            <input value={name} maxLength={200} onChange={(e) => setName(e.target.value)} placeholder="məs. Plastik vedrə"
              className="mt-1 w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm outline-none focus:border-gold-500" />
          </label>
          <label className="block text-sm font-medium text-slate-700">Say
            <input value={qty} inputMode="numeric" onChange={(e) => setQty(e.target.value.replace(/\D/g, ""))}
              className="mt-1 w-full rounded-xl border border-slate-200 px-3 py-2.5 text-center text-sm outline-none focus:border-gold-500" />
          </label>
        </div>
        <div className="flex items-baseline justify-between rounded-2xl bg-navy-800 px-5 py-4 text-white">
          <span className="text-sm text-white/70">{count > 1 ? `${count} × ${formatMoney(value)}` : "Cəmi"}</span>
          <span className="text-2xl font-bold tabular-nums">{formatMoney(Math.round(value * count * 100) / 100)}</span>
        </div>
      </form>
    </Modal>
  );
}
