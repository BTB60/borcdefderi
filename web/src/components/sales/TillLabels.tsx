"use client";

import { useEffect, useMemo, useState } from "react";
import { Alert, Button, Field, Input, Modal, Select } from "@/components/ui";
import { API_URL } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { desktop } from "@/lib/desktop";
import { formatMoney } from "@/lib/format";
import type { Product } from "@/lib/types";

const SIZES = [
  { key: "40x25", w: 40, h: 25, title: "Balaca 40×25" },
  { key: "58x40", w: 58, h: 40, title: "Orta 58×40" },
  { key: "100x50", w: 100, h: 50, title: "Böyük 100×50" },
];
const KEY = "hp_till_label";

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

/** One page per label, sized in mm, for a label printer: store, name, price (old price struck), barcode. */
export function labelsHtml(items: { product: Product; count: number }[], wMm: number, hMm: number, store: string): string {
  const small = hMm < 30;
  const pages = items.flatMap(({ product: p, count }) => Array.from({ length: count }, () => {
    const code = (p.barcode || p.code || "").replace(/[^0-9A-Za-z-]/g, "");
    const old = p.oldPrice && p.oldPrice > p.salePrice ? `<s>${esc(formatMoney(p.oldPrice))}</s> ` : "";
    return `<section>
      ${small ? "" : `<div class="st">${esc(store)}</div>`}
      <div class="nm">${esc(p.name)}</div>
      ${code ? `<img src="${API_URL}/api/public/barcode.png?text=${encodeURIComponent(code)}" alt=""><div class="cd">${esc(code)}</div>` : ""}
      <div class="pr">${old}${esc(formatMoney(p.salePrice))}</div>
    </section>`;
  }));
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    @page { size: ${wMm}mm ${hMm}mm; margin: 0 }
    body { margin: 0; font-family: "Segoe UI", Arial, sans-serif; color: #000 }
    section { width: ${wMm}mm; height: ${hMm}mm; box-sizing: border-box; padding: 1.2mm 1.5mm; display: flex; flex-direction: column;
      justify-content: space-between; overflow: hidden; page-break-after: always; break-after: page }
    .st { font-size: ${small ? 6 : 7}pt; color: #333; white-space: nowrap; overflow: hidden }
    .nm { font-size: ${small ? 7 : 9}pt; font-weight: 700; line-height: 1.1; max-height: 2.2em; overflow: hidden }
    img { width: 100%; height: ${Math.max(6, hMm * 0.32)}mm; object-fit: fill; image-rendering: pixelated }
    .cd { font-size: 6pt; text-align: center; letter-spacing: .5px }
    .pr { font-size: ${small ? 10 : 14}pt; font-weight: 800; text-align: right } s { font-size: .6em; font-weight: 400; color: #444 }
  </style></head><body>${pages.join("")}</body></html>`;
}

/** Windows kassa: barcode labels straight to the label printer (pick goods, how many, label size). */
export function TillLabelsModal({ products, onClose }: { products: Product[]; onClose: () => void }) {
  const { me } = useAuth();
  const app = desktop();
  const [size, setSize] = useState("58x40");
  const [q, setQ] = useState("");
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [printers, setPrinters] = useState<{ name: string; isDefault: boolean }[]>([]);
  const [printer, setPrinter] = useState<string>("");
  const [msg, setMsg] = useState<{ kind: "error" | "success"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    try { const s = localStorage.getItem(KEY); if (s) setSize(s); } catch { /* ignore */ }
    app?.getPrinters().then(setPrinters).catch(() => undefined);
    app?.getLabelPrinter?.().then((p) => setPrinter(p ?? "")).catch(() => undefined);
  }, [app]);

  const found = useMemo(() => {
    const s = q.trim().toLowerCase();
    return (s ? products.filter((p) => p.name.toLowerCase().includes(s) || p.code.toLowerCase().includes(s) || (p.barcode ?? "").includes(s)) : products).slice(0, 40);
  }, [products, q]);
  const total = Object.values(counts).reduce((a, b) => a + b, 0);

  async function print() {
    const sz = SIZES.find((x) => x.key === size) ?? SIZES[1];
    const items = products.filter((p) => (counts[p.id] ?? 0) > 0).map((p) => ({ product: p, count: counts[p.id] }));
    if (!items.length || !app?.printLabels) return;
    setBusy(true);
    setMsg(null);
    try {
      try { localStorage.setItem(KEY, size); } catch { /* ignore */ }
      const ok = await app.printLabels({ html: labelsHtml(items, sz.w, sz.h, me?.storeName ?? ""), widthMm: sz.w, heightMm: sz.h });
      setMsg(ok ? { kind: "success", text: `${total} etiket printerə göndərildi` } : { kind: "error", text: "Çap alınmadı — printeri yoxlayın" });
      if (ok) setCounts({});
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title="Etiket çap et" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Bağla</Button><Button variant="gold" disabled={busy || total === 0} onClick={() => void print()}>{busy ? "Göndərilir…" : `Çap et (${total})`}</Button></>}>
      {msg && <div className="mb-3"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <div className="mb-4 grid gap-3 sm:grid-cols-2">
        <Field label="Etiket printeri">
          <Select value={printer} onChange={(e) => { setPrinter(e.target.value); void app?.setLabelPrinter?.(e.target.value || null); }}>
            <option value="">Windows-un əsas printeri</option>
            {printers.map((p) => <option key={p.name} value={p.name}>{p.name}{p.isDefault ? " (əsas)" : ""}</option>)}
          </Select>
        </Field>
        <Field label="Etiket ölçüsü">
          <Select value={size} onChange={(e) => setSize(e.target.value)}>
            {SIZES.map((s) => <option key={s.key} value={s.key}>{s.title} mm</option>)}
          </Select>
        </Field>
      </div>
      <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Məhsul axtar — ad, kod, barkod" aria-label="Məhsul axtar" />
      <ul className="mt-3 max-h-80 divide-y divide-slate-100 overflow-y-auto rounded-xl ring-1 ring-slate-200">
        {found.map((p) => (
          <li key={p.id} className="flex items-center gap-3 px-3 py-2">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-navy-800">{p.name}</p>
              <p className="text-xs text-slate-400">{p.barcode || p.code} · {formatMoney(p.salePrice)} · qalıq {Number(p.stockQty)}</p>
            </div>
            <button type="button" onClick={() => setCounts((c) => ({ ...c, [p.id]: Math.max(0, (c[p.id] ?? 0) - 1) }))} className="h-8 w-8 rounded-lg bg-slate-100 text-lg">−</button>
            <span className="w-8 text-center font-semibold tabular-nums">{counts[p.id] ?? 0}</span>
            <button type="button" onClick={() => setCounts((c) => ({ ...c, [p.id]: Math.min(500, (c[p.id] ?? 0) + 1) }))} className="h-8 w-8 rounded-lg bg-slate-100 text-lg">+</button>
            <button type="button" onClick={() => setCounts((c) => ({ ...c, [p.id]: Math.max(1, Math.ceil(p.stockQty)) }))} className="rounded-lg px-2 py-1 text-xs text-gold-700 hover:bg-gold-500/10">qalıq qədər</button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}
