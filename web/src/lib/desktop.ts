/**
 * The HesabPro Kassa Windows app (Electron) exposes `window.hesabDesktop`. In the browser it is undefined and every
 * helper here falls back to normal web behaviour.
 */
import type { Sale } from "./types";
import { formatMoney } from "./format";

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
}

export type UpdateStatus = { state: "idle" | "downloading" | "ready"; version: string | null; percent: number };

declare global {
  interface Window {
    hesabDesktop?: DesktopBridge;
  }
}

export function desktop(): DesktopBridge | undefined {
  return typeof window === "undefined" ? undefined : window.hesabDesktop;
}

/** True when the request never reached the server (no internet / server asleep), not when it answered with an error. */
export function isNetworkError(e: unknown): boolean {
  return e instanceof TypeError;
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

/** 80 mm receipt as HTML (the desktop app prints it silently). */
/** Receipt paper: 80 mm (default) or 58 mm, and the line printed at the bottom of every receipt. */
export type ReceiptSettings = { paper: "80" | "58"; footer: string };

export function receiptSettings(): ReceiptSettings {
  try {
    const paper = localStorage.getItem("hp_paper") === "58" ? "58" : "80";
    return { paper, footer: localStorage.getItem("hp_receipt_footer") ?? "Təşəkkür edirik!" };
  } catch {
    return { paper: "80", footer: "Təşəkkür edirik!" };
  }
}

export function saveReceiptSettings(s: Partial<ReceiptSettings>) {
  try {
    if (s.paper) localStorage.setItem("hp_paper", s.paper);
    if (s.footer !== undefined) localStorage.setItem("hp_receipt_footer", s.footer);
  } catch { /* ignore */ }
}

/** Page CSS for the chosen paper width. */
export function paperCss(): string {
  const { paper } = receiptSettings();
  return paper === "58"
    ? "@page { size: 58mm auto; margin: 0 } body { width: 50mm; margin: 0 auto; padding: 3mm 0; font: 10.5px/1.3 'Segoe UI', Arial, sans-serif; color: #000 }"
    : "@page { size: 80mm auto; margin: 0 } body { width: 72mm; margin: 0 auto; padding: 4mm 0; font: 12px/1.35 'Segoe UI', Arial, sans-serif; color: #000 }";
}

export function receiptHtml(sale: Sale, store: { name: string | null }, opts: { change?: number; offline?: boolean } = {}): string {
  const rows = sale.items.map((i) => `
    <tr><td colspan="2">${esc(i.name)}</td></tr>
    <tr><td class="m">${Number(i.quantity)} × ${formatMoney(i.unitPrice)}${i.discount > 0 ? ` −${formatMoney(i.discount)}` : ""}</td>
        <td class="r">${formatMoney(i.lineTotal)}</td></tr>`).join("");
  const line = (label: string, v: number, cls = "") => `<tr class="${cls}"><td>${label}</td><td class="r">${formatMoney(v)}</td></tr>`;
  const when = new Date(sale.createdAt || Date.now()).toLocaleString("az-AZ");
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    ${paperCss()}
    h1 { font-size: 16px; text-align: center; margin: 0 0 2px } .c { text-align: center } .m { color: #333 }
    table { width: 100%; border-collapse: collapse } td { padding: 1px 0; vertical-align: top } .r { text-align: right; white-space: nowrap }
    hr { border: 0; border-top: 1px dashed #000; margin: 6px 0 } .big td { font-size: 15px; font-weight: 700 }
  </style></head><body>
    <h1>${esc(store.name ?? "HesabPro")}</h1>
    <div class="c">${sale.priceLevel === "WHOLESALE" ? "Topdan satış çeki" : "Satış çeki"} ${esc(sale.number)}</div>
    <div class="c m">${esc(when)}${sale.customerName ? ` · ${esc(sale.customerName)}` : ""}</div>
    ${opts.offline ? '<div class="c"><b>İnternetsiz satış — sonra göndəriləcək</b></div>' : ""}
    <hr><table>${rows}</table><hr>
    <table>
      ${sale.discount > 0 ? line("Endirim", -sale.discount) : ""}
      ${line("YEKUN", sale.total, "big")}
      ${sale.paidCash > 0 ? line("Nağd", sale.paidCash) : ""}
      ${sale.paidCard > 0 ? line("Kart", sale.paidCard) : ""}
      ${sale.creditAmount > 0 ? line("Borca", sale.creditAmount) : ""}
      ${opts.change && opts.change > 0 ? line("Qaytarılan", opts.change) : ""}
    </table>
    <hr>${receiptSettings().footer.trim() ? `<div class="c">${esc(receiptSettings().footer.trim())}</div>` : ""}<div class="c m">HesabPro</div>
  </body></html>`;
}
