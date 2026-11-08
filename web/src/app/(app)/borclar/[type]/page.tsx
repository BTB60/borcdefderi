"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api, canShareFiles, download, shareProtectedFile } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { DEBT_SECTION_SLUG, DEBT_STATUS_LABEL, DEBT_TYPE_LABEL, METHOD_LABEL, formatDate, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import type { Debt, DebtPage, DebtStatus, DebtType, LedgerEntry, Party, Payment, PaymentMethod } from "@/lib/types";
import { debtReminderText, paymentConfirmationText, waLink } from "@/lib/whatsapp";

const PAGE_SIZE = 20;
const TODAY = todayBaku;
const METHODS: PaymentMethod[] = ["CASH", "CARD", "TRANSFER", "OTHER"];

function WhatsAppIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor" aria-hidden="true">
      <path d="M12 2a10 10 0 0 0-8.6 15.1L2 22l5-1.3A10 10 0 1 0 12 2Zm0 18.2a8.2 8.2 0 0 1-4.2-1.2l-.3-.2-3 .8.8-2.9-.2-.3A8.2 8.2 0 1 1 12 20.2Zm4.5-6.1c-.2-.1-1.5-.7-1.7-.8-.2-.1-.4-.1-.6.1l-.8 1c-.1.2-.3.2-.5.1a6.7 6.7 0 0 1-3.3-2.9c-.2-.4.2-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.6-.4h-.5a1 1 0 0 0-.7.3 3 3 0 0 0-.9 2.2 5.2 5.2 0 0 0 1.1 2.7 11.8 11.8 0 0 0 4.5 4c1.7.7 2.3.8 3.2.6.5-.1 1.5-.6 1.7-1.2.2-.6.2-1.1.2-1.2l-.5-.2Z" />
    </svg>
  );
}

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
            <Button variant="gold" onClick={() => setCreating(true)}>+ Yeni borc</Button>
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
                      <div className="font-medium text-navy-800">{d.partyName}</div>
                      <div className="text-xs text-slate-400">{d.partyPhone ?? "—"}</div>
                    </Td>
                    <Td className="max-w-[16rem] truncate text-slate-500">{d.description ?? "—"}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(d.amount)}</Td>
                    <Td className="text-right tabular-nums text-emerald-700">{formatMoney(d.paidAmount)}</Td>
                    <Td className="text-right font-semibold tabular-nums text-navy-800">{formatMoney(d.remainingAmount)}</Td>
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
            setNotice("Borc uğurla əlavə edildi");
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
    </>
  );
}

function DebtFormModal({ type, onClose, onSaved }: { type: DebtType; onClose: () => void; onSaved: () => void }) {
  const isCustomer = type === "CUSTOMER_DEBT";
  const [parties, setParties] = useState<Party[] | null>(null);
  const [form, setForm] = useState({ partyId: "", amount: "", debtDate: TODAY(), dueDate: "", description: "", note: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Party[]>(isCustomer ? "/api/customers" : "/api/companies").then(setParties).catch(() => setParties([]));
  }, [isCustomer]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setErrors({});
    try {
      await api("/api/debts", {
        method: "POST",
        json: {
          debtType: type,
          partyId: form.partyId,
          amount: form.amount,
          debtDate: form.debtDate,
          dueDate: form.dueDate || null,
          description: form.description || null,
          note: form.note || null,
        },
      });
      onSaved();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrors(err.fields ?? {});
      } else setError("Əməliyyat alınmadı");
    } finally {
      setBusy(false);
    }
  }

  const footer = (
    <>
      <Button variant="ghost" onClick={onClose}>Ləğv et</Button>
      <Button type="submit" form="debt-form" disabled={busy || !parties?.length}>{busy ? "Saxlanılır…" : "Saxla"}</Button>
    </>
  );

  return (
    <Modal open title={`Yeni borc — ${DEBT_TYPE_LABEL[type]}`} onClose={onClose} footer={footer}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {parties && parties.length === 0 ? (
        <EmptyState title={`Əvvəlcə ${isCustomer ? "müştəri" : "şirkət"} əlavə edin`} text="Müştəri və şirkət bölməsindən qeyd yaradın." />
      ) : (
        <form id="debt-form" onSubmit={submit} className="space-y-4">
          <Field label={isCustomer ? "Müştəri" : "Şirkət"} error={errors.partyId}>
            <Select required value={form.partyId} onChange={(e) => setForm({ ...form, partyId: e.target.value })}>
              <option value="">Seçin…</option>
              {parties?.map((p) => <option key={p.id} value={p.id}>{p.name}{p.phone ? ` · ${p.phone}` : ""}</option>)}
            </Select>
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Məbləğ (₼)" error={errors.amount}>
              <Input required type="number" min="0.01" step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
            </Field>
            <Field label="Borc tarixi" error={errors.debtDate}>
              <Input required type="date" value={form.debtDate} onChange={(e) => setForm({ ...form, debtDate: e.target.value })} />
            </Field>
          </div>
          <Field label="Son ödəmə tarixi" hint="İstəyə bağlı">
            <Input type="date" value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
          </Field>
          <Field label="Təsvir">
            <Input maxLength={255} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Məs: Mart ayı mal alışı" />
          </Field>
          <Field label="Qeyd">
            <Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          </Field>
        </form>
      )}
    </Modal>
  );
}

