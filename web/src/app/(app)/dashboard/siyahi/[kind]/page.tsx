"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, PageHeader, Select, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { DEBT_STATUS_LABEL, METHOD_LABEL, formatDate, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import { DebtShortcuts, type ShortcutSection } from "@/components/dashboard/DebtShortcuts";
import type { DashboardListKind, DebtRow, EntryRow, ListPage, PartyRow, PaymentRow } from "@/lib/types";

const META: Record<DashboardListKind, { title: string; subtitle: string; sumLabel: string; shape: "party" | "debt" | "entry" | "payment"; partyFilter?: boolean }> = {
  "customer-debts": { title: "Müştəri borcu", subtitle: "Bizə borcu olan müştərilər", sumLabel: "Qalıq borc", shape: "party" },
  "active-customers": { title: "Aktiv müştərilər", subtitle: "Qalıq borcu olan müştərilər", sumLabel: "Qalıq borc", shape: "party" },
  "company-receivables": { title: "Şirkətlər bizə borcludur", subtitle: "Bizə borcu olan şirkətlər", sumLabel: "Qalıq borc", shape: "party" },
  "company-payables": { title: "Toplam borcum", subtitle: "Bizim şirkətlərə olan açıq öhdəliklərimiz", sumLabel: "Qalıq borcumuz", shape: "party" },
  "today-debts": { title: "Yazılan borclar", subtitle: "Seçilmiş dövrdə yazılan və artırılan borclar (Bakı vaxtı)", sumLabel: "Yazılan məbləğ", shape: "entry", partyFilter: true },
  "active-debts": { title: "Aktiv borclar", subtitle: "Qalığı sıfırdan böyük olan borclar (qismən ödənilənlər daxil)", sumLabel: "Qalıq", shape: "debt", partyFilter: true },
  "overdue-debts": { title: "Gecikmiş borclar", subtitle: "Son ödəmə tarixi keçmiş və bağlanmamış borclar", sumLabel: "Qalıq", shape: "debt", partyFilter: true },
  "closed-debts": { title: "Bitmiş borclar", subtitle: "Tam ödənilmiş və bağlanmış borclar", sumLabel: "Bağlanmış məbləğ", shape: "debt", partyFilter: true },
  "payments-out": { title: "Ödədiyim", subtitle: "Mağazanın şirkətlərə etdiyi faktiki ödənişlər", sumLabel: "Ödənilib", shape: "payment" },
  "payments-in": { title: "Alınan ödənişlər", subtitle: "Müştəri və şirkətlərdən qəbul edilən ödənişlər", sumLabel: "Qəbul edilib", shape: "payment", partyFilter: true },
};

const PAGE = 50;

/** Lists that get the "+ Müştəri / + Şirkət" and "+ Borc" shortcuts, as on their dashboard card. */
const SHORTCUTS: Partial<Record<DashboardListKind, ShortcutSection>> = {
  "customer-debts": "customer",
  "active-customers": "customer",
  "company-receivables": "receivable",
  "company-payables": "payable",
};

function iso(d: Date) {
  return d.toISOString().slice(0, 10);
}

/** Date presets in Baku time: today, this week, this month, last month. */
function preset(key: string): { from: string; to: string } {
  const today = todayBaku();
  const t = new Date(today + "T12:00:00Z");
  if (key === "today") return { from: today, to: today };
  if (key === "yesterday") {
    const y = new Date(t);
    y.setUTCDate(y.getUTCDate() - 1);
    return { from: iso(y), to: iso(y) };
  }
  if (key === "week") {
    const s = new Date(t);
    s.setUTCDate(s.getUTCDate() - ((s.getUTCDay() + 6) % 7));
    return { from: iso(s), to: today };
  }
  if (key === "month") return { from: today.slice(0, 8) + "01", to: today };
  if (key === "lastMonth") {
    const first = new Date(Date.UTC(t.getUTCFullYear(), t.getUTCMonth() - 1, 1));
    const last = new Date(Date.UTC(t.getUTCFullYear(), t.getUTCMonth(), 0));
    return { from: iso(first), to: iso(last) };
  }
  return { from: "", to: "" };
}

