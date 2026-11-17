"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Alert, Badge, Card, EmptyState, PageHeader, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatMoney } from "@/lib/format";

type Batch = {
  purchaseItemId: string; productId: string; productName: string; productCode: string; unit: string; purchaseNumber: string;
  expiryDate: string; daysLeft: number; quantityLeft: number; costValue: number; saleValue: number;
};

const RANGES = [7, 30, 90];

/** Son istifadə tarixi: goods on the shelf that have expired or expire soon, oldest first. */
export default function ExpiryPage() {
  const [days, setDays] = useState(30);
  const [rows, setRows] = useState<Batch[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setRows(null);
    api<Batch[]>(`/api/inventory/expiring?days=${days}`).then(setRows).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [days]);

  const expired = (rows ?? []).filter((r) => r.daysLeft < 0);
  const soon = (rows ?? []).filter((r) => r.daysLeft >= 0);
  const sum = (l: Batch[]) => l.reduce((a, r) => a + r.costValue, 0);

  return (
    <>
      <PageHeader
        title="Son istifadə tarixi"
        subtitle="Rəfdə olan və vaxtı keçən / keçməkdə olan mallar. Tarix mal qəbulunda yazılır."
        actions={<Link href="/anbar" className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:border-slate-400">← Anbar</Link>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <section className="mb-6 grid gap-4 sm:grid-cols-3">
        <StatCard label="Vaxtı keçib" value={String(expired.length)} tone="rose" hint={`Maya dəyəri ${formatMoney(sum(expired))}`} />
        <StatCard label={`${days} gün ərzində bitir`} value={String(soon.length)} tone="gold" hint={`Maya dəyəri ${formatMoney(sum(soon))}`} />
        <StatCard label="Məsləhət" value="Endirim" tone="navy" hint="Tarixi yaxınlaşan malı endirimə qoyun — etiketdə xətli qiymət çıxır" />
      </section>
      <Card>
        <div className="mb-4 flex flex-wrap gap-1.5" role="group" aria-label="Müddət">
          {RANGES.map((d) => (
            <button key={d} onClick={() => setDays(d)} aria-pressed={days === d}
              className={`rounded-full px-3 py-1.5 text-xs font-medium transition ${days === d ? "bg-navy-800 text-white" : "bg-slate-100 text-slate-600 hover:bg-slate-200"}`}>
              {d} gün
            </button>
          ))}
        </div>
        {!rows ? <Spinner /> : rows.length === 0 ? (
          <EmptyState title="Vaxtı keçən mal yoxdur" text={`Növbəti ${days} gündə heç bir partiyanın vaxtı bitmir. Mal qəbulunda son istifadə tarixini yazmağı unutmayın.`} />
        ) : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Məhsul</Th><Th>Partiya</Th><Th>Son tarix</Th><Th className="text-right">Rəfdə</Th><Th className="text-right">Maya dəyəri</Th><Th className="text-right">Satış dəyəri</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <tr key={r.purchaseItemId} className={r.daysLeft < 0 ? "bg-rose-50" : r.daysLeft <= 7 ? "bg-amber-50/60" : ""}>
                  <Td><div className="font-medium text-navy-800">{r.productName}</div><div className="text-xs text-slate-400">{r.productCode}</div></Td>
                  <Td className="text-xs text-slate-500">{r.purchaseNumber}</Td>
                  <Td>
                    <div>{formatDate(r.expiryDate)}</div>
                    {r.daysLeft < 0 ? <Badge tone="rose">{-r.daysLeft} gün keçib</Badge>
                      : r.daysLeft === 0 ? <Badge tone="rose">Bu gün bitir</Badge>
                        : <Badge tone={r.daysLeft <= 7 ? "amber" : "slate"}>{r.daysLeft} gün qalıb</Badge>}
                  </Td>
                  <Td className="text-right tabular-nums">{Number(r.quantityLeft)} {r.unit}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(r.costValue)}</Td>
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
