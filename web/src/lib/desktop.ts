/**
 * The HesabPro Kassa Windows app (Electron) exposes `window.hesabDesktop`. In the browser it is undefined and every
 * helper here falls back to normal web behaviour.
 */
import type { Sale } from "./types";
import { qtyWithUnit, unitKind } from "@/lib/units";
import { formatMoney } from "./format";
import { RECEIPT_DESIGN_KEY, pushPrintDesign } from "@/lib/printDesign";

export interface DesktopBridge {
  version: string;
  /** Prints receipt HTML straight to the chosen printer, with no dialog. */
  printHtml(html: string): Promise<boolean>;
  getPrinters(): Promise<{ name: string; isDefault: boolean }[]>;
  getPrinter(): Promise<string | null>;
  setPrinter(name: string | null): Promise<void>;
  /** Keeps a sale made without internet; the app sends it by itself when the server is reachable again. */
  queueSale(sale: { apiUrl: string; token: string; payload: unknown; total: number }): Promise<void>;
  pending(): Promise<{ count: number; failed: { total: number; error: string; savedAt: string }[] }>;
  syncNow(): Promise<number>;
  onPendingChange(cb: (count: number) => void): () => void;
  /** App updates (missing in app versions older than this): download by themselves, then "Yenilə". */
  updateStatus?(): Promise<UpdateStatus>;
  installUpdate?(): Promise<boolean>;
  onUpdate?(cb: (s: UpdateStatus) => void): () => void;
  /** Open with Windows. */
  getAutoStart?(): Promise<boolean>;
  setAutoStart?(on: boolean): Promise<boolean>;
  /** Cash drawer on the receipt printer (ESC/POS pulse); auto = open after every cash sale. */
  openDrawer?(): Promise<boolean>;
  getDrawerAuto?(): Promise<boolean>;
  setDrawerAuto?(on: boolean): Promise<boolean>;
  /** Customer display window, full screen on the second monitor when there is one. */
  openCustomerDisplay?(): Promise<boolean>;
  /** Label printer (barcode labels go straight to it, one page per label). */
  getLabelPrinter?(): Promise<string | null>;
  setLabelPrinter?(name: string | null): Promise<void>;
  printLabels?(job: { html: string; widthMm: number; heightMm: number }): Promise<boolean>;
  /** Kabellə qoşulan tərəzi (COM port): models, ports, settings and the live weight. */
  scaleProtocols?(): Promise<ScaleProtocol[]>;
  scalePorts?(): Promise<string[]>;
  getScale?(): Promise<ScaleConfig | null>;
  setScale?(cfg: ScaleConfig | null): Promise<ScaleConfig | null>;
  scaleWeight?(): Promise<ScaleReading | null>;
  onScale?(cb: (r: ScaleReading) => void): () => void;
}

export type ScaleProtocol = { id: string; label: string; baud: number; format: string };
export type ScaleConfig = { port: string; protocol: string; baud: number; format: string; unit: "auto" | "kg" | "g" | "lb"; command: string };
/** kg = weight in kilograms (null while moving), stable = the scale has settled; error = no connection. */
export type ScaleReading = { kg?: number | null; stable?: boolean; open?: boolean; error?: string; at: number };

export type UpdateStatus = { state: "idle" | "downloading" | "ready" | "installing"; version: string | null; percent: number };

declare global {
  interface Window {
    hesabDesktop?: DesktopBridge;
  }
}

export function desktop(): DesktopBridge | undefined {
  return typeof window === "undefined" ? undefined : window.hesabDesktop;
}

/** Printers that only make a file (PDF, XPS, OneNote, fax): a receipt "printed" there never comes out on paper. */
export function isVirtualPrinter(name: string | null | undefined): boolean {
  return !!name && /pdf|xps|onenote|fax|send to|to file/i.test(name);
}

/**
 * Why receipts would not come out of the chosen printer, or null when it looks fine: Windows sees no printer,
 * the chosen one is gone (unplugged, renamed), or it is a "print to file" printer.
 */
