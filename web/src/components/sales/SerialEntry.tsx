"use client";

import { useRef, useState } from "react";
import { Button, Modal } from "@/components/ui";
import type { Product } from "@/lib/types";

/**
 * IMEI / seriya nömrəsi: the cashier scans (or types) one number per unit; each Enter adds one.
 * The receipt line gets as many units as numbers.
 */
export function SerialEntry({ product, current, onDone, onClose }: {
  product: Product;
  /** Numbers already in the receipt for this product. */
  current: string[];
  onDone: (serials: string[]) => void;
  onClose: () => void;
}) {
  const [list, setList] = useState<string[]>(current);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  function addOne() {
    const s = text.trim();
    if (!s) return;
    if (!/^[0-9A-Za-z-]{4,64}$/.test(s)) { setError("Yalnız rəqəm / hərf, 4–64 simvol"); return; }
    if (list.includes(s)) { setError(`${s} artıq əlavə olunub`); return; }
    if (list.length >= Math.floor(product.stockQty)) { setError(`Anbarda cəmi ${Math.floor(product.stockQty)} ədəd var`); return; }
    setList([...list, s]);
    setText("");
    setError(null);
    input.current?.focus();
  }

  return (
    <Modal open title={`📱 ${product.name}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" disabled={list.length === 0 && current.length === 0} onClick={() => onDone(list)}>{list.length ? `Səbətə yaz (${list.length} ədəd)` : "Səbətdən çıxar"}</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); addOne(); }} className="space-y-3">
        <p className="text-sm text-slate-500">Hər ədədin IMEI / seriya nömrəsini skanerlə oxudun və ya yazıb Enter basın.{product.warrantyMonths ? ` Zəmanət: ${product.warrantyMonths} ay.` : ""}</p>
        <input ref={input} autoFocus value={text} onChange={(e) => { setText(e.target.value); setError(null); }} aria-label="IMEI / seriya nömrəsi"
          placeholder="IMEI / seriya nömrəsi" className="w-full rounded-xl border border-slate-300 px-4 py-3 font-mono text-lg tracking-wider text-navy-800 outline-none focus:border-gold-500" />
        {error && <p className="text-sm text-rose-600">{error}</p>}
        {list.length > 0 && (
          <ul className="space-y-1.5">
            {list.map((s, i) => (
              <li key={s} className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-1.5 font-mono text-sm text-navy-800">
                <span>{i + 1}. {s}</span>
                <button type="button" onClick={() => setList(list.filter((x) => x !== s))} aria-label={`${s} sil`} className="px-1 text-slate-400 hover:text-rose-600">×</button>
              </li>
            ))}
          </ul>
        )}
      </form>
    </Modal>
  );
}
