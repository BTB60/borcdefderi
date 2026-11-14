"use client";

import { useEffect, useState } from "react";
import { Alert, Card, EmptyState, Input, PageHeader, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatMoney, todayBaku } from "@/lib/format";
import type { MonthPoint, Pnl } from "@/lib/types";

interface ProductProfit { productId: string; name: string; unit: string | null; quantity: number; revenue: number; cost: number; profit: number; marginPct: number }

function delta(now: number, before: number) {
  const d = now - before;
  const pct = before !== 0 ? Math.round((d / Math.abs(before)) * 100) : null;
  return `${d >= 0 ? "+" : "−"}${formatMoney(Math.abs(d))}${pct !== null ? ` (${d >= 0 ? "+" : ""}${pct}%)` : ""}`;
}

/** Gəlir və mənfəət: profit and loss from correctly classified figures, monthly dynamics. */
export default function FinancePage() {
  const { isOwner, can } = useAuth();
  const today = todayBaku();
  const [range, setRange] = useState({ from: today.slice(0, 8) + "01", to: today });
  const [pnl, setPnl] = useState<Pnl | null>(null);
  const [months, setMonths] = useState<MonthPoint[] | null>(null);
  const [products, setProducts] = useState<ProductProfit[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOwner && !can("REPORTS")) return;
    api<Pnl>(`/api/finance/pnl?from=${range.from}&to=${range.to}`).then(setPnl).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
    api<ProductProfit[]>(`/api/finance/product-profit?from=${range.from}&to=${range.to}&limit=200`).then(setProducts).catch(() => setProducts([]));
  }, [range, isOwner, can]);
  useEffect(() => {
    if (!isOwner && !can("REPORTS")) return;
    api<MonthPoint[]>("/api/finance/monthly?months=12").then(setMonths).catch(() => setMonths([]));
  }, [isOwner, can]);

  if (!isOwner && !can("REPORTS")) return <Card><EmptyState title="Bu bölmə üçün “Hesabatlar” icazəsi lazımdır" /></Card>;
  const max = Math.max(1, ...(months ?? []).flatMap((m) => [m.netSales, m.expenses + m.costOfGoods, Math.abs(m.netResult)]));

  return (
    <>
      <PageHeader title="Gəlir və mənfəət" subtitle="Yalnız satış gəliri və faktiki xərclər sayılır — köhnə borcun yığılması, köçürmə və kassaya pul qoyulması mənfəət deyil" />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <div className="mb-5 flex flex-wrap gap-3">
        <Input type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} aria-label="Başlanğıc" className="w-auto" />
        <Input type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} aria-label="Son" className="w-auto" />
        {[["Bu ay", today.slice(0, 8) + "01", today], ["Bu il", today.slice(0, 5) + "01-01", today]].map(([l, f, t]) => (
          <button key={l} type="button" onClick={() => setRange({ from: f, to: t })} className="rounded-full bg-slate-100 px-3 py-1.5 text-xs font-medium">{l}</button>
        ))}
      </div>
      {!pnl ? <Spinner /> : (
        <>
          <section className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
            <StatCard label="Xalis satış" value={formatMoney(pnl.netSales)} tone="emerald" hint={`${pnl.saleCount} satış · qaytarma ${formatMoney(pnl.returns)}`} />
            <StatCard label="Maya dəyəri" value={formatMoney(pnl.costOfGoods)} tone="sky" hint="satılan malların alış qiyməti" />
            <StatCard label="Məhsul mənfəəti" value={formatMoney(pnl.grossProfit)} tone={pnl.grossProfit >= 0 ? "navy" : "rose"}
              hint={`satış − maya dəyəri${pnl.netSales ? ` · marja ${Math.round((pnl.grossProfit / pnl.netSales) * 100)}%` : ""}`} />
            <StatCard label="Xərclər" value={formatMoney(pnl.expenses)} tone="rose" />
            <StatCard label="Xalis nəticə" value={formatMoney(pnl.netResult)} tone={pnl.netResult >= 0 ? "gold" : "rose"} hint="məhsul mənfəəti − xərclər" />
          </section>
          <div className="mb-6 grid gap-5 lg:grid-cols-2">
            <Card>
              <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">Mənfəət və zərər</h2>
              <dl className="space-y-2 text-sm">
                {[
                  ["Satış", pnl.sales], ["− Qaytarmalar", -pnl.returns], ["= Xalis satış", pnl.netSales], ["− Maya dəyəri", -pnl.costOfGoods],
                  ["= Məhsul mənfəəti", pnl.grossProfit], ["− Xərclər", -pnl.expenses], ["= Xalis nəticə", pnl.netResult],
                ].map(([l, v]) => (
                  <div key={l as string} className={`flex justify-between ${(l as string).startsWith("=") ? "border-t border-slate-200 pt-2 font-semibold text-navy-800" : ""}`}>
                    <dt>{l}</dt><dd className="tabular-nums">{formatMoney(v as number)}</dd>
                  </div>
                ))}
              </dl>
              <p className="mt-4 text-xs text-slate-500">
                Əvvəlki dövr ({formatDate(pnl.previous.from)} – {formatDate(pnl.previous.to)}): satış {delta(pnl.netSales, pnl.previous.netSales)},
                xərc {delta(pnl.expenses, pnl.previous.expenses)}, xalis nəticə {delta(pnl.netResult, pnl.previous.netResult)}.
              </p>
            </Card>
            <Card>
              <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">Pul axını (mənfəətə daxil deyil)</h2>
              <dl className="space-y-2 text-sm">
                <div className="flex justify-between"><dt>Satışda dərhal ödənilən</dt><dd className="tabular-nums">{formatMoney(pnl.salesPaidNow)}</dd></div>
                <div className="flex justify-between"><dt>Satışda borca yazılan</dt><dd className="tabular-nums">{formatMoney(pnl.salesOnCredit)}</dd></div>
                <div className="flex justify-between"><dt>Müştərilərdən borc yığılması</dt><dd className="tabular-nums">{formatMoney(pnl.debtCollections)}</dd></div>
                <div className="flex justify-between"><dt>Şirkətlərə borc ödənişi</dt><dd className="tabular-nums">{formatMoney(pnl.supplierPayments)}</dd></div>
              </dl>
              <p className="mt-4 text-xs text-slate-500">Kreditlə satış gəlir kimi satış günü bir dəfə sayılır; müştəri borcu sonra ödəyəndə bu, gəlir deyil, borcun yığılmasıdır.</p>
            </Card>
          </div>
          <Card className="mb-6">
            <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Məhsullar üzrə mənfəət</h2>
            <p className="mb-4 text-xs text-slate-500">Seçilmiş dövrdə satılan hər məhsul: satış − maya dəyəri (qaytarılan miqdar çıxılıb, ləğv edilmiş satışlar sayılmır). Ən çox mənfəət gətirən yuxarıda.</p>
            {!products ? <Spinner /> : products.length === 0 ? <EmptyState title="Bu dövrdə satılan məhsul yoxdur" /> : (
              <Table>
                <thead className="bg-slate-50/70"><tr>
                  <Th>Məhsul</Th><Th className="text-right">Satılıb</Th><Th className="text-right">Satış</Th><Th className="text-right">Maya dəyəri</Th>
                  <Th className="text-right">Mənfəət</Th><Th className="text-right">Marja</Th>
                </tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {products.map((p) => (
                    <tr key={p.productId}>
                      <Td className="font-medium text-navy-800">{p.name}</Td>
                      <Td className="text-right tabular-nums">{p.quantity} {p.unit ?? ""}</Td>
                      <Td className="text-right tabular-nums">{formatMoney(p.revenue)}</Td>
                      <Td className="text-right tabular-nums text-slate-500">{formatMoney(p.cost)}</Td>
                      <Td className={`text-right font-semibold tabular-nums ${p.profit < 0 ? "text-rose-600" : "text-emerald-700"}`}>{formatMoney(p.profit)}</Td>
                      <Td className={`text-right tabular-nums ${p.marginPct < 0 ? "text-rose-600" : ""}`}>{p.marginPct}%</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>
        </>
      )}
      <Card>
        <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Aylıq dinamika (12 ay)</h2>
        {!months ? <Spinner /> : (
          <div className="space-y-3">
            {months.map((m) => (
              <div key={m.month} className="grid grid-cols-[4rem_1fr_7rem] items-center gap-3 text-xs">
                <span className="text-slate-500">{m.month.slice(5)}/{m.month.slice(2, 4)}</span>
                <div className="space-y-1">
                  <div className="h-2 rounded-full bg-emerald-500" style={{ width: `${(m.netSales / max) * 100}%` }} title={`Satış ${formatMoney(m.netSales)}`} />
                  <div className="h-2 rounded-full bg-rose-400" style={{ width: `${((m.expenses + m.costOfGoods) / max) * 100}%` }} title={`Maya + xərc ${formatMoney(m.expenses + m.costOfGoods)}`} />
                </div>
                <span className={`text-right font-semibold tabular-nums ${m.netResult >= 0 ? "text-emerald-700" : "text-rose-600"}`}>{formatMoney(m.netResult)}</span>
              </div>
            ))}
            <p className="text-xs text-slate-400"><span className="text-emerald-600">■</span> xalis satış · <span className="text-rose-500">■</span> maya dəyəri + xərclər · sağda xalis nəticə</p>
          </div>
        )}
      </Card>
    </>
  );
}