export async function printerProblem(app: DesktopBridge): Promise<string | null> {
  try {
    const [printers, chosen] = await Promise.all([app.getPrinters(), app.getPrinter()]);
    if (printers.length === 0) return "Windows heç bir printer görmür. Çek printerini qoşun və drayverini quraşdırın.";
    if (chosen && !printers.some((p) => p.name === chosen)) {
      return `Seçilmiş printer “${chosen}” tapılmadı (söndürülüb və ya ayrılıb). Kassa → Parametrlər → Çek printeri bölməsində yenisini seçin.`;
    }
    const target = chosen || printers.find((p) => p.isDefault)?.name || null;
    if (isVirtualPrinter(target)) {
      return `Çek “${target}”-ə göndərilir — bu, real printer deyil, kağız çıxmır. Kassa → Parametrlər → Çek printeri bölməsində çek printerini seçin.`;
    }
    return null;
  } catch {
    return null; // an older app without the printer list: just try
  }
}

/** Prints on the till and says what went wrong (null = sent to the printer). */
export async function printOnTill(app: DesktopBridge, html: string): Promise<string | null> {
  const problem = await printerProblem(app);
  if (problem) return problem;
  const ok = await app.printHtml(html).catch(() => false);
  return ok ? null : "Printer cavab vermədi. Printerin yanılı olduğunu, kağızın və kabelin yerində olduğunu yoxlayın.";
}