function DebtEditModal({ debt, onClose, onSaved }: { debt: Debt; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({
    amount: String(debt.amount),
    debtDate: debt.debtDate,
    dueDate: debt.dueDate ?? "",
    description: debt.description ?? "",
    note: debt.note ?? "",
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api(`/api/debts/${debt.id}`, {
        method: "PUT",
        json: { ...form, dueDate: form.dueDate || null, description: form.description || null, note: form.note || null, version: debt.version },
      });
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Yadda saxlanılmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Redaktə — ${debt.partyName}`}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="debt-edit" disabled={busy}>Yadda saxla</Button></>}
    >
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="debt-edit" onSubmit={submit} className="space-y-4">
        <p className="rounded-xl bg-slate-50 px-4 py-3 text-xs text-slate-500">
          Artıq ödənilmiş məbləğ: <strong className="text-navy-800">{formatMoney(debt.paidAmount)}</strong>. Borc məbləği bundan az ola bilməz.
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Məbləğ (₼)">
            <Input required type="number" min="0.01" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
          </Field>
          <Field label="Borc tarixi">
            <Input required type="date" value={form.debtDate} onChange={(e) => setForm({ ...form, debtDate: e.target.value })} />
          </Field>
        </div>
        <Field label="Son ödəmə tarixi">
          <Input type="date" value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
        </Field>
        <Field label="Təsvir">
          <Input maxLength={255} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </Field>
        <Field label="Qeyd">
          <Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </Field>
      </form>
    </Modal>
  );
}

function PaymentModal({ debt, storeName, canNotify, onClose, onSaved }: { debt: Debt; storeName: string; canNotify: boolean; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ amount: String(debt.remainingAmount), paymentMethod: "CASH" as PaymentMethod, description: "", note: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<Payment | null>(null);
  const amountNumber = Number(form.amount);
  const exceeds = amountNumber > debt.remainingAmount + 1e-9;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setDone(await api<Payment>(`/api/debts/${debt.id}/payments`, { method: "POST", json: { ...form, description: form.description || null, note: form.note || null } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Ödəniş qeydə alınmadı");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    const remaining = done.remainingAfter ?? 0;
    const text = paymentConfirmationText(storeName, debt.partyName, done.amount, remaining, done.receiptUrl);
    const wa = canNotify ? waLink(debt.partyPhone, text) : null;
    const shareFile = async () => {
      setError(null);
      try {
        const shared = await shareProtectedFile(`/api/payments/${done.id}/receipt`, `cek-${done.id.slice(0, 8)}.pdf`, text);
        if (!shared) setError("Bu brauzer faylı paylaşa bilmir — “WhatsApp ilə çek göndər” düyməsindən istifadə edin");
      } catch (e) {
        setError(e instanceof ApiError ? e.message : "Çek paylaşılmadı");
      }
    };
    return (
      <Modal open title="Ödəniş qeydə alındı" onClose={onSaved} footer={<Button variant="primary" onClick={onSaved}>Bağla</Button>}>
        <div className="mb-5 rounded-2xl bg-emerald-50 px-5 py-4 text-center ring-1 ring-emerald-600/20">
          <p className="text-sm text-emerald-700">{debt.partyName} · {METHOD_LABEL[done.paymentMethod]}</p>
          <p className="mt-1 font-serif text-3xl font-semibold text-emerald-700">{formatMoney(done.amount)}</p>
          <p className="mt-1 text-sm text-slate-600">{remaining > 0 ? `Qalıq borc: ${formatMoney(remaining)}` : "Borc tam ödənildi"}</p>
        </div>
        {error && <div className="mb-4"><Alert>{error}</Alert></div>}
        <div className="grid gap-2 sm:grid-cols-2">
          <Button variant="outline" onClick={() => download(`/api/payments/${done.id}/receipt`, "cek.pdf").catch((e) => setError(e instanceof ApiError ? e.message : "Çek yüklənmədi"))}>
            Çeki yüklə (PDF)
          </Button>
          {wa ? (
            <a href={wa} target="_blank" rel="noopener noreferrer" className="inline-flex items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-emerald-500">
              <WhatsAppIcon /> WhatsApp ilə çek göndər
            </a>
          ) : (
            <p className="self-center text-center text-xs text-slate-400">{canNotify ? "WhatsApp üçün telefon nömrəsi yoxdur" : ""}</p>
          )}
          {canNotify && canShareFiles() && (
            <Button variant="outline" className="sm:col-span-2" onClick={shareFile}>PDF faylı paylaş (WhatsApp və s.)</Button>
          )}
        </div>
        {wa && <p className="mt-3 text-xs text-slate-400">Mesajda müştərinin çeki açması üçün təhlükəsiz link olur — müştəri basanda çek PDF kimi açılır.</p>}
      </Modal>
    );
  }

  return (
    <Modal
      open
      title="Ödəniş qəbulu"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="payment-form" disabled={busy || exceeds || !(amountNumber > 0)}>{busy ? "Saxlanılır…" : "Ödənişi qeydə al"}</Button></>}
    >
      <div className="mb-5 flex items-center justify-between rounded-2xl bg-navy-800 px-5 py-4 text-white">
        <div>
          <p className="text-xs text-white/60">{debt.partyName}</p>
          <p className="text-xs text-white/50">Qalıq borc</p>
        </div>
        <p className="font-serif text-2xl font-semibold text-gold-400">{formatMoney(debt.remainingAmount)}</p>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="payment-form" onSubmit={submit} className="space-y-4">
        <Field label="Məbləğ (₼)" error={exceeds ? "Qalıq borcdan çox ola bilməz" : undefined}>
          <Input required type="number" min="0.01" max={debt.remainingAmount} step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
        </Field>
        <Field label="Ödəniş üsulu">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" role="radiogroup" aria-label="Ödəniş üsulu">
            {METHODS.map((m) => (
              <button
                type="button"
                key={m}
                role="radio"
                aria-checked={form.paymentMethod === m}
                onClick={() => setForm({ ...form, paymentMethod: m })}
                className={`rounded-xl border px-3 py-2.5 text-sm font-medium transition-all ${form.paymentMethod === m ? "border-gold-500 bg-gold-500/10 text-navy-800 shadow-glow" : "border-slate-300 text-slate-600 hover:border-slate-400"}`}
              >
                {METHOD_LABEL[m]}
              </button>
            ))}
          </div>
        </Field>
        <Field label="Təsvir">
          <Input maxLength={255} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder={form.paymentMethod === "TRANSFER" ? "Məs: M10 / bank əməliyyat nömrəsi" : "Məs: Qismən ödəniş"} />
        </Field>
        <Field label="Qeyd">
          <Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </Field>
        <div className="flex flex-wrap gap-2 pt-1">
          <span className="text-xs text-slate-500">Sürətli seçim:</span>
          <button type="button" className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-200" onClick={() => setForm({ ...form, amount: String(debt.remainingAmount) })}>Tam ödəniş</button>
          <button type="button" className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-200" onClick={() => setForm({ ...form, amount: String(Math.round(debt.remainingAmount / 2 * 100) / 100) })}>Yarısı</button>
        </div>
      </form>
    </Modal>
  );
}

function HistoryModal({ debt, onClose }: { debt: Debt; onClose: () => void }) {
  const [entries, setEntries] = useState<LedgerEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api<LedgerEntry[]>(`/api/debts/${debt.id}/history`).then(setEntries).catch((e) => setError(e instanceof ApiError ? e.message : "Tarixçə yüklənmədi"));
  }, [debt.id]);

  const labels: Record<LedgerEntry["type"], string> = {
    DEBT_CREATED: "Borc yaradıldı",
    DEBT_ADJUSTED: "Məbləğ dəyişdirildi",
    PAYMENT_CASH: "Nağd ödəniş",
    PAYMENT_CARD: "Kart ödənişi",
    PAYMENT_TRANSFER: "Köçürmə ilə ödəniş",
    PAYMENT_OTHER: "Digər ödəniş",
  };

  return (
    <Modal open title={`Tarixçə — ${debt.partyName}`} onClose={onClose}>
      {error && <Alert>{error}</Alert>}
      {!entries && !error && <Spinner />}
      {entries && (
        <ol className="relative space-y-5 border-l-2 border-slate-200 pl-6">
          {entries.map((e) => (
            <li key={e.id} className="relative">
              <span className={`absolute -left-[31px] top-1 h-3.5 w-3.5 rounded-full ring-4 ring-white ${e.type.startsWith("PAYMENT") ? "bg-emerald-500" : "bg-navy-800"}`} />
              <p className="text-sm font-medium text-navy-800">{labels[e.type]}</p>
              <p className="text-xs text-slate-500">{formatDateTime(e.createdAt)} · {e.actorName}</p>
              <p className="mt-1 text-sm">
                <span className="tabular-nums">{formatMoney(e.amount)}</span>
                <span className="ml-2 text-xs text-slate-500">qalıq: {formatMoney(e.balanceAfter)}</span>
              </p>
              {e.paymentId && (
                <button
                  className="mt-1 text-xs font-medium text-gold-600 hover:text-gold-500"
                  onClick={() => download(`/api/payments/${e.paymentId}/receipt`, "cek.pdf").catch((err) => setError(err instanceof ApiError ? err.message : "Çek yüklənmədi"))}
                >
                  Çek (PDF) →
                </button>
              )}
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}
