"use client";

import { useEffect, useRef, useState } from "react";
import { Button, Modal } from "@/components/ui";
import { formatMoney } from "@/lib/format";
import { fromGrams, qtyWithUnit, unitKind } from "@/lib/units";
import type { Product } from "@/lib/types";
import { scaleText } from "@/components/sales/ScaleSettings";
import { useScale } from "@/components/sales/useScale";

const QUICK = [100, 250, 500, 1000, 1500, 2000];

/**
 * Çəki ilə satış: the cashier types the weight in qram or kq (or taps a quick weight); it goes into the receipt
 * in the product's own unit (kq or qram) with the price worked out at once.
 */
export function WeightEntry({ product, price, current, onDone, onClose }: {
  product: Product;
  price: number;
  /** Quantity already in the receipt (product unit), when changing a line. */
  current?: number;
  onDone: (qty: number) => void;
  onClose: () => void;
}) {
  const perGram = unitKind(product.unit) === "g";
  const [mode, setMode] = useState<"g" | "kg">("g");
  const startGrams = current ? (perGram ? current : current * 1000) : 0;
  const [text, setText] = useState(startGrams ? String(Math.round(startGrams)) : "");
  // kabellə tərəzi: while the cashier has not typed, the field follows the scale
  const reading = useScale();
  // changing a line keeps its weight until "Tərəzidən götür"
  const typed = useRef(!!current);
  const scaleGrams = reading && !reading.error && reading.kg != null && reading.kg > 0 ? Math.round(reading.kg * 1000) : null;
  useEffect(() => {
    if (scaleGrams != null && !typed.current) {
      setMode("g");
      setText(String(scaleGrams));
    }
  }, [scaleGrams]);

  const value = Number(text.replace(",", ".")) || 0;
  const grams = mode === "g" ? value : value * 1000;
  const qty = fromGrams(grams, product.unit);
  const total = Math.round(qty * price * 100) / 100;
  const ok = qty > 0;

  function switchMode(m: "g" | "kg") {
    if (m === mode) return;
    if (value > 0) setText(m === "kg" ? String(Math.round(grams) / 1000) : String(Math.round(grams)));
    setMode(m);
  }

  return (
    <Modal open title={`⚖ ${product.name}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" disabled={!ok} onClick={() => onDone(qty)}>Səbətə yaz</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); if (ok) onDone(qty); }} className="space-y-4">
        <p className="text-sm text-slate-500">Qiymət: <b className="text-navy-800">{formatMoney(price)} / {perGram ? "q" : "kq"}</b> · anbarda {qtyWithUnit(product.stockQty, product.unit)}</p>
        <div className="flex items-stretch gap-2">
          <input autoFocus value={text} inputMode="decimal" aria-label="Çəki" placeholder={mode === "g" ? "məs. 350" : "məs. 1,25"}
            onChange={(e) => { typed.current = true; setText(e.target.value.replace(/[^\d.,]/g, "")); }}
            className="min-w-0 flex-1 rounded-xl border border-slate-300 px-4 py-3 text-right text-3xl font-semibold tabular-nums text-navy-800 outline-none focus:border-gold-500" />
          <div className="inline-flex rounded-xl bg-slate-100 p-1" role="tablist" aria-label="Vahid">
            {(["g", "kg"] as const).map((m) => (
              <button key={m} type="button" role="tab" aria-selected={mode === m} onClick={() => switchMode(m)}
                className={`rounded-lg px-4 text-base font-semibold transition ${mode === m ? "bg-navy-800 text-white shadow" : "text-slate-600 hover:text-navy-800"}`}>
                {m === "g" ? "qram" : "kq"}
              </button>
            ))}
          </div>
        </div>
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
          {QUICK.map((g) => (
            <button key={g} type="button" onClick={() => { typed.current = true; setText(mode === "g" ? String(g) : String(g / 1000)); }}
              className="rounded-xl border border-slate-200 py-2 text-sm font-medium text-navy-800 hover:border-gold-500">
              {g < 1000 ? `${g} q` : `${(g / 1000).toLocaleString("az-AZ")} kq`}
            </button>
          ))}
        </div>
        {reading && (
          <div className={`flex items-center justify-between gap-3 rounded-xl px-4 py-2 text-sm ring-1 ${reading.error ? "bg-rose-50 text-rose-700 ring-rose-200" : reading.stable ? "bg-emerald-50 text-emerald-700 ring-emerald-200" : "bg-slate-50 text-slate-600 ring-slate-200"}`}>
            <span>⚖ Tərəzi: <b className="tabular-nums">{scaleText(reading).text}</b></span>
            {scaleGrams != null && typed.current && (
              <button type="button" onClick={() => { typed.current = false; setMode("g"); setText(String(scaleGrams)); }}
                className="rounded-lg bg-white px-3 py-1 font-semibold text-navy-800 ring-1 ring-slate-200 hover:ring-gold-500">Tərəzidən götür</button>
            )}
          </div>
        )}
        <div className="flex items-baseline justify-between rounded-2xl bg-navy-800 px-5 py-4 text-white">
          <span className="text-sm text-white/70">{ok ? qtyWithUnit(qty, product.unit) : "Çəkini yazın"}</span>
          <span className="text-2xl font-bold tabular-nums">{formatMoney(total)}</span>
        </div>
      </form>
    </Modal>
  );
}
