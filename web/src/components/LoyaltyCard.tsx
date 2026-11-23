"use client";

import { API_URL } from "@/lib/api";
import { formatMoney } from "@/lib/format";

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

/** Barcode picture of a customer's code (the till's scanner reads it like a product). */
export const cardBarcodeUrl = (code: string) => `${API_URL}/api/public/barcode.png?text=${encodeURIComponent(code)}`;

/**
 * The store's bonus card design (Bonus kartları → Kart dizaynı), kept with the store's print design: colours or a
 * picture for the background, the store's logo, text colour, the title and what the card shows.
 */
export interface CardDesign {
  /** Gradient from → to (CSS colours). */
  color1?: string;
  color2?: string;
  /** Background picture (data URL, resized in the browser); drawn over the colours. */
  backgroundImage?: string | null;
  /** 0–80: how much the picture is darkened so the text stays readable. */
  shade?: number;
  logo?: string | null;
  textColor?: string;
  /** Small line on top, e.g. "Bonus kartı" or "VIP kart". */
  title?: string;
  showStore?: boolean;
  showPercent?: boolean;
}

export const DEFAULT_CARD: Required<CardDesign> = {
  color1: "#0f1f3d", color2: "#3b1f6b", backgroundImage: null, shade: 30, logo: null, textColor: "#ffffff",
  title: "Bonus kartı", showStore: true, showPercent: true,
};

export const cardDesign = (d?: CardDesign | null): Required<CardDesign> => ({ ...DEFAULT_CARD, ...(d ?? {}) } as Required<CardDesign>);

function background(d: Required<CardDesign>): string {
  const grad = `linear-gradient(135deg, ${d.color1}, ${d.color2})`;
  if (!d.backgroundImage) return grad;
  const a = Math.max(0, Math.min(80, d.shade)) / 100;
  return `linear-gradient(rgba(0,0,0,${a}), rgba(0,0,0,${a})), url("${d.backgroundImage}") center / cover no-repeat, ${grad}`;
}

/** The card itself (bank-card proportions), as everyone sees it: in the app, in the kabinet and on paper. */
export function CardFace({ design, store, name, code, percent, balance, className = "" }: {
  design?: CardDesign | null; store: string; name: string; code: string; percent?: number | null; balance?: number | null; className?: string;
}) {
  const d = cardDesign(design);
  return (
    <div className={`relative flex aspect-[85.6/54] w-full flex-col overflow-hidden rounded-2xl p-[5%] shadow-xl ${className}`}
      style={{ background: background(d), color: d.textColor }}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate text-[10px] uppercase tracking-widest opacity-80">{d.title}{d.showPercent && percent ? ` · ${Number(percent)}%` : ""}</p>
          {d.showStore && <p className="truncate font-serif text-base font-semibold leading-tight">{store}</p>}
          <p className="truncate text-xs opacity-90">{name}</p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          {/* eslint-disable-next-line @next/next/no-img-element -- the store's own logo (data URL) */}
          {d.logo && <img src={d.logo} alt="" className="h-9 max-w-[90px] object-contain" />}
          {balance != null && <p className="font-serif text-base font-semibold tabular-nums">{formatMoney(balance)}</p>}
        </div>
      </div>
      <div className="mt-auto rounded-lg bg-white px-2 py-1 text-center text-black">
        {/* eslint-disable-next-line @next/next/no-img-element -- a server-made barcode picture */}
        <img src={cardBarcodeUrl(code)} alt={`Barkod ${code}`} className="mx-auto h-8 max-w-full" />
        <p className="text-[10px] tracking-[0.2em]">{code}</p>
      </div>
    </div>
  );
}

/**
 * Bonus kartı on paper: a bank-card sized page (85.6 × 54 mm) in the store's design. At the till the code is scanned
 * in the product search.
 */
export function printLoyaltyCard(card: { store: string; name: string; code: string; percent?: number | null; design?: CardDesign | null }) {
  const d = cardDesign(card.design);
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>Bonus kartı</title><style>
    @page { size: 85.6mm 54mm; margin: 0 }
    body { margin: 0; font-family: 'Segoe UI', Arial, sans-serif }
    .card { width: 85.6mm; height: 54mm; box-sizing: border-box; padding: 4mm 5mm; display: flex; flex-direction: column;
            background: ${background(d).replace(/"/g, "'")}; color: ${esc(d.textColor)};
            -webkit-print-color-adjust: exact; print-color-adjust: exact }
    .top { display: flex; justify-content: space-between; gap: 2mm }
    .sub { font-size: 7pt; opacity: .8; letter-spacing: .1em; text-transform: uppercase }
    .st { font-size: 11pt; font-weight: 700 } .nm { font-size: 9pt; opacity: .9 }
    .logo { max-height: 11mm; max-width: 26mm; object-fit: contain }
    .bc { margin-top: auto; background: #fff; border-radius: 2mm; padding: 1.2mm; text-align: center; color: #000 }
    .bc img { height: 10mm; max-width: 100% } .cd { font-size: 7.5pt; letter-spacing: .15em }
  </style></head><body><div class="card">
    <div class="top"><div>
      <div class="sub">${esc(d.title)}${d.showPercent && card.percent ? ` · ${Number(card.percent)}%` : ""}</div>
      ${d.showStore ? `<div class="st">${esc(card.store)}</div>` : ""}
      <div class="nm">${esc(card.name)}</div>
    </div>${d.logo ? `<img class="logo" src="${d.logo}" alt="">` : ""}</div>
    <div class="bc"><img src="${cardBarcodeUrl(card.code)}" alt=""><div class="cd">${esc(card.code)}</div></div>
  </div><script>window.onload = () => setTimeout(() => window.print(), 400)</script></body></html>`;
  const w = window.open("", "_blank", "noopener=no,width=480,height=360");
  if (!w) return false;
  w.document.write(html);
  w.document.close();
  return true;
}

/** The card as the customer sees it in their kabinet: the store's design, the code as a barcode and the balance. */
export function LoyaltyCardView({ store, name, code, balance, percent, design }: {
  store: string; name: string; code: string; balance: number; percent?: number | null; design?: CardDesign | null;
}) {
  return (
    <section className="mb-5">
      <CardFace design={design} store={store} name={name} code={code} percent={percent} balance={balance} className="mx-auto max-w-md" />
      <p className="mt-2 text-center text-xs text-slate-500">Kassada bu barkodu göstərin — bonus yığılır və xərclənir.</p>
    </section>
  );
}

/** Shrinks a picked picture so the design stays small: JPEG for photos (background), PNG for logos (keeps transparency). */
export function shrinkImage(file: File, maxW: number, maxH: number, png: boolean): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("Şəkil oxunmadı"));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error("Şəkil açılmadı"));
      img.onload = () => {
        const k = Math.min(1, maxW / img.width, maxH / img.height);
        const c = document.createElement("canvas");
        c.width = Math.round(img.width * k);
        c.height = Math.round(img.height * k);
        const g = c.getContext("2d");
        if (!g) { reject(new Error("Şəkil hazırlanmadı")); return; }
        if (!png) { g.fillStyle = "#fff"; g.fillRect(0, 0, c.width, c.height); }
        g.drawImage(img, 0, 0, c.width, c.height);
        resolve(png ? c.toDataURL("image/png") : c.toDataURL("image/jpeg", 0.82));
      };
      img.src = String(reader.result);
    };
    reader.readAsDataURL(file);
  });
}
