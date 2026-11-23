"use client";

import { API_URL } from "@/lib/api";
import { formatMoney } from "@/lib/format";

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

/** Barcode picture of a customer's code (the till's scanner reads it like a product). */
export const cardBarcodeUrl = (code: string) => `${API_URL}/api/public/barcode.png?text=${encodeURIComponent(code)}`;

/**
 * Bonus kartı: a bank-card sized page (85.6 × 54 mm) with the store, the customer, their code as a barcode and the
 * bonus percent. Printed from the customer's card; at the till the code is scanned in the product search.
 */
export function printLoyaltyCard(card: { store: string; name: string; code: string; percent?: number | null }) {
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>Bonus kartı</title><style>
    @page { size: 85.6mm 54mm; margin: 0 }
    body { margin: 0; font-family: 'Segoe UI', Arial, sans-serif }
    .card { width: 85.6mm; height: 54mm; box-sizing: border-box; padding: 4mm 5mm; display: flex; flex-direction: column;
            background: linear-gradient(135deg, #0f1f3d, #3b1f6b); color: #fff; -webkit-print-color-adjust: exact; print-color-adjust: exact }
    .st { font-size: 11pt; font-weight: 700 } .sub { font-size: 7pt; opacity: .7; letter-spacing: .1em; text-transform: uppercase }
    .nm { margin-top: 2mm; font-size: 10pt; font-weight: 600 }
    .bc { margin-top: auto; background: #fff; border-radius: 2mm; padding: 1.5mm; text-align: center; color: #000 }
    .bc img { height: 11mm; max-width: 100% } .cd { font-size: 8pt; letter-spacing: .15em }
  </style></head><body><div class="card">
    <div class="sub">Bonus kartı${card.percent ? ` · ${card.percent}% bonus` : ""}</div>
    <div class="st">${esc(card.store)}</div>
    <div class="nm">${esc(card.name)}</div>
    <div class="bc"><img src="${cardBarcodeUrl(card.code)}" alt=""><div class="cd">${esc(card.code)}</div></div>
  </div><script>window.onload = () => setTimeout(() => window.print(), 300)</script></body></html>`;
  const w = window.open("", "_blank", "noopener=no,width=480,height=360");
  if (!w) return false;
  w.document.write(html);
  w.document.close();
  return true;
}

/** The card as the customer sees it in their kabinet: code as barcode and the bonus balance. */
export function LoyaltyCardView({ store, name, code, balance, percent }: { store: string; name: string; code: string; balance: number; percent?: number | null }) {
  return (
    <section className="mb-5 overflow-hidden rounded-3xl bg-gradient-to-br from-navy-800 to-violet-900 p-5 text-white shadow-xl">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-[11px] uppercase tracking-widest text-white/60">Bonus kartı{percent ? ` · hər alışdan ${percent}%` : ""}</p>
          <p className="font-serif text-lg font-semibold">{store}</p>
          <p className="text-sm text-white/80">{name}</p>
        </div>
        <div className="text-right">
          <p className="text-[11px] uppercase tracking-widest text-white/60">Bonus</p>
          <p className="font-serif text-2xl font-semibold tabular-nums">{formatMoney(balance)}</p>
        </div>
      </div>
      <div className="mt-4 rounded-2xl bg-white p-3 text-center text-black">
        {/* eslint-disable-next-line @next/next/no-img-element -- a server-made barcode picture */}
        <img src={cardBarcodeUrl(code)} alt={`Barkod ${code}`} className="mx-auto h-14 max-w-full" />
        <p className="mt-1 text-xs tracking-[0.2em]">{code}</p>
      </div>
      <p className="mt-2 text-xs text-white/60">Kassada bu barkodu göstərin — bonus yığılır və xərclənir.</p>
    </section>
  );
}
