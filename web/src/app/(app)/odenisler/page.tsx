"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, PageHeader, Select, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { DEBT_TYPE_LABEL, METHOD_LABEL, formatDateTime, formatMoney } from "@/lib/format";
import type { Payment } from "@/lib/types";

export default function PaymentsPage() {
  const [filters, setFilters] = useState({ from: "", to: "", method: "" });
  const [applied, setApplied] = useState(filters);
  const [items, setItems] = useState<Payment[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const qs = new URLSearchParams({ limit: "200" });
    if (applied.from) qs.set("from", applied.from);
    if (applied.to) qs.set("to", applied.to);
    if (applied.method) qs.set("method", applied.method);
    setItems(null);
    api<Payment[]>(`/api/payments?${qs.toString()}`)
      .then(setItems)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ödənişlər yüklənmədi"));
  }, [applied]);

  const total = items?.reduce((sum, p) => sum + p.amount, 0) ?? 0;
  const cash = items?.filter((p) => p.paymentMethod === "CASH").reduce((s, p) => s + p.amount, 0) ?? 0;
  const card = items?.filter((p) => p.paymentMethod === "CARD").reduce((s, p) => s + p.amount, 0) ?? 0;

  function apply(e: FormEvent) {
    e.preventDefault();
    setApplied(filters);
  }

  return (
    <>
      <PageHeader title="Ödənişlər" subtitle="Nağd və kart ödənişlərinin tam siyahısı (ən yeni əvvəldə)" />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <div className="mb-6 grid gap-4 sm:grid-cols-3">
        <Card><p className="text-xs uppercase tracking-wider text-slate-500">Cəmi (seçilmiş)</p><p className="mt-2 font-serif text-2xl font-semibold text-navy-800">{formatMoney(total)}</p></Card>
        <Card><p className="text-xs uppercase tracking-wider text-slate-500">Nağd</p><p className="mt-2 font-serif text-2xl font-semibold text-emerald-700">{formatMoney(cash)}</p></Card>
        <Card><p className="text-xs uppercase tracking-wider text-slate-500">Kart</p><p className="mt-2 font-serif text-2xl font-semibold text-sky-700">{formatMoney(card)}</p></Card>
      </div>

      <Card>
        <form onSubmit={apply} className="mb-5 grid gap-3 sm:grid-cols-4">
          <Input type="date" aria-label="Başlanğıc" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} />
          <Input type="date" aria-label="Son" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} />
          <Select aria-label="Üsul" value={filters.method} onChange={(e) => setFilters({ ...filters, method: e.target.value })}>
            <option value="">Bütün üsullar</option>
            <option value="CASH">Nağd</option>
            <option value="CARD">Kart</option>
          </Select>
          <Button type="submit">Filtrlə</Button>
        </form>

        {!items ? (
          <Spinner />
        ) : items.length === 0 ? (
          <EmptyState title="Ödəniş tapılmadı" />
        ) : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Tarix</Th><Th>Ad</Th><Th>Bölmə</Th><Th>Üsul</Th><Th>Təsvir</Th><Th>Qeydiyyatçı</Th><Th className="text-right">Məbləğ</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((p) => (
                <tr key={p.id} className="transition-colors hover:bg-gold-500/5">
                  <Td>{formatDateTime(p.paidAt)}</Td>
                  <Td className="font-medium text-navy-800">{p.partyName}</Td>
                  <Td className="text-slate-500">{DEBT_TYPE_LABEL[p.debtType]}</Td>
                  <Td><Badge tone={p.paymentMethod === "CASH" ? "emerald" : "sky"}>{METHOD_LABEL[p.paymentMethod]}</Badge></Td>
                  <Td className="max-w-[14rem] truncate text-slate-500">{p.description ?? "—"}</Td>
                  <Td className="text-slate-500">{p.recordedByName ?? "—"}</Td>
                  <Td className="text-right font-semibold tabular-nums">{formatMoney(p.amount)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