/** True when the request never reached the server (no internet / server asleep), not when it answered with an error. */
export function isNetworkError(e: unknown): boolean {
  return e instanceof TypeError;
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

/** Çek dizaynı: everything the store can choose about its receipt (kept by the store, mirrored on this computer). */
export type ReceiptSettings = {
  paper: "80" | "58";
  footer: string;
  /** Extra lines under the store name: phone, address, VÖEN, Instagram… */
  header: string;
  /** Small picture on top (data URL), or null. */
  logo: string | null;
  fontSize: "s" | "m" | "l";
  align: "center" | "left";
  showCashier: boolean;
  showCustomer: boolean;
  showStoreName: boolean;
  boldTotal: boolean;
};

const DESIGN_KEY = RECEIPT_DESIGN_KEY;
const DEFAULTS: ReceiptSettings = {
  paper: "80", footer: "Təşəkkür edirik!", header: "", logo: null, fontSize: "m", align: "center",
  showCashier: true, showCustomer: true, showStoreName: true, boldTotal: true,
};

export function receiptSettings(): ReceiptSettings {
  try {
    const saved = JSON.parse(localStorage.getItem(DESIGN_KEY) ?? "null") as Partial<ReceiptSettings> | null;
    // older keys (before the designer) still count
    const paper = localStorage.getItem("hp_paper") === "58" ? "58" : "80";
    const footer = localStorage.getItem("hp_receipt_footer");
    return { ...DEFAULTS, paper, ...(footer !== null ? { footer } : {}), ...(saved ?? {}) };
  } catch {
    return DEFAULTS;
  }
}

export function saveReceiptSettings(s: Partial<ReceiptSettings>) {
  try {
    const next = { ...receiptSettings(), ...s };
    localStorage.setItem(DESIGN_KEY, JSON.stringify(next));
    if (s.paper) localStorage.setItem("hp_paper", s.paper);
    if (s.footer !== undefined) localStorage.setItem("hp_receipt_footer", s.footer);
    pushPrintDesign("receipt");
  } catch { /* storage full (big logo) or blocked */ }
}

/** Page CSS for the chosen paper width and text size. */
export function paperCss(d: ReceiptSettings = receiptSettings()): string {
  const base = d.paper === "58" ? { s: 9.5, m: 10.5, l: 12 } : { s: 11, m: 12, l: 13.5 };
  const size = base[d.fontSize] ?? base.m;
  return d.paper === "58"
    ? `@page { size: 58mm auto; margin: 0 } body { width: 50mm; margin: 0 auto; padding: 3mm 0; font: ${size}px/1.3 'Segoe UI', Arial, sans-serif; color: #000 }`
    : `@page { size: 80mm auto; margin: 0 } body { width: 72mm; margin: 0 auto; padding: 4mm 0; font: ${size}px/1.35 'Segoe UI', Arial, sans-serif; color: #000 }`;
}

/** A sale receipt as HTML in the store's design (the desktop app prints it silently). */
export function receiptHtml(sale: Sale, store: { name: string | null }, opts: { change?: number; offline?: boolean; design?: ReceiptSettings } = {}): string {
  const d = opts.design ?? receiptSettings();
  const rows = sale.items.map((i) => `
    <tr><td colspan="2">${esc(i.name)}</td></tr>
    <tr><td class="m">${unitKind(i.unit) === "piece" ? Number(i.quantity) : esc(qtyWithUnit(Number(i.quantity), i.unit))} × ${formatMoney(i.unitPrice)}${i.discount > 0 ? ` −${formatMoney(i.discount)}` : ""}</td>
        <td class="r">${formatMoney(i.lineTotal)}</td></tr>${(i.serials ?? []).map((s) => `
    <tr><td colspan="2" class="m">IMEI/SN: ${esc(s)}</td></tr>`).join("")}${i.warrantyMonths ? `
    <tr><td colspan="2" class="m">Zəmanət: ${i.warrantyMonths} ay${sale.createdAt ? ` (${esc(new Date(new Date(sale.createdAt).setMonth(new Date(sale.createdAt).getMonth() + i.warrantyMonths)).toLocaleDateString("az-AZ"))}-dək)` : ""}</td></tr>` : ""}`).join("");
  const line = (label: string, v: number, cls = "") => `<tr class="${cls}"><td>${label}</td><td class="r">${formatMoney(v)}</td></tr>`;
  const when = new Date(sale.createdAt || Date.now()).toLocaleString("az-AZ");
  const head = d.header.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => `<div class="h m">${esc(l)}</div>`).join("");
  const who = [d.showCashier && sale.createdByName ? `Kassir: ${esc(sale.createdByName)}` : "", d.showCustomer && sale.customerName ? `Müştəri: ${esc(sale.customerName)}` : ""].filter(Boolean);
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    ${paperCss(d)}
    h1 { font-size: 1.35em; margin: 0 0 2px } .h, h1, .logo { text-align: ${d.align} } .c { text-align: center } .m { color: #333 }
    .logo img { max-width: 60%; max-height: 22mm; object-fit: contain; filter: grayscale(1) contrast(1.4) }
    table { width: 100%; border-collapse: collapse } td { padding: 1px 0; vertical-align: top } .r { text-align: right; white-space: nowrap }
    hr { border: 0; border-top: 1px dashed #000; margin: 6px 0 } .big td { font-size: 1.25em; ${d.boldTotal ? "font-weight: 800" : "font-weight: 600"} }
  </style></head><body>
    ${d.logo ? `<div class="logo"><img src="${d.logo}" alt=""></div>` : ""}
    ${d.showStoreName ? `<h1>${esc(store.name ?? "HesabPro")}</h1>` : ""}
    ${head}
    <div class="c">${sale.priceLevel === "WHOLESALE" ? "Topdan satış çeki" : "Satış çeki"} ${esc(sale.number)}</div>
    <div class="c m">${esc(when)}</div>
    ${who.length ? `<div class="c m">${who.join(" · ")}</div>` : ""}
    ${opts.offline ? '<div class="c"><b>İnternetsiz satış — sonra göndəriləcək</b></div>' : ""}
    <hr><table>${rows}</table><hr>
    <table>
      ${sale.discount - (sale.bonusUsed ?? 0) > 0.004 ? line("Endirim", -(sale.discount - (sale.bonusUsed ?? 0))) : ""}
      ${(sale.bonusUsed ?? 0) > 0 ? line("Bonus kartı ilə", -(sale.bonusUsed ?? 0)) : ""}
      ${line("YEKUN", sale.total, "big")}
      ${sale.paidCash > 0 ? line("Nağd", sale.paidCash) : ""}
      ${sale.paidCard > 0 ? line("Kart", sale.paidCard) : ""}
      ${sale.creditAmount > 0 ? line("Borca", sale.creditAmount) : ""}
      ${opts.change && opts.change > 0 ? line("Qaytarılan", opts.change) : ""}
    </table>
    <hr>${d.footer.trim() ? d.footer.trim().split("\n").map((l) => `<div class="c">${esc(l)}</div>`).join("") : ""}<div class="c m">HesabPro</div>
  </body></html>`;
}
