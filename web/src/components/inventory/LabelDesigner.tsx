"use client";

import { useEffect, useState } from "react";
import { Card, Field, Input } from "@/components/ui";
import { LABEL_DESIGN_KEY, onPrintDesignSaved, pushPrintDesign } from "@/lib/printDesign";
import type { Product } from "@/lib/types";

export const LABEL_SIZES = [
  { key: "small", title: "Balaca", w: 40, h: 25, show: ["name", "price", "old"] },
  { key: "medium", title: "Orta", w: 58, h: 40, show: ["store", "name", "price", "old"] },
  { key: "large", title: "Böyük", w: 100, h: 50, show: ["store", "name", "code", "price", "old"] },
] as const;
export const LABEL_PARTS = [["store", "Mağaza adı"], ["name", "Məhsulun adı"], ["code", "Kod və vahid"], ["price", "Qiymət"], ["old", "Endirim (köhnə qiymət xətli)"]] as const;

export interface LabelDesign { size: string; w: number; h: number; barcode: number; font: number; show: string[] }

const DEFAULT: LabelDesign = { size: "medium", w: 58, h: 40, barcode: 100, font: 100, show: ["store", "name", "price", "old"] };

export function loadLabelDesign(): LabelDesign {
  try {
    const raw = localStorage.getItem(LABEL_DESIGN_KEY);
    if (raw) return { ...DEFAULT, ...(JSON.parse(raw) as Partial<LabelDesign>) };
  } catch { /* storage unavailable */ }
  return DEFAULT;
}

/** Keeps the design on this computer and sends it to the store (all tills and browsers print the same). */
export function saveLabelDesign(d: LabelDesign) {
  try { localStorage.setItem(LABEL_DESIGN_KEY, JSON.stringify(d)); } catch { /* ignore */ }
  pushPrintDesign("label");
}

export const labelSizeOk = (d: LabelDesign) => d.w >= 20 && d.w <= 150 && d.h >= 12 && d.h <= 120;

