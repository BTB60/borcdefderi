"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Alert, Badge, Card, EmptyState, PageHeader, Spinner, StatCard } from "@/components/ui";
import { ITEM_STATUS, installmentText, type InstallmentPlan } from "@/components/debts/Installments";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatMoney } from "@/lib/format";
import { waLink } from "@/lib/whatsapp";

/** Taksitlər: every customer paying in parts — late ones first, the next date and a WhatsApp reminder. */
export default function InstallmentsPage() {
  const { me } = useAuth();
  const [plans, setPlans] = useState<InstallmentPlan[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<"all" | "late" | "week">("all");

  useEffect(() => {
    api<InstallmentPlan[]>("/api/installments").then(setPlans).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, []);

  if (error) return <Alert>{error}</Alert>;
  if (!plans) return <Spinner />;

  const active = plans.filter((p) => p.status === "ACTIVE");
  const late = active.filter((p) => p.overdue > 0);
  const weekEnd = new Date(Date.now() + 7 * 864e5).toISOString().slice(0, 10);
  const week = active.filter((p) => p.overdue === 0 && p.next && p.next.dueDate <= weekEnd);
  const shown = filter === "late" ? late : filter === "week" ? week : plans;

  return (
    <>
      <PageHeader title="Taksitlər" subtitle="Hissə-hissə ödəyən müştərilər: gecikənlər, bu həftə ödəyəcəklər" />
      <section className="mb-6 grid gap-4 sm:grid-cols-3">
        <StatCard label="Gecikən" value={formatMoney(late.reduce((s, p) => s + p.overdue, 0))} hint={`${late.length} müştəri`} tone="rose" />
        <StatCard label="Bu həftə gözlənilir" value={formatMoney(week.reduce((s, p) => s + (p.next ? p.next.amount - p.next.paid : 0), 0))} hint={`${week.length} müştəri`} tone="amber" />
        <StatCard label="Taksitdə qalan borc" value={formatMoney(active.reduce((s, p) => s + p.left, 0))} hint={`${active.length} aktiv qrafik`} tone="navy" />
      </section>
      <div className="mb-4 inline-flex rounded-xl bg-slate-100 p-1" role="tablist">
        {([["all", "Hamısı"], ["late", `Gecikən (${late.length})`], ["week", `Bu həftə (${week.length})`]] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={filter === k} onClick={() => setFilter(k)}
            className={`rounded-lg px-4 py-1.5 text-sm font-medium ${filter === k ? "bg-white text-navy-800 shadow" : "text-slate-500"}`}>{l}</button>
        ))}
      </div>
      {shown.length === 0 ? (
        <EmptyState title="Taksit yoxdur" text="Müştərinin kartında “Borcu hissələrə böl” ilə qrafik qurun." />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {shown.map((p) => {
            const wa = waLink(p.customerPhone, installmentText(me?.storeName ?? "", p));
            return (
              <Card key={p.id}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <Link href={`/kontragentlar/musteri/${p.customerId}`} className="font-semibold text-navy-800 hover:underline">{p.customerName}</Link>
                    <p className="text-xs text-slate-500">{p.customerPhone ?? "telefon yoxdur"}</p>
                  </div>
                  {p.status === "COMPLETED" ? <Badge tone="emerald">Tam ödənib</Badge>
                    : p.overdue > 0 ? <Badge tone="rose">Gecikir {formatMoney(p.overdue)}</Badge>
                    : <Badge tone="navy">Aktiv</Badge>}
                </div>
                <div className="mt-3 h-2 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-emerald-500" style={{ width: `${Math.min(100, (p.paid / p.total) * 100)}%` }} /></div>
                <div className="mt-2 flex justify-between text-sm">
                  <span className="text-slate-500">Ödənib {formatMoney(p.paid)} / {formatMoney(p.total)}</span>
                  <span className="font-medium text-navy-800">Qalıb {formatMoney(p.left)}</span>
                </div>
                {p.next && (
                  <p className="mt-2 flex items-center gap-2 text-sm">
                    <span className="text-slate-500">Növbəti:</span> {formatDate(p.next.dueDate)} · <b>{formatMoney(p.next.amount - p.next.paid)}</b>
                    <Badge tone={ITEM_STATUS[p.next.status].tone}>{ITEM_STATUS[p.next.status].label}{p.next.status === "OVERDUE" ? ` ${p.next.daysLate} gün` : ""}</Badge>
                  </p>
                )}
                {wa && p.status === "ACTIVE" && (
                  <a href={wa} target="_blank" rel="noopener noreferrer" className="mt-3 inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-emerald-500">WhatsApp xatırlatma</a>
                )}
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
