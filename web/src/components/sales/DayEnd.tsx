"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Input, Modal, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { desktop, paperCss, receiptSettings, printOnTill } from "@/lib/desktop";
import { formatMoney, todayBaku } from "@/lib/format";

type Summary = {
  date: string; storeName: string; salesCount: number; salesTotal: number; paidCash: number; paidCard: number; credit: number;
  grossProfit: number; returnsCount: number; returnsTotal: number; expensesTotal: number; debtsCollected: number; supplierPaid: number;
  accounts: { name: string; kind: string; balance: number }[]; topProducts: { name: string; quantity: number; total: number }[];
};
type Cashier = { name: string; salesCount: number; salesTotal: number; cancelledCount: number; shiftShortage: number; shiftSurplus: number };
type Shift = { cashierName: string; openedAt: string; closedAt: string | null; expectedCash: number | null; countedCash: number | null; difference: number | null };

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
const hm = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString("az-AZ", { hour: "2-digit", minute: "2-digit" }) : "açıq");

/** Gün sonu çeki: the whole day of the store on receipt paper (for closing the till). */
export function dayEndHtml(s: Summary, cashiers: Cashier[], shifts: Shift[]): string {
  const d = receiptSettings();
  const row = (l: string, v: number, b = false) => `<tr${b ? ' class="b"' : ""}><td>${esc(l)}</td><td class="r">${formatMoney(v)}</td></tr>`;
  const day = s.date.split("-").reverse().join(".");
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    ${paperCss(d)}
    h1 { font-size: 1.3em; text-align: center; margin: 0 } .c { text-align: center } .m { color: #333 } h2 { font-size: 1em; margin: 6px 0 2px }
    table { width: 100%; border-collapse: collapse } td { padding: 1px 0; vertical-align: top } .r { text-align: right; white-space: nowrap }
    hr { border: 0; border-top: 1px dashed #000; margin: 6px 0 } .b td { font-weight: 800; font-size: 1.1em }
  </style></head><body>
    ${d.logo ? `<div class="c"><img src="${d.logo}" alt="" style="max-width:55%;max-height:18mm;filter:grayscale(1)"></div>` : ""}
    <h1>${esc(s.storeName)}</h1>
    <div class="c"><b>GÜN SONU ÇEKİ</b></div>
    <div class="c m">${esc(day)} · çap: ${esc(new Date().toLocaleString("az-AZ"))}</div><hr>
    <table>
      <tr><td>Satış sayı</td><td class="r">${s.salesCount}</td></tr>
      ${row("SATIŞ CƏMİ", s.salesTotal, true)}${row("Nağd", s.paidCash)}${row("Kart", s.paidCard)}${row("Nisyə (borca)", s.credit)}
      ${s.returnsCount > 0 ? row(`Qaytarma (${s.returnsCount})`, -s.returnsTotal) : ""}
      ${row("Mənfəət (təxmini)", s.grossProfit)}
    </table><hr>
    <table>
      ${row("Xərclər", s.expensesTotal > 0 ? -s.expensesTotal : 0)}${row("Yığılan borc", s.debtsCollected)}${s.supplierPaid > 0 ? row("Tədarükçüyə ödəniş", -s.supplierPaid) : ""}
    </table>
    ${s.accounts.length ? `<hr><h2>Kassa və hesablar (hazırda)</h2><table>${s.accounts.map((a) => row(a.name, a.balance)).join("")}</table>` : ""}
    ${cashiers.length ? `<hr><h2>Kassirlər</h2><table>${cashiers.map((c) => `<tr><td>${esc(c.name)} (${c.salesCount})</td><td class="r">${formatMoney(c.salesTotal)}</td></tr>`
      + (c.cancelledCount ? `<tr><td class="m">&nbsp;ləğv: ${c.cancelledCount}</td><td></td></tr>` : "")
      + (c.shiftShortage ? `<tr><td class="m">&nbsp;əskik</td><td class="r">−${formatMoney(c.shiftShortage)}</td></tr>` : "")
      + (c.shiftSurplus ? `<tr><td class="m">&nbsp;artıq</td><td class="r">+${formatMoney(c.shiftSurplus)}</td></tr>` : "")).join("")}</table>` : ""}
    ${shifts.length ? `<hr><h2>Növbələr</h2><table>${shifts.map((sh) => `<tr><td>${esc(sh.cashierName)} ${hm(sh.openedAt)}–${hm(sh.closedAt)}</td><td class="r">${
      sh.difference == null ? "açıq" : sh.difference === 0 ? "fərq yox" : (sh.difference > 0 ? "+" : "−") + formatMoney(Math.abs(sh.difference))}</td></tr>`).join("")}</table>` : ""}
    ${s.topProducts.length ? `<hr><h2>Ən çox satılan</h2><table>${s.topProducts.map((p, i) => `<tr><td>${i + 1}. ${esc(p.name)} × ${Number(p.quantity)}</td><td class="r">${formatMoney(p.total)}</td></tr>`).join("")}</table>` : ""}
    <hr><div class="c m">Kassir imzası: ____________</div><div class="c m">HesabPro</div>
  </body></html>`;
}

/** "Gün sonu çeki" window: pick the day, see it, print it on the receipt printer. */
export function DayEndModal({ onClose }: { onClose: () => void }) {
  const [date, setDate] = useState(todayBaku());
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    setHtml(null);
    setError(null);
    Promise.all([
      api<Summary>(`/api/reports/day-summary?date=${date}`),
      api<{ rows: (Cashier & { name: string })[] }>(`/api/reports/cashiers?from=${date}&to=${date}`).then((r) => r.rows.filter((c) => c.salesCount > 0 || c.cancelledCount > 0 || c.shiftShortage > 0 || c.shiftSurplus > 0)).catch(() => []),
      api<Shift[]>("/api/shifts?limit=100").then((l) => l.filter((s) => s.openedAt.slice(0, 10) === date || (s.closedAt ?? "").slice(0, 10) === date)).catch(() => []),
    ])
      .then(([s, c, sh]) => setHtml(dayEndHtml(s, c, sh)))
      .catch((e) => setError(e instanceof ApiError && e.status === 403
        ? "Gün sonu çeki üçün “Hesabatlar” icazəsi lazımdır. Mağaza sahibi bu icazəni verə bilər; öz növbənizin Z-hesabatı növbəni bağlayanda çıxır."
        : e instanceof ApiError ? e.message : "Hesabat yüklənmədi"));
  }, [date]);

  async function print() {
    if (!html) return;
    const app = desktop();
    if (app) {
      const problem = await printOnTill(app, html);
      setNote(problem ? `Çap alınmadı: ${problem}` : "Gün sonu çeki printerə göndərildi");
      return;
    }
    const frame = document.createElement("iframe");
    frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden";
    frame.srcdoc = html;
    frame.onload = () => setTimeout(() => { frame.contentWindow?.focus(); frame.contentWindow?.print(); }, 200);
    document.body.appendChild(frame);
    setTimeout(() => frame.remove(), 60_000);
  }

  return (
    <Modal open title="Gün sonu çeki" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Bağla</Button><Button variant="gold" disabled={!html} onClick={() => void print()}>🖨 Çap et</Button></>}>
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <Input type="date" value={date} max={todayBaku()} onChange={(e) => setDate(e.target.value)} className="w-auto" aria-label="Gün" />
        <span className="text-xs text-slate-500">Bütün kassirlər, kassa qalıqları və növbələr bir çekdə</span>
      </div>
      {note && <div className="mb-3"><Alert kind="success">{note}</Alert></div>}
      {error ? <Alert>{error}</Alert> : !html ? <Spinner /> : (
        <div className="flex justify-center rounded-2xl bg-slate-100 p-3">
          <iframe title="Gün sonu çeki" srcDoc={html} className="h-[460px] rounded-sm bg-white shadow-lg"
            style={{ width: receiptSettings().paper === "58" ? 230 : 310 }} />
        </div>
      )}
    </Modal>
  );
}
