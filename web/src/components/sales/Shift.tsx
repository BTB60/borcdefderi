"use client";

import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { desktop, paperCss } from "@/lib/desktop";
import { formatDateTime, formatMoney } from "@/lib/format";

export type ShiftReport = {
  id: string; userId: string; cashierName: string; openedAt: string; closedAt: string | null; openingCash: number;
  salesCount: number; salesTotal: number; paidCash: number; paidCard: number; credit: number; discounts: number;
  returnsCount: number; returnsTotal: number; refunded: number; cashByKind: Record<string, number>; cashIn: number; cashOut: number;
  expectedCash: number; countedCash: number | null; difference: number | null; note: string | null; open: boolean;
};

const KIND: Record<string, string> = {
  SALE: "Satış (nağd)", SALE_REFUND: "Qaytarılan pul", DEBT_COLLECTION: "Borc ödənişi", SUPPLIER_PAYMENT: "Təchizatçıya ödəniş",
  EXPENSE: "Xərc", DEPOSIT: "Mədaxil", WITHDRAWAL: "Məxaric", OPENING: "Açılış", TRANSFER_IN: "Köçürmə (daxil)",
  TRANSFER_OUT: "Köçürmə (çıxış)", PURCHASE: "Mal alışı", COUNT_ADJUSTMENT: "Sayım fərqi", PAYMENT_REVERSAL: "Ödəniş ləğvi",
  EXPENSE_REVERSAL: "Xərc qaytarıldı",
};

const num = (s: string) => (s.trim() === "" ? NaN : Number(s.replace(",", ".")));
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

