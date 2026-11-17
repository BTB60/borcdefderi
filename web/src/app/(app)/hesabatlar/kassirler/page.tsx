"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Alert, Card, EmptyState, Field, Input, PageHeader, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { JOB_TITLE_LABEL, formatMoney, todayBaku } from "@/lib/format";
import type { JobTitle } from "@/lib/types";

type Row = {
  userId: string; name: string; jobTitle: JobTitle | "OWNER" | null; active: boolean;
  salesCount: number; salesTotal: number; paidCash: number; paidCard: number; credit: number; discount: number; averageSale: number;
  returnsCount: number; returnsTotal: number; cancelledCount: number; cancelledTotal: number;
  shiftsClosed: number; shiftShortage: number; shiftSurplus: number;
};

/** Kassir hesabatı: what each cashier sold, gave as discount, returned, cancelled and how their shifts closed. */
export default function CashierReportPage() {
  const today = todayBaku();
  const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
  const [to, setTo] = useState(today);
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setRows(null);
    setError(null);
    api<{ rows: Row[] }>(`/api/reports/cashiers?from=${from}&to=${to}`)
      .then((r) => setRows(r.rows))
      .catch((e) => { setRows([]); setError(e instanceof ApiError ? e.message : "Hesabat yüklənmədi"); });
  }, [from, to]);

  const all = rows ?? [];
  const sum = (k: keyof Row) => all.reduce((a, r) => a + Number(r[k] ?? 0), 0);
  const role = (r: Row) => (r.jobTitle === "OWNER" ? "Sahib" : r.jobTitle ? JOB_TITLE_LABEL[r.jobTitle] : "İşçi");

  return (
    <>
      <PageHeader
        title="Kassir hesabatı"
        subtitle="Hər işçinin satışı, endirimi, qaytarması, ləğv etdiyi satışlar və növbə fərqləri"
        actions={<Link href="/hesabatlar" className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:border-slate-400">← Hesabatlar</Link>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <Card className="mb-5">
        <div className="grid gap-4 sm:grid-cols-[200px_200px_1fr] sm:items-end">
          <Field label="Başlanğıc"><Input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="Son"><Input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></Field>
          <div className="flex flex-wrap gap-1.5">
            {[["Bu gün", today, today], ["Bu ay", `${today.slice(0, 8)}01`, today]].map(([l, a, b]) => (
              <button key={l} onClick={() => { setFrom(a); setTo(b); }} className="rounded-full bg-slate-100 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-200">{l}</button>
            ))}
          </div>
        </div>
      </Card>
      <section className="mb-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Satış" value={formatMoney(sum("salesTotal"))} tone="navy" hint={`${sum("salesCount")} çek`} />
        <StatCard label="Verilən endirim" value={formatMoney(sum("discount"))} tone="gold" />
        <StatCard label="Ləğv edilən satış" value={formatMoney(sum("cancelledTotal"))} tone="rose" hint={`${sum("cancelledCount")} satış`} />
        <StatCard label="Növbə əskiyi" value={formatMoney(sum("shiftShortage"))} tone="rose" hint={`Artıq: ${formatMoney(sum("shiftSurplus"))}`} />
      </section>
      <Card>
        {!rows ? <Spinner /> : all.length === 0 ? <EmptyState title="Bu dövrdə məlumat yoxdur" /> : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr>
                <Th>İşçi</Th><Th className="text-right">Satış</Th><Th className="text-right">Nağd / Kart / Nisyə</Th>
                <Th className="text-right">Orta çek</Th><Th className="text-right">Endirim</Th><Th className="text-right">Qaytarma</Th>
                <Th className="text-right">Ləğv</Th><Th className="text-right">Növbə fərqi</Th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {all.map((r) => (
                <tr key={r.userId} className={r.cancelledCount > 0 || r.shiftShortage > 0 ? "bg-rose-50/40" : ""}>
                  <Td><div className="font-medium text-navy-800">{r.name}</div><div className="text-xs text-slate-400">{role(r)}{!r.active ? " · deaktiv" : ""}</div></Td>
                  <Td className="text-right"><div className="font-semibold tabular-nums">{formatMoney(r.salesTotal)}</div><div className="text-xs text-slate-400">{r.salesCount} çek</div></Td>
                  <Td className="text-right text-xs tabular-nums text-slate-600">{formatMoney(r.paidCash)}<br />{formatMoney(r.paidCard)}<br />{formatMoney(r.credit)}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(r.averageSale)}</Td>
                  <Td className="text-right tabular-nums">{r.discount > 0 ? formatMoney(r.discount) : "—"}</Td>
                  <Td className="text-right tabular-nums">{r.returnsCount > 0 ? <>{formatMoney(r.returnsTotal)}<div className="text-xs text-slate-400">{r.returnsCount} dəfə</div></> : "—"}</Td>
                  <Td className="text-right tabular-nums">{r.cancelledCount > 0 ? <span className="font-semibold text-rose-600">{formatMoney(r.cancelledTotal)}<div className="text-xs font-normal">{r.cancelledCount} satış</div></span> : "—"}</Td>
                  <Td className="text-right tabular-nums">
                    {r.shiftsClosed === 0 ? <span className="text-slate-300">—</span> : (
                      <>
                        {r.shiftShortage > 0 && <div className="font-semibold text-rose-600">−{formatMoney(r.shiftShortage)}</div>}
                        {r.shiftSurplus > 0 && <div className="text-amber-700">+{formatMoney(r.shiftSurplus)}</div>}
                        {r.shiftShortage === 0 && r.shiftSurplus === 0 && <div className="text-emerald-700">Fərq yoxdur</div>}
                        <div className="text-xs text-slate-400">{r.shiftsClosed} növbə</div>
                      </>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