/** Size (preset or own mm), barcode / text scale and the parts shown on the label. */
export function LabelDesignControls({ d, onChange }: { d: LabelDesign; onChange: (d: LabelDesign) => void }) {
  function pick(key: string) {
    const p = LABEL_SIZES.find((s) => s.key === key);
    onChange(p ? { ...d, size: key, w: p.w, h: p.h, show: [...p.show] } : { ...d, size: "custom" });
  }
  return (
    <>
      <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Ölçü</p>
      <div className="flex flex-wrap gap-2">
        {LABEL_SIZES.map((s) => (
          <button key={s.key} type="button" onClick={() => pick(s.key)}
            className={`rounded-full border px-3 py-1.5 text-sm ${d.size === s.key ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>{s.title} {s.w}×{s.h}</button>
        ))}
        <button type="button" onClick={() => pick("custom")}
          className={`rounded-full border px-3 py-1.5 text-sm ${d.size === "custom" ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>Öz ölçüm</button>
      </div>
      {d.size === "custom" && (
        <div className="mt-3 grid grid-cols-2 gap-3">
          <Field label="En (mm)" hint="20–150"><Input type="number" min={20} max={150} value={d.w} onChange={(e) => onChange({ ...d, w: Number(e.target.value) })} /></Field>
          <Field label="Hündürlük (mm)" hint="12–120"><Input type="number" min={12} max={120} value={d.h} onChange={(e) => onChange({ ...d, h: Number(e.target.value) })} /></Field>
        </div>
      )}
      <p className="mb-2 mt-5 text-xs font-semibold uppercase tracking-wider text-slate-500">Dizayn</p>
      <label className="flex items-center gap-3 text-sm"><span className="w-28">Barkod ölçüsü</span>
        <input type="range" min={30} max={100} step={5} value={d.barcode} onChange={(e) => onChange({ ...d, barcode: Number(e.target.value) })} className="flex-1" />
        <span className="w-10 text-right">{d.barcode}%</span></label>
      <label className="mt-2 flex items-center gap-3 text-sm"><span className="w-28">Yazı ölçüsü</span>
        <input type="range" min={50} max={200} step={10} value={d.font} onChange={(e) => onChange({ ...d, font: Number(e.target.value) })} className="flex-1" />
        <span className="w-10 text-right">{d.font}%</span></label>
      <div className="mt-3 flex flex-wrap gap-2">
        {LABEL_PARTS.map(([k, t]) => (
          <label key={k} className="flex items-center gap-1.5 rounded-full border border-slate-200 px-3 py-1 text-sm">
            <input type="checkbox" checked={d.show.includes(k)} onChange={(e) => {
              const on = e.target.checked;
              // the struck-through old price sits next to the price, so "Endirim" also switches the price on
              const show = on ? [...new Set([...d.show, k, ...(k === "old" ? ["price"] : [])])] : d.show.filter((x) => x !== k);
              onChange({ ...d, show });
            }} />{t}
          </label>
        ))}
      </div>
    </>
  );
}

/** Approximate picture of one label (the PDF is the real label). */
export function LabelPreview({ d, product, storeName }: { d: LabelDesign; product?: Product | null; storeName?: string }) {
  const scale = Math.min(300 / d.w, 160 / d.h);
  const base = Math.max(6, Math.min(14, d.h * 0.25)) * (d.font / 100) * scale * 0.36;
  const sale = product?.salePrice ?? 10;
  const old = product ? product.oldPrice ?? null : 15;
  const discount = d.show.includes("old") && old != null && old > sale;
  const wide = d.show.includes("price") && d.w / d.h >= 1.8;
  const money = (v: number) => `${v.toFixed(2).replace(".", ",")} AZN`;
  const priceEl = (
    <div className={wide ? "text-right" : "flex items-end justify-between"}>
      {discount && <div className="text-slate-500 line-through" style={{ fontSize: base * 0.95 }}>{money(old!)}</div>}
      <div className={wide || discount ? "" : "w-full text-center"} style={{ fontSize: base * 1.7, fontWeight: 900, lineHeight: 1 }}>{money(sale)}</div>
    </div>
  );
  return (
    <div className="flex min-h-[180px] items-center justify-center rounded-xl bg-slate-100 p-4">
      <div className="flex flex-col overflow-hidden rounded border border-slate-400 bg-white text-black shadow"
        style={{ width: d.w * scale, height: d.h * scale, padding: Math.min(d.w, d.h) * 0.06 * scale }}>
        {d.show.includes("store") && <div className="truncate text-slate-600" style={{ fontSize: base * 0.7 }}>{storeName ?? "Mağaza"}</div>}
        {d.show.includes("name") && <div className={d.h >= 35 ? "line-clamp-2" : "truncate"} style={{ fontSize: base, fontWeight: 800, lineHeight: 1.1 }}>{product?.name ?? "Məhsulun adı"}</div>}
        {d.show.includes("code") && <div style={{ fontSize: base * 0.75 }}>{product?.code ?? "P-000001"} · {product?.unit ?? "ədəd"}</div>}
        <div className="flex min-h-0 flex-1 items-end gap-2">
          <div className="h-full min-w-0 flex-1">
            <div className="flex h-full w-full items-center justify-center">
              <div className="flex h-full flex-col items-center" style={{ width: `${d.barcode}%`, height: `${d.barcode}%` }}>
                <div className="w-full flex-1" style={{ background: "repeating-linear-gradient(90deg,#000 0 2px,#fff 2px 4px,#000 4px 5px,#fff 5px 8px)" }} />
                <div style={{ fontSize: Math.max(6, base * 0.8), letterSpacing: 1 }}>{product?.barcode ?? product?.code ?? "4760000000011"}</div>
              </div>
            </div>
          </div>
          {wide && d.show.includes("price") && <div>{discount && <div className="text-right font-extrabold" style={{ fontSize: base * 0.75 }}>ENDİRİM</div>}{priceEl}</div>}
        </div>
        {!wide && d.show.includes("price") && priceEl}
      </div>
    </div>
  );
}

/** Parametrlər → barkod etiketi: the store's label design, saved as it is changed. */
export function LabelDesignCard({ storeName }: { storeName?: string }) {
  const [d, setD] = useState<LabelDesign>(DEFAULT);
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => { setD(loadLabelDesign()); }, []);
  useEffect(() => onPrintDesignSaved((ok) => setNote(ok ? "Yadda saxlanıldı" : "Serverə yazılmadı — yalnız bu kompüterdə saxlanıldı")), []);

  function change(next: LabelDesign) {
    setD(next);
    setNote(null);
    if (labelSizeOk(next)) saveLabelDesign(next);
    else setNote("Etiket ölçüsü: en 20–150 mm, hündürlük 12–120 mm");
  }

  return (
    <Card className="lg:col-span-2">
      <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-2 font-serif text-lg font-semibold text-navy-800"><span aria-hidden="true">🏷️</span>Barkod etiketi dizaynı</h2>
        {note && <span className={`text-sm ${note === "Yadda saxlanıldı" ? "text-emerald-700" : "text-amber-700"}`}>{note}</span>}
      </div>
      <div className="grid gap-6 md:grid-cols-2">
        <div><LabelDesignControls d={d} onChange={change} /></div>
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Önizləmə</p>
          <LabelPreview d={d} storeName={storeName} />
          <p className="mt-3 text-xs text-slate-500">Anbarda “Barkod” ilə çap edəndə bu dizayn işlənir. Dizayn mağaza üzrə saxlanılır: bütün kassalarda və brauzerlərdə eynidir.</p>
        </div>
      </div>
    </Card>
  );
}
