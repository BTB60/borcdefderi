"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { FinanceOverview } from "@/lib/types";

function Tile({ href, label, value, hint, tone }: { href: string; label: string; value: string; hint?: string; tone: string }) {
  return (
    <Link href={href} className={`rounded-2xl border border-slate-200/70 bg-white p-4 shadow-card transition-all hover:-translate-y-0.5 hover:shadow-lg border-l-4 ${tone}`}>
      <span className="block text-xs font-medium uppercase tracking-wider text-slate-500">{label}</span>
      <span className="mt-1 block font-serif text-xl font-semibold tabular-nums text-navy-800">{value}</span>
      {hint && <span className="block text-xs text-slate-400">{hint}</span>}
    </Link>
  );
}

/**
 * Money figures from /api/cash/overview: balances by account kind, money in/out (transfers excluded) and expenses.
 * Only shown for users with the CASH / EXPENSES permissions (the API returns no value otherwise).
 */
export function MoneyCards() {
  const [o, setO] = useState<FinanceOverview | null>(null);
  useEffect(() => {
    api<FinanceOverview>("/api/cash/overview").then(setO).catch(() => setO(null));
  }, []);
  if (!o) return null;
  const hasCash = o.cashBalance !== undefined;
  const hasExp = o.monthExpenses !== undefined;
  if (!hasCash && !hasExp) return null;
  const diff = hasExp && o.previousMonthExpenses !== undefined ? (o.monthExpenses ?? 0) - (o.previousMonthExpenses ?? 0) : null;

  return (
    <section className="mb-8" aria-label="Kassa və xərclər">
      {hasExp && ((o.budgetsOver ?? 0) > 0 || (o.budgetsNear ?? 0) > 0 || (o.recurringDue ?? 0) > 0) && (
        <div className="mb-3 flex flex-wrap gap-2 text-sm">
          {(o.budgetsOver ?? 0) > 0 && <Link href="/xercler" className="rounded-xl bg-rose-50 px-3 py-2 font-medium text-rose-700 ring-1 ring-rose-200">{o.budgetsOver} kateqoriyada büdcə limiti keçilib</Link>}
          {(o.budgetsNear ?? 0) > 0 && <Link href="/xercler" className="rounded-xl bg-amber-50 px-3 py-2 font-medium text-amber-700 ring-1 ring-amber-200">{o.budgetsNear} kateqoriyada büdcə limitinə yaxınlaşır</Link>}
          {(o.recurringDue ?? 0) > 0 && <Link href="/xercler" className="rounded-xl bg-violet-50 px-3 py-2 font-medium text-violet-700 ring-1 ring-violet-200">{o.recurringDue} təkrarlanan xərc ödənilməlidir ({formatMoney(o.recurringDueAmount)})</Link>}
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {hasCash && (
          <>
            <Tile href="/kassa" label="Kassa balansı (nağd)" value={formatMoney(o.cashBalance)} tone="border-l-emerald-500" />
            <Tile href="/kassa" label="Bank və kart balansı" value={formatMoney((o.bankBalance ?? 0) + (o.cardBalance ?? 0))} hint={`Bank ${formatMoney(o.bankBalance)} · Kart ${formatMoney(o.cardBalance)}`} tone="border-l-sky-500" />
            <Tile href="/kassa" label="Bu gün pul hərəkəti" value={`+${formatMoney(o.todayIn)} / −${formatMoney(o.todayOut)}`} hint="Köçürmələr daxil deyil" tone="border-l-teal-500" />
            <Tile href="/kassa" label="Bu ay pul hərəkəti" value={`+${formatMoney(o.monthIn)} / −${formatMoney(o.monthOut)}`} hint="Daxil olan / çıxan" tone="border-l-violet-500" />
          </>
        )}
        {hasExp && (
          <>
            <Tile href="/xercler" label="Bu günün xərci" value={formatMoney(o.todayExpenses)} tone="border-l-rose-500" />
            <Tile href="/xercler" label="Bu ayın xərci" value={formatMoney(o.monthExpenses)}
              hint={diff === null ? undefined : `Keçən ay ${formatMoney(o.previousMonthExpenses)} (${diff >= 0 ? "+" : "−"}${formatMoney(Math.abs(diff))})`} tone="border-l-orange-500" />
          </>
        )}
      </div>
      <p className="mt-2 text-xs text-slate-400">Borc ödənişlərinin yığılması gəlir, şirkətə borc ödənişi xərc sayılmır. Xalis mənfəət satış modulu ilə əlavə olunacaq.</p>
    </section>
  );
}