/** Z-report as an 80 mm page (printed silently by the desktop app, with the print dialog in a browser). */
export function zReportHtml(r: ShiftReport, storeName: string | null): string {
  const row = (l: string, v: number | null, b = false) => v == null ? "" : `<tr${b ? ' class="b"' : ""}><td>${esc(l)}</td><td class="r">${formatMoney(v)}</td></tr>`;
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    ${paperCss()}
    h1 { font-size: 15px; text-align: center; margin: 0 } .c { text-align: center } table { width: 100%; border-collapse: collapse }
    td { padding: 1px 0 } .r { text-align: right } hr { border: 0; border-top: 1px dashed #000; margin: 6px 0 } .b td { font-weight: 700; font-size: 13px }
  </style></head><body>
    <h1>${esc(storeName ?? "HesabPro")}</h1><div class="c"><b>Z-HESABAT</b></div>
    <div class="c">${esc(r.cashierName)}</div>
    <div class="c">${esc(formatDateTime(r.openedAt))} — ${r.closedAt ? esc(formatDateTime(r.closedAt)) : "açıq"}</div><hr>
    <table>${row("Satış sayı", null)}<tr><td>Satış sayı</td><td class="r">${r.salesCount}</td></tr>
      ${row("Satış cəmi", r.salesTotal, true)}${row("Nağd", r.paidCash)}${row("Kart", r.paidCard)}${row("Borca", r.credit)}${row("Endirimlər", r.discounts)}
    </table><hr><table><tr><td>Qaytarma sayı</td><td class="r">${r.returnsCount}</td></tr>${row("Qaytarma cəmi", r.returnsTotal)}${row("Qaytarılan pul", r.refunded)}</table><hr>
    <table>${row("Açılışda kassada", r.openingCash)}${Object.entries(r.cashByKind).map(([k, v]) => row(KIND[k] ?? k, v)).join("")}
      ${row("Olmalı idi", r.expectedCash, true)}${row("Sayıldı", r.countedCash, true)}${row("Fərq", r.difference, true)}</table>
    ${r.note ? `<hr><div>${esc(r.note)}</div>` : ""}<hr><div class="c">HesabPro</div></body></html>`;
}

export function printZReport(r: ShiftReport, storeName: string | null) {
  const html = zReportHtml(r, storeName);
  const d = desktop();
  if (d) { void d.printHtml(html); return; }
  const frame = document.createElement("iframe");
  frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden";
  frame.srcdoc = html;
  frame.onload = () => setTimeout(() => { frame.contentWindow?.focus(); frame.contentWindow?.print(); }, 200);
  document.body.appendChild(frame);
  setTimeout(() => frame.remove(), 60_000);
}

function ZReport({ r }: { r: ShiftReport }) {
  const line = (l: string, v: number | null | undefined, cls = "") =>
    v == null ? null : <div className={`flex justify-between py-0.5 ${cls}`}><span className="text-slate-600">{l}</span><span className="tabular-nums">{formatMoney(v)}</span></div>;
  return (
    <div className="space-y-3 text-sm">
      <p className="text-slate-500">{r.cashierName} · {formatDateTime(r.openedAt)} — {r.closedAt ? formatDateTime(r.closedAt) : "açıq"}</p>
      <div className="rounded-xl bg-slate-50 p-3">
        <div className="flex justify-between py-0.5"><span className="text-slate-600">Satış sayı</span><span>{r.salesCount}</span></div>
        {line("Satış cəmi", r.salesTotal, "font-semibold text-navy-800")}{line("Nağd", r.paidCash)}{line("Kart", r.paidCard)}{line("Borca", r.credit)}
        {r.discounts > 0 && line("Endirimlər", r.discounts)}
        {r.returnsCount > 0 && <>{line(`Qaytarma (${r.returnsCount})`, r.returnsTotal, "text-rose-700")}{line("Qaytarılan pul", r.refunded)}</>}
      </div>
      <div className="rounded-xl bg-slate-50 p-3">
        {line("Açılışda kassada", r.openingCash)}
        {Object.entries(r.cashByKind).map(([k, v]) => <div key={k}>{line(KIND[k] ?? k, v)}</div>)}
        {line("Kassada olmalıdır", r.expectedCash, "border-t border-slate-200 pt-1 font-semibold text-navy-800")}
        {line("Sayıldı", r.countedCash, "font-semibold")}
        {r.difference != null && (
          <div className={`flex justify-between py-0.5 font-semibold ${r.difference < 0 ? "text-rose-700" : r.difference > 0 ? "text-amber-700" : "text-emerald-700"}`}>
            <span>{r.difference < 0 ? "Əskik" : r.difference > 0 ? "Artıq" : "Fərq yoxdur"}</span><span className="tabular-nums">{formatMoney(r.difference)}</span>
          </div>
        )}
      </div>
      {r.note && <p className="text-slate-600">{r.note}</p>}
    </div>
  );
}

/** Bar above the till: open a shift with the drawer cash, see the running totals, close it with the counted cash. */
export function ShiftBar() {
  const { me } = useAuth();
  const [shift, setShift] = useState<ShiftReport | null | undefined>(undefined);
  const [mode, setMode] = useState<"open" | "close" | "z" | null>(null);
  const [cash, setCash] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [closed, setClosed] = useState<ShiftReport | null>(null);

  const load = useCallback(() => {
    api<ShiftReport | undefined>("/api/shifts/current").then((r) => setShift(r ?? null)).catch(() => setShift(null));
  }, []);
  useEffect(load, [load]);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      if (mode === "open") {
        setShift(await api<ShiftReport>("/api/shifts/open", { method: "POST", json: { openingCash: num(cash) || 0 } }));
        setMode(null);
      } else if (mode === "close" && shift) {
        const v = num(cash);
        if (Number.isNaN(v)) { setError("Kassadakı pulu sayıb yazın"); setBusy(false); return; }
        const z = await api<ShiftReport>(`/api/shifts/${shift.id}/close`, { method: "POST", json: { countedCash: v, note: note || null } });
        setClosed(z);
        setShift(null);
        setMode("z");
        printZReport(z, me?.storeName ?? null);
      }
      setCash(""); setNote("");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }

  if (shift === undefined) return null;
  const counted = num(cash);
  return (
    <>
      <div className={`mb-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl px-4 py-2.5 text-sm ring-1 ${shift ? "bg-emerald-50 ring-emerald-200" : "bg-amber-50 ring-amber-200"}`}>
        {shift ? (
          <span className="text-emerald-900">
            <b>Növbə açıqdır</b> · {formatDateTime(shift.openedAt)} · {shift.salesCount} satış · {formatMoney(shift.salesTotal)} · kassada olmalı: <b>{formatMoney(shift.expectedCash)}</b>
          </span>
        ) : (
          <span className="text-amber-900"><b>Növbə açılmayıb.</b> Gün sonu Z-hesabatı üçün işə başlayanda növbəni açın.</span>
        )}
        <div className="flex gap-2">
          {shift && <Button variant="ghost" className="px-3 py-1.5" onClick={() => { load(); setClosed(shift); setMode("z"); }}>X-hesabat</Button>}
          <Button variant={shift ? "outline" : "gold"} className="px-3 py-1.5" onClick={() => { setError(null); setCash(""); setMode(shift ? "close" : "open"); }}>
            {shift ? "Növbəni bağla" : "Növbəni aç"}
          </Button>
        </div>
      </div>

      {(mode === "open" || mode === "close") && (
        <Modal open title={mode === "open" ? "Növbəni aç" : "Növbəni bağla"} onClose={() => setMode(null)}
          footer={<><Button variant="ghost" onClick={() => setMode(null)}>Ləğv et</Button><Button variant="gold" disabled={busy} onClick={submit}>{mode === "open" ? "Aç" : "Bağla və Z-hesabatı çap et"}</Button></>}>
          {error && <div className="mb-3"><Alert>{error}</Alert></div>}
          {mode === "close" && shift && (
            <p className="mb-3 rounded-xl bg-slate-50 px-3 py-2 text-sm">Kassada olmalıdır: <b className="tabular-nums">{formatMoney(shift.expectedCash)}</b></p>
          )}
          <Field label={mode === "open" ? "Kassada olan pul (açılış)" : "Kassadakı pulu sayın"}>
            <Input autoFocus inputMode="decimal" value={cash} onChange={(e) => setCash(e.target.value)} placeholder="0.00"
              onKeyDown={(e) => e.key === "Enter" && submit()} />
          </Field>
          {mode === "close" && shift && !Number.isNaN(counted) && (
            <p className={`mt-2 text-sm font-semibold ${counted - shift.expectedCash < 0 ? "text-rose-700" : counted - shift.expectedCash > 0 ? "text-amber-700" : "text-emerald-700"}`}>
              Fərq: {formatMoney(Math.round((counted - shift.expectedCash) * 100) / 100)}
            </p>
          )}
          {mode === "close" && <div className="mt-3"><Field label="Qeyd (istəyə bağlı)"><Input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} /></Field></div>}
        </Modal>
      )}
      {mode === "z" && closed && (
        <Modal open title={closed.open ? "X-hesabat (növbə davam edir)" : "Z-hesabat"} onClose={() => setMode(null)}
          footer={<><Button variant="outline" onClick={() => printZReport(closed, me?.storeName ?? null)}>Çap et</Button><Button onClick={() => setMode(null)}>Bağla</Button></>}>
          <ZReport r={closed} />
        </Modal>
      )}
    </>
  );
}

/** "Növbələr" tab: past shifts with their Z-reports (owner sees every cashier). */
export function ShiftList() {
  const { me } = useAuth();
  const [items, setItems] = useState<ShiftReport[] | null>(null);
  const [open, setOpen] = useState<ShiftReport | null>(null);
  useEffect(() => { api<ShiftReport[]>("/api/shifts?limit=100").then(setItems).catch(() => setItems([])); }, []);
  return (
    <Card>
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Hələ növbə yoxdur" /> : (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Kassir</Th><Th>Açıldı</Th><Th>Bağlandı</Th><Th className="text-right">Satış</Th><Th className="text-right">Olmalı</Th><Th className="text-right">Sayıldı</Th><Th className="text-right">Fərq</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((s) => (
              <tr key={s.id} className="cursor-pointer hover:bg-slate-50" onClick={() => setOpen(s)}>
                <Td className="font-medium text-navy-800">{s.cashierName}</Td>
                <Td className="text-xs">{formatDateTime(s.openedAt)}</Td>
                <Td className="text-xs">{s.closedAt ? formatDateTime(s.closedAt) : <Badge tone="emerald">açıq</Badge>}</Td>
                <Td className="text-right tabular-nums">{s.salesCount} · {formatMoney(s.salesTotal)}</Td>
                <Td className="text-right tabular-nums">{formatMoney(s.expectedCash)}</Td>
                <Td className="text-right tabular-nums">{s.countedCash == null ? "—" : formatMoney(s.countedCash)}</Td>
                <Td className={`text-right font-semibold tabular-nums ${s.difference == null ? "" : s.difference < 0 ? "text-rose-700" : s.difference > 0 ? "text-amber-700" : "text-emerald-700"}`}>
                  {s.difference == null ? "—" : formatMoney(s.difference)}
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      {open && (
        <Modal open title={open.open ? "X-hesabat" : "Z-hesabat"} onClose={() => setOpen(null)}
          footer={<><Button variant="outline" onClick={() => printZReport(open, me?.storeName ?? null)}>Çap et</Button><Button onClick={() => setOpen(null)}>Bağla</Button></>}>
          <ZReport r={open} />
        </Modal>
      )}
    </Card>
  );
}
