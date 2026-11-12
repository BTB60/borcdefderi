"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, PageHeader, Select, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api, download } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { DEBT_SECTION_SLUG, DEBT_STATUS_LABEL, DEBT_TYPE_LABEL, formatDate, formatMoney } from "@/lib/format";
import type { Debt, DebtPage, DebtStatus, DebtType } from "@/lib/types";
import { debtReminderText, waLink } from "@/lib/whatsapp";
import { DebtEditModal, DebtFormModal, DebtIncreaseModal, HistoryModal, PaymentModal, WhatsAppIcon } from "@/components/debts/DebtModals";

const PAGE_SIZE = 20;


const STATUS_TONE: Record<DebtStatus, "amber" | "emerald" | "slate"> = { OPEN: "slate", PARTIAL: "amber", PAID: "emerald" };

type Filters = { status: string; q: string; from: string; to: string };

export default function DebtSectionPage() {
  const params = useParams<{ type: string }>();
  const type: DebtType | undefined = DEBT_SECTION_SLUG[params.type];
  const { can, me } = useAuth();

  const [filters, setFilters] = useState<Filters>({ status: "", q: "", from: "", to: "" });
  const [applied, setApplied] = useState<Filters>(filters);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<DebtPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [creating, setCreating] = useState(false);
  const [paying, setPaying] = useState<Debt | null>(null);
  const [editing, setEditing] = useState<Debt | null>(null);
  const [historyOf, setHistoryOf] = useState<Debt | null>(null);
  const [increasing, setIncreasing] = useState<Debt | null>(null);

  const load = useCallback(async () => {
    if (!type) return;
    setLoading(true);
    setError(null);
    const qs = new URLSearchParams({ type, page: String(page), size: String(PAGE_SIZE) });
    if (applied.status) qs.set("status", applied.status);
    if (applied.q.trim()) qs.set("q", applied.q.trim());
    if (applied.from) qs.set("from", applied.from);
    if (applied.to) qs.set("to", applied.to);
    try {
      setData(await api<DebtPage>(`/api/debts?${qs.toString()}`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Məlumat yüklənə bilmədi");
    } finally {
      setLoading(false);
    }
  }, [type, applied, page]);

  useEffect(() => {
    load();
  }, [load]);

  if (!type) return <EmptyState title="Bölmə tapılmadı" />;
  if (!can(type === "CUSTOMER_DEBT" ? "CUSTOMER_DEBTS" : type === "COMPANY_RECEIVABLE" ? "COMPANY_RECEIVABLES" : "COMPANY_PAYABLES")) {
    return (
      <>
        <PageHeader title={DEBT_TYPE_LABEL[type]} />
        <Card><EmptyState title="Bu bölmə üçün icazəniz yoxdur" text="Mağaza sahibindən icazə tələb edin." /></Card>
      </>
    );
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;
  const isCustomer = type === "CUSTOMER_DEBT";
  const partyLabel = isCustomer ? "Müştəri" : "Şirkət";

  async function exportAs(format: "xlsx" | "pdf") {
    const qs = new URLSearchParams({ type: type as string, format });
    if (applied.status) qs.set("status", applied.status);
    if (applied.from) qs.set("from", applied.from);
    if (applied.to) qs.set("to", applied.to);
    try {
      await download(`/api/reports/debts/export?${qs.toString()}`, `borc.${format}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Eksport alınmadı");
    }
  }

  function applyFilters(e: FormEvent) {
    e.preventDefault();
    setPage(0);
    setApplied(filters);
  }

  return (
    <>
      <PageHeader
        title={DEBT_TYPE_LABEL[type]}
        subtitle={isCustomer ? "Müştərilərin mağazaya olan borcları" : type === "COMPANY_RECEIVABLE" ? "Şirkətlərin mağazaya borcları" : "Mağazanın şirkətlərə olan borcları"}
        actions={
          <>
            <Button variant="outline" onClick={() => exportAs("xlsx")}>Excel</Button>
            <Button variant="outline" onClick={() => exportAs("pdf")}>PDF</Button>
            <Button variant="gold" onClick={() => setCreating(true)}>+ Borc əlavə et</Button>
          </>
        }
      />

      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <section className="mb-6 grid gap-4 sm:grid-cols-3" aria-label="Cəmi">
        <StatCard label="Cəmi borc" value={formatMoney(data?.totals.amount)} tone="navy" />
        <StatCard label="Ödənilib" value={formatMoney(data?.totals.paid)} tone="emerald" />
        <StatCard label="Qalıq" value={formatMoney(data?.totals.remaining)} tone="gold" hint={`${data?.total ?? 0} qeyd`} />
      </section>

      <Card>
        <form onSubmit={applyFilters} className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <div className="lg:col-span-2">
            <Input placeholder="Ad, telefon və ya təsvir üzrə axtar" value={filters.q} onChange={(e) => setFilters({ ...filters, q: e.target.value })} aria-label="Axtarış" />
          </div>
          <Select value={filters.status} onChange={(e) => setFilters({ ...filters, status: e.target.value })} aria-label="Status">
            <option value="">Bütün statuslar</option>
            <option value="UNPAID">Ödənilməmiş</option>
            <option value="OPEN">Açıq</option>
            <option value="PARTIAL">Qismən</option>
            <option value="PAID">Ödənilib</option>
          </Select>
          <Input type="date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} aria-label="Başlanğıc tarix" />
          <div className="flex gap-2">
            <Input type="date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} aria-label="Son tarix" />
            <Button type="submit" variant="primary">Filtr</Button>
          </div>
        </form>

        {loading && !data ? (
          <Spinner />
        ) : !data || data.items.length === 0 ? (
          <EmptyState title="Qeyd tapılmadı" text="Filtri dəyişin və ya yeni borc əlavə edin." />
        ) : (
          <>
            <Table>
              <thead className="bg-slate-50/70">
                <tr>
                  <Th>Tarix</Th><Th>{partyLabel}</Th><Th>Təsvir</Th><Th className="text-right">Məbləğ</Th>
                  <Th className="text-right">Ödənilib</Th><Th className="text-right">Qalıq</Th><Th>Status</Th><Th className="text-right">Əməliyyat</Th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((d) => (
                  <tr key={d.id} className="transition-colors hover:bg-gold-500/5">
                    <Td>{formatDate(d.debtDate)}{d.dueDate && <div className="text-xs text-slate-400">son: {formatDate(d.dueDate)}</div>}</Td>
                    <Td>
                      <Link href={`/kontragentlar/${isCustomer ? "musteri" : "sirket"}/${d.partyId}`} className="font-medium text-navy-800 hover:text-gold-600 hover:underline">{d.partyName}</Link>
                      <div className="text-xs text-slate-400">{d.partyPhone ?? "—"}</div>
                    </Td>
                    <Td className="max-w-[16rem] truncate text-slate-500">{d.description ?? "—"}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(d.amount)}</Td>
                    <Td className="text-right tabular-nums text-emerald-700">{formatMoney(d.paidAmount)}</Td>
                    <Td className={`text-right font-semibold tabular-nums ${d.remainingAmount > 0 ? "text-rose-700" : "text-emerald-700"}`}>{formatMoney(d.remainingAmount)}</Td>
                    <Td><Badge tone={STATUS_TONE[d.status]}>{DEBT_STATUS_LABEL[d.status]}</Badge></Td>
                    <Td className="text-right">
                      <div className="flex justify-end gap-1">
                        {type !== "COMPANY_PAYABLE" && d.status !== "PAID" && waLink(d.partyPhone, "") && (
                          <a
                            href={waLink(d.partyPhone, debtReminderText(me?.storeName ?? "", d.partyName, d.remainingAmount, d.dueDate)) ?? undefined}
                            target="_blank"
                            rel="noopener noreferrer"
                            title="WhatsApp ilə borc xatırlatması göndər"
                            aria-label={`${d.partyName} — WhatsApp xatırlatma`}
                            className="inline-flex items-center rounded-xl px-2.5 py-1.5 text-emerald-600 transition hover:bg-emerald-50"
                          >
                            <WhatsAppIcon />
                          </a>
                        )}
                        <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setHistoryOf(d)}>Tarixçə</Button>
                        <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setEditing(d)} disabled={d.status === "PAID"}>Redaktə</Button>
                        <Button variant="outline" className="px-2.5 py-1.5" onClick={() => setIncreasing(d)} title="Müştəri yenidən nisyə aldı: borcun üstünə əlavə et">+ Borc artır</Button>
                        <Button variant="primary" className="px-3 py-1.5" onClick={() => setPaying(d)} disabled={d.status === "PAID"}>Ödəniş</Button>
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <div className="mt-5 flex items-center justify-between text-sm text-slate-500">
              <span>Səhifə {page + 1} / {totalPages}</span>
              <div className="flex gap-2">
                <Button variant="outline" disabled={page === 0 || loading} onClick={() => setPage((p) => p - 1)}>Geri</Button>
                <Button variant="outline" disabled={page + 1 >= totalPages || loading} onClick={() => setPage((p) => p + 1)}>İrəli</Button>
              </div>
            </div>
          </>
        )}
      </Card>

      {creating && (
        <DebtFormModal
          type={type}
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setNotice("Borc əlavə edildi");
            load();
          }}
        />
      )}
      {editing && (
        <DebtEditModal
          debt={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setNotice("Dəyişikliklər yadda saxlanıldı");
            load();
          }}
        />
      )}
      {paying && (
        <PaymentModal
          debt={paying}
          storeName={me?.storeName ?? ""}
          canNotify={type !== "COMPANY_PAYABLE"}
          onClose={() => setPaying(null)}
          onSaved={() => {
            setPaying(null);
            setNotice("Ödəniş qeydə alındı");
            load();
          }}
        />
      )}
      {historyOf && <HistoryModal debt={historyOf} onClose={() => setHistoryOf(null)} />}
      {increasing && (
        <DebtIncreaseModal
          debt={increasing}
          onClose={() => setIncreasing(null)}
          onSaved={(u) => {
            setIncreasing(null);
            setNotice(`Borc artırıldı. Yeni qalıq: ${formatMoney(u.remainingAmount)}`);
            load();
          }}
        />
      )}
    </>
  );
}
