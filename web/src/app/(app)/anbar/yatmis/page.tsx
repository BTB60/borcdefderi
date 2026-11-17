"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Alert, Card, EmptyState, PageHeader, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatMoney } from "@/lib/format";

type Idle = {
  productId: string; name: string; code: string; unit: string; stockQty: number; lastSoldOn: string | null;
  lastReceivedOn: string | null; idleDays: number; costValue: number; saleValue: number;
};

const RANGES = [30, 60, 90, 180];

/** Yatmış mallar: goods in stock that have not sold for a long time, with the money frozen in them. */
export default function IdleGoodsPage() {
  const [days, setDays] = useState(60);
  const [rows, setRows] = useState<Idle[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setRows(null);
    api<Idle[]>(`/api/inventory/idle?days=${days}`).then(setRows).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [days]);

  const all = rows ?? [];
  const cost = all.reduce((a, r) => a + r.costValue, 0);
  const sale = all.reduce((a, r) => a + r.saleValue, 0);

  return (
    <>
      <PageHeader
        title="Yatmış mallar"
        subtitle="Anbarda olan, amma uzun müddətdir satılmayan mallar və onlarda donmuş pul"
        actions={<Link href="/anbar" className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:border-slate-400">← Anbar</Link>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <section className="mb-6 grid gap-4 sm:grid-cols-3">
        <StatCard label="Yatmış məhsul" value={String(all.length)} tone="navy" hint={`${days}+ gündür satılmır`} />
        <StatCard label="Donmuş pul (maya)" value={formatMoney(cost)} tone="rose" />
        <StatCard label="Satış dəyəri" value={formatMoney(sale)} tone="gold" hint="Endirimlə satıb pulu dövriyyəyə qaytarın" />
      </section>
      <Card>
        <div className="mb-4 flex flex-wrap gap-1.5" role="group" aria-label="Müddət">
          {RANGES.map((d) => (
            <button key={d} onClick={() => setDays(d)} aria-pressed={days === d}
              className={`rounded-full px-3 py-1.5 text-xs font-medium transition ${days === d ? "bg-navy-800 text-white" : "bg-slate-100 text-slate-600 hover:bg-slate-200"}`}>
              {d}+ gün
            </button>
          ))}
        </div>
        {!rows ? <Spinner /> : all.length === 0 ? <EmptyState title="Yatmış mal yoxdur" text={`Son ${days} gündə anbardakı bütün mallar satılıb.`} /> : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Məhsul</Th><Th className="text-right">Qalıq</Th><Th>Son satış</Th><Th>Son qəbul</Th><Th className="text-right">Satılmır</Th><Th className="text-right">Maya dəyəri</Th><Th className="text-right">Satış dəyəri</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {all.map((r) => (
                <tr key={r.productId}>
                  <Td><div className="font-medium text-navy-800">{r.name}</div><div className="text-xs text-slate-400">{r.code}</div></Td>
                  <Td className="text-right tabular-nums">{Number(r.stockQty)} {r.unit}</Td>
                  <Td>{r.lastSoldOn ? formatDate(r.lastSoldOn) : <span className="text-rose-600">Heç satılmayıb</span>}</Td>
                  <Td>{r.lastReceivedOn ? formatDate(r.lastReceivedOn) : "—"}</Td>
                  <Td className="text-right tabular-nums">{r.idleDays} gün</Td>
                  <Td className="text-right font-semibold tabular-nums">{formatMoney(r.costValue)}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(r.saleValue)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
