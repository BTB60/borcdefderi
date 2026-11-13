"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, PageHeader, Select, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api, download } from "@/lib/api";
import { receiptMessageText, waLink } from "@/lib/whatsapp";
import { useAuth } from "@/lib/auth";
import { DEBT_TYPE_LABEL, METHOD_LABEL, METHOD_TONE, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import type { Payment, PaymentMethod } from "@/lib/types";
import { PaymentCancelModal, PaymentEditModal } from "@/components/payments/PaymentEditModals";

const METHODS: PaymentMethod[] = ["CASH", "CARD", "TRANSFER", "OTHER"];

export default function PaymentsPage() {
  const { can, me, isOwner } = useAuth();
  const [action, setAction] = useState<{ kind: "edit" | "cancel"; payment: Payment } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [filters, setFilters] = useState({ from: "", to: "", method: "" });
  const [applied, setApplied] = useState(filters);
  const [items, setItems] = useState<Payment[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);

  useEffect(() => {
    const qs = new URLSearchParams({ limit: "500", includeVoided: "true" });
    if (applied.from) qs.set("from", applied.from);
    if (applied.to) qs.set("to", applied.to);
    if (applied.method) qs.set("method", applied.method);
    setItems(null);
    setError(null);
    api<Payment[]>(`/api/payments?${qs.toString()}`)
      .then(setItems)
      .catch((e) => {
        setItems([]);
        setError(e instanceof ApiError ? e.message : "Ödənişlər yüklənmədi");
      });
  }, [applied, reload]);

  const sum = (m?: PaymentMethod) => items?.filter((p) => !p.voidedAt && (!m || p.paymentMethod === m)).reduce((s, p) => s + p.amount, 0) ?? 0;

  function changed(message: string) {
    setAction(null);
    setNotice(message);
    setReload((n) => n + 1);
  }

  function apply(e: FormEvent) {
    e.preventDefault();
    setApplied(filters);
  }

  function setRange(kind: "today" | "month") {
    const today = todayBaku();
    const next = { ...filters, from: kind === "today" ? today : `${today.slice(0, 8)}01`, to: today };
    setFilters(next);
    setApplied(next);
  }

  async function exportReport(format: "xlsx" | "pdf") {
    const qs = new URLSearchParams({ format });
    if (applied.from) qs.set("from", applied.from);
    if (applied.to) qs.set("to", applied.to);
    setExporting(format);
    try {
      await download(`/api/reports/payments/export?${qs.toString()}`, `odenisler.${format}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Hesabat yüklənmədi");
    } finally {
      setExporting(null);
    }
  }

  return (
    <>
      <PageHeader
        title="Ödənişlər"
        subtitle="Nağd, kart, köçürmə və digər ödənişlər (ən yeni əvvəldə)"
        actions={
          can("REPORTS") ? (
            <>
              <Button variant="outline" disabled={exporting !== null} onClick={() => exportReport("xlsx")}>{exporting === "xlsx" ? "Hazırlanır…" : "Excel hesabat"}</Button>
              <Button variant="outline" disabled={exporting !== null} onClick={() => exportReport("pdf")}>{exporting === "pdf" ? "Hazırlanır…" : "PDF hesabat"}</Button>
            </>
          ) : undefined
        }
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}

      <div className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <Card><p className="text-xs uppercase tracking-wider text-slate-500">Cəmi (seçilmiş)</p><p className="mt-2 font-serif text-2xl font-semibold text-navy-800">{formatMoney(sum())}</p></Card>
        {METHODS.map((m) => (
          <Card key={m}>
            <p className="text-xs uppercase tracking-wider text-slate-500">{METHOD_LABEL[m]}</p>
            <p className="mt-2 font-serif text-2xl font-semibold text-navy-800">{formatMoney(sum(m))}</p>
          </Card>
        ))}
      </div>

      <Card>
        <form onSubmit={apply} className="mb-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Input type="date" aria-label="Başlanğıc tarix" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} />
          <Input type="date" aria-label="Son tarix" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} />
          <Select aria-label="Ödəniş üsulu" value={filters.method} onChange={(e) => setFilters({ ...filters, method: e.target.value })}>
            <option value="">Bütün üsullar</option>
            {METHODS.map((m) => <option key={m} value={m}>{METHOD_LABEL[m]}</option>)}
          </Select>
          <Button type="submit">Filtrlə</Button>
        </form>
        <div className="mb-5 flex flex-wrap gap-2 text-xs">
          <button className="rounded-full bg-slate-100 px-3 py-1 font-medium text-slate-700 hover:bg-slate-200" onClick={() => setRange("today")}>Bu gün</button>
          <button className="rounded-full bg-slate-100 px-3 py-1 font-medium text-slate-700 hover:bg-slate-200" onClick={() => setRange("month")}>Bu ay</button>
          {can("REPORTS") && <span className="self-center text-slate-400">Hesabat seçilmiş tarix aralığı üzrə hazırlanır (boşdursa — cari ay).</span>}
        </div>

        {!items ? (
          <Spinner />
        ) : items.length === 0 ? (
          <EmptyState title="Ödəniş tapılmadı" text="Tarix aralığını və ya üsulu dəyişin." />
        ) : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Tarix</Th><Th>Ad</Th><Th>Bölmə</Th><Th>Üsul</Th><Th>Təsvir</Th><Th>Qəbul edən</Th><Th className="text-right">Məbləğ</Th><Th className="text-right">Çek</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((p) => (
                <tr key={p.id} className={`transition-colors hover:bg-gold-500/5 ${p.voidedAt ? "bg-slate-50 text-slate-400" : ""}`}>
                  <Td>
                    {formatDateTime(p.paidAt)}
                    {p.voidedAt && <div className="mt-1"><Badge tone="rose">Ləğv edildi</Badge></div>}
                    {!p.voidedAt && p.editedAt && <div className="mt-1 text-[11px] text-slate-400">düzəldilib</div>}
                  </Td>
                  <Td className={`font-medium ${p.voidedAt ? "text-slate-400 line-through" : "text-navy-800"}`}>{p.partyName}</Td>
                  <Td className="text-slate-500">{DEBT_TYPE_LABEL[p.debtType]}</Td>
                  <Td><Badge tone={METHOD_TONE[p.paymentMethod]}>{METHOD_LABEL[p.paymentMethod]}</Badge></Td>
                  <Td className="max-w-[14rem] truncate text-slate-500">{p.voidedAt ? `Səbəb: ${p.voidReason ?? "—"}` : p.description ?? "—"}</Td>
                  <Td className="text-slate-500">{p.recordedByName ?? "—"}</Td>
                  <Td className={`text-right font-semibold tabular-nums ${p.voidedAt ? "text-slate-400 line-through" : "text-emerald-700"}`}>{formatMoney(p.amount)}</Td>
                  <Td className="text-right">
                    <div className="flex items-center justify-end gap-3">
                      {isOwner && !p.voidedAt && (
                        <>
                          <button className="text-xs font-medium text-navy-700 hover:text-navy-500" onClick={() => setAction({ kind: "edit", payment: p })}>Düzəlt</button>
                          <button className="text-xs font-medium text-rose-600 hover:text-rose-500" onClick={() => setAction({ kind: "cancel", payment: p })}>Ləğv et</button>
                        </>
                      )}
                      <button
                        className="text-xs font-medium text-gold-600 hover:text-gold-500"
                        onClick={() => download(`/api/payments/${p.id}/receipt`, "cek.pdf").catch((e) => setError(e instanceof ApiError ? e.message : "Çek yüklənmədi"))}
                      >
                        PDF
                      </button>
                      {(() => {
                        const href = !p.voidedAt && p.debtType !== "COMPANY_PAYABLE" && p.receiptUrl
                          ? waLink(p.partyPhone, receiptMessageText(me?.storeName ?? "", p.partyName, p.amount, p.receiptUrl, p.paidAt))
                          : null;
                        return href ? (
                          <a href={href} target="_blank" rel="noopener noreferrer" className="text-xs font-medium text-emerald-600 hover:text-emerald-500" title="Çeki WhatsApp ilə müştəriyə göndər">
                            WhatsApp
                          </a>
                        ) : null;
                      })()}
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {action?.kind === "edit" && (
        <PaymentEditModal payment={action.payment} onClose={() => setAction(null)} onSaved={() => changed("Ödəniş düzəldildi")} />
      )}
      {action?.kind === "cancel" && (
        <PaymentCancelModal payment={action.payment} onClose={() => setAction(null)} onSaved={() => changed("Ödəniş ləğv edildi")} />
      )}
    </>
  );
}