export default function DashboardListPage() {
  const params = useParams<{ kind: string }>();
  const kind = params.kind as DashboardListKind;
  const meta = META[kind];
  const isEntries = kind === "today-debts";
  const [filters, setFilters] = useState(() => ({ q: "", ...(isEntries ? preset("today") : { from: "", to: "" }), partyKind: "" }));
  const [applied, setApplied] = useState(filters);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ListPage<unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!meta) return;
    setLoading(true);
    setError(null);
    const qs = new URLSearchParams({ page: String(page), size: String(PAGE) });
    if (applied.q.trim()) qs.set("q", applied.q.trim());
    if (applied.from) qs.set("from", applied.from);
    if (applied.to) qs.set("to", applied.to);
    if (applied.partyKind) qs.set("partyKind", applied.partyKind);
    try {
      setData(await api<ListPage<unknown>>(`/api/dashboard/lists/${kind}?${qs.toString()}`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Siyahı yüklənmədi");
    } finally {
      setLoading(false);
    }
  }, [kind, meta, applied, page]);

  useEffect(() => {
    load();
  }, [load]);

  if (!meta) return <EmptyState title="Siyahı tapılmadı" />;

  const apply = (e?: FormEvent) => {
    e?.preventDefault();
    setPage(0);
    setApplied(filters);
  };
  const applyPreset = (key: string) => {
    const next = { ...filters, ...preset(key) };
    setFilters(next);
    setPage(0);
    setApplied(next);
  };
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE)) : 1;
  const profile = (kindOf: "CUSTOMER" | "COMPANY", id: string) => `/kontragentlar/${kindOf === "CUSTOMER" ? "musteri" : "sirket"}/${id}`;

  return (
    <>
      <PageHeader
        title={meta.title}
        subtitle={meta.subtitle}
        actions={
          <>
            {SHORTCUTS[kind] && (
              <DebtShortcuts
                section={SHORTCUTS[kind]}
                onSaved={(msg) => {
                  setNotice(msg);
                  load();
                }}
              />
            )}
            <Link href="/dashboard"><Button variant="outline">← Panel</Button></Link>
          </>
        }
      />
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <section className="mb-5 grid gap-4 sm:grid-cols-2">
        <StatCard label="Qeyd sayı" value={String(data?.total ?? "—")} tone="navy" />
        <StatCard label={meta.sumLabel} value={data ? formatMoney(data.sum) : "—"} tone="gold" hint="Panel kartındakı rəqəmlə eynidir" />
      </section>

      <Card>
        <form onSubmit={apply} className="mb-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
          <div className="lg:col-span-2">
            <Input placeholder="Ad, telefon, müştəri kodu, çek nömrəsi" value={filters.q} onChange={(e) => setFilters({ ...filters, q: e.target.value })} aria-label="Axtarış" />
          </div>
          <Input type="date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} aria-label="Başlanğıc tarix" />
          <Input type="date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} aria-label="Son tarix" />
          {meta.partyFilter ? (
            <Select value={filters.partyKind} onChange={(e) => setFilters({ ...filters, partyKind: e.target.value })} aria-label="Tərəf">
              <option value="">Müştəri və şirkət</option>
              <option value="CUSTOMER">Yalnız müştərilər</option>
              <option value="COMPANY">Yalnız şirkətlər</option>
            </Select>
          ) : <div className="hidden lg:block" />}
          <Button type="submit" variant="primary">Axtar</Button>
        </form>
        <div className="mb-5 flex flex-wrap gap-2 text-xs">
          {[["today", "Bu gün"], ["yesterday", "Dünən"], ["week", "Bu həftə"], ["month", "Bu ay"], ["lastMonth", "Keçən ay"], ["all", "Hamısı"]].map(([k, l]) => (
            <button key={k} type="button" onClick={() => applyPreset(k)} className="rounded-full bg-slate-100 px-3 py-1.5 font-medium text-slate-700 hover:bg-slate-200">{l}</button>
          ))}
        </div>

        {loading && !data ? <Spinner /> : !data || data.items.length === 0 ? (
          <EmptyState title="Qeyd tapılmadı" text="Axtarışı və ya tarixi dəyişin." />
        ) : (
          <>
            <Table>
              {meta.shape === "party" && (
                <>
                  <thead className="bg-slate-50/70"><tr><Th>Ad</Th><Th>Telefon</Th><Th className="text-right">Ümumi borc</Th><Th className="text-right">Ödənilib</Th><Th className="text-right">Qalıq</Th><Th>Son ödəniş</Th><Th>Son əməliyyat</Th></tr></thead>
                  <tbody className="divide-y divide-slate-100">
                    {(data.items as PartyRow[]).map((r) => (
                      <tr key={r.partyId + r.debtType} className="hover:bg-gold-500/5">
                        <Td>
                          <Link href={profile(r.partyKind, r.partyId)} className="font-medium text-navy-800 hover:text-gold-600 hover:underline">{r.name}</Link>
                          <div className="text-xs text-slate-400">{r.code ?? ""}{r.overdueDebts > 0 && <span className="ml-1 text-rose-600">· gecikmiş</span>}</div>
                        </Td>
                        <Td>{r.phone ?? "—"}</Td>
                        <Td className="text-right tabular-nums">{formatMoney(r.totalDebt)}</Td>
                        <Td className="text-right tabular-nums text-emerald-700">{formatMoney(r.paid)}</Td>
                        <Td className="text-right font-semibold tabular-nums text-navy-800">{formatMoney(r.remaining)}</Td>
                        <Td className="text-xs">{r.lastPaymentAt ? formatDateTime(r.lastPaymentAt) : "—"}</Td>
                        <Td className="text-xs">{r.lastActivityAt ? formatDateTime(r.lastActivityAt) : "—"}</Td>
                      </tr>
                    ))}
                  </tbody>
                </>
              )}
              {meta.shape === "debt" && (
                <>
                  <thead className="bg-slate-50/70"><tr><Th>Borclu</Th><Th>Borc tarixi</Th><Th className="text-right">İlkin məbləğ</Th><Th className="text-right">Ödənilib</Th><Th className="text-right">Qalıq</Th><Th>{kind === "closed-debts" ? "Bağlanma" : "Son ödəniş"}</Th><Th>Çek</Th><Th>Status</Th></tr></thead>
                  <tbody className="divide-y divide-slate-100">
                    {(data.items as DebtRow[]).map((r) => (
                      <tr key={r.debtId} className="hover:bg-gold-500/5">
                        <Td>
                          <Link href={profile(r.partyKind, r.partyId)} className="font-medium text-navy-800 hover:text-gold-600 hover:underline">{r.name}</Link>
                          <div className="text-xs text-slate-400">{[r.code, r.phone].filter(Boolean).join(" · ") || "—"}</div>
                        </Td>
                        <Td>{formatDate(r.debtDate)}{r.dueDate && <div className={`text-xs ${r.overdueDays > 0 ? "font-semibold text-rose-600" : "text-slate-400"}`}>son: {formatDate(r.dueDate)}{r.overdueDays > 0 ? ` · ${r.overdueDays} gün gecikir` : ""}</div>}</Td>
                        <Td className="text-right tabular-nums">{formatMoney(r.amount)}</Td>
                        <Td className="text-right tabular-nums text-emerald-700">{formatMoney(r.paid)}</Td>
                        <Td className="text-right font-semibold tabular-nums text-navy-800">{formatMoney(r.remaining)}</Td>
                        <Td className="text-xs">{r.lastPaymentAt ? formatDateTime(r.lastPaymentAt) : "—"}</Td>
                        <Td className="text-xs">{r.receiptNumber ?? "—"}</Td>
                        <Td><Badge tone={r.status === "PAID" ? "emerald" : r.overdueDays > 0 ? "rose" : r.status === "PARTIAL" ? "amber" : "slate"}>{r.overdueDays > 0 ? "Gecikmiş" : DEBT_STATUS_LABEL[r.status]}</Badge></Td>
                      </tr>
                    ))}
                  </tbody>
                </>
              )}
              {meta.shape === "entry" && (
                <>
                  <thead className="bg-slate-50/70"><tr><Th>Müştəri</Th><Th>Tarix</Th><Th>Çek</Th><Th className="text-right">Məbləğ</Th><Th className="text-right">Ödənilib</Th><Th className="text-right">Qalıq</Th><Th>İşçi</Th></tr></thead>
                  <tbody className="divide-y divide-slate-100">
                    {(data.items as EntryRow[]).map((r) => (
                      <tr key={r.entryId} className="hover:bg-gold-500/5">
                        <Td>
                          <Link href={profile(r.partyKind, r.partyId)} className="font-medium text-navy-800 hover:text-gold-600 hover:underline">{r.name}</Link>
                          <div className="text-xs text-slate-400">{[r.code, r.phone].filter(Boolean).join(" · ") || "—"}</div>
                        </Td>
                        <Td>{formatDate(r.entryDate)}<div className="text-xs text-slate-400">{formatDateTime(r.createdAt)}</div></Td>
                        <Td className="text-xs">{r.receiptNumber ?? "—"}{r.note && <div className="text-slate-400">{r.note}</div>}</Td>
                        <Td className="text-right font-semibold tabular-nums">{formatMoney(r.amount)}</Td>
                        <Td className="text-right tabular-nums text-emerald-700">{formatMoney(r.debtPaid)}</Td>
                        <Td className="text-right tabular-nums text-navy-800">{formatMoney(r.debtRemaining)}</Td>
                        <Td className="text-xs">{r.actorName}</Td>
                      </tr>
                    ))}
                  </tbody>
                </>
              )}
              {meta.shape === "payment" && (
                <>
                  <thead className="bg-slate-50/70"><tr><Th>Tarix</Th><Th>Kimə / kimdən</Th><Th>Üsul</Th><Th>Təsvir</Th><Th>Əməliyyatı edən</Th><Th className="text-right">Məbləğ</Th></tr></thead>
                  <tbody className="divide-y divide-slate-100">
                    {(data.items as PaymentRow[]).map((r) => (
                      <tr key={r.paymentId} className="hover:bg-gold-500/5">
                        <Td>{formatDateTime(r.paidAt)}</Td>
                        <Td><Link href={profile(r.partyKind, r.partyId)} className="font-medium text-navy-800 hover:text-gold-600 hover:underline">{r.name}</Link></Td>
                        <Td>{METHOD_LABEL[r.paymentMethod]}</Td>
                        <Td className="max-w-[14rem] truncate text-slate-500">{r.description ?? "—"}</Td>
                        <Td className="text-xs">{r.recordedByName}</Td>
                        <Td className="text-right font-semibold tabular-nums">{formatMoney(r.amount)}</Td>
                      </tr>
                    ))}
                  </tbody>
                </>
              )}
            </Table>
            <div className="mt-5 flex items-center justify-between text-sm text-slate-500">
              <span>Səhifə {page + 1} / {pages}</span>
              <div className="flex gap-2">
                <Button variant="outline" disabled={page === 0 || loading} onClick={() => setPage((p) => p - 1)}>Geri</Button>
                <Button variant="outline" disabled={page + 1 >= pages || loading} onClick={() => setPage((p) => p + 1)}>İrəli</Button>
              </div>
            </div>
          </>
        )}
      </Card>
    </>
  );
}
