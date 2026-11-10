"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, API_URL, api, getToken, openProtected } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { METHOD_LABEL, formatDate, formatMoney, todayBaku } from "@/lib/format";
import type { Budget, CashAccount, Expense, ExpenseCategory, ExpensePage, ExpenseSummary, PaymentMethod, RecurringExpense } from "@/lib/types";

const METHODS: PaymentMethod[] = ["CASH", "CARD", "TRANSFER", "OTHER"];
const FREQ_LABEL = { DAILY: "Gündəlik", WEEKLY: "Həftəlik", MONTHLY: "Aylıq", YEARLY: "İllik" } as const;
type Tab = "list" | "recurring" | "budget" | "analysis";

/** Xərclər: expenses, recurring expenses, monthly budgets and analysis. Never mixed with debts. */
export default function ExpensesPage() {
  const { isOwner, can } = useAuth();
  const [tab, setTab] = useState<Tab>("list");
  const [categories, setCategories] = useState<ExpenseCategory[]>([]);
  const [accounts, setAccounts] = useState<CashAccount[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadMeta = useCallback(() => {
    api<ExpenseCategory[]>("/api/expense-categories").then(setCategories).catch((e) => setError(e instanceof ApiError ? e.message : "Kateqoriyalar yüklənmədi"));
    api<CashAccount[]>("/api/cash/accounts").then(setAccounts).catch(() => setAccounts([]));
  }, []);
  useEffect(loadMeta, [loadMeta]);

  if (!isOwner && !can("EXPENSES")) {
    return <Card><EmptyState title="Xərclər bölməsi üçün icazəniz yoxdur" text="Mağaza sahibindən “Xərclər və büdcə” icazəsi istəyin." /></Card>;
  }

  return (
    <>
      <PageHeader title="Xərclər" subtitle="Mağazanın xərcləri — borclara və borc ödənişlərinə qarışmır" />
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <div className="mb-5 inline-flex flex-wrap rounded-xl bg-white p-1 shadow-sm ring-1 ring-slate-200" role="tablist">
        {([["list", "Xərclər"], ["recurring", "Təkrarlanan"], ["budget", "Büdcə"], ["analysis", "Analiz"]] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)} className={`rounded-lg px-4 py-2 text-sm font-medium transition ${tab === k ? "bg-navy-800 text-white shadow" : "text-slate-600 hover:text-navy-800"}`}>{l}</button>
        ))}
      </div>
      {tab === "list" && <ExpenseList categories={categories} accounts={accounts} onNotice={setNotice} onCategory={loadMeta} />}
      {tab === "recurring" && <RecurringList categories={categories} accounts={accounts} onNotice={setNotice} />}
      {tab === "budget" && <BudgetList categories={categories} onNotice={setNotice} />}
      {tab === "analysis" && <Analysis />}
    </>
  );
}

function ExpenseList({ categories, accounts, onNotice, onCategory }: { categories: ExpenseCategory[]; accounts: CashAccount[]; onNotice: (m: string) => void; onCategory: () => void }) {
  const today = todayBaku();
  const [filter, setFilter] = useState({ from: today.slice(0, 8) + "01", to: today, categoryId: "", q: "" });
  const [data, setData] = useState<ExpensePage | null>(null);
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Expense | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  const load = useCallback(() => {
    const qs = new URLSearchParams({ page: String(page), size: "50" });
    Object.entries(filter).forEach(([k, v]) => v && qs.set(k, v));
    api<ExpensePage>(`/api/expenses?${qs}`).then(setData).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [filter, page]);
  useEffect(load, [load]);

  async function remove(e: Expense) {
    if (!(await confirm(<>“{e.title}” ({formatMoney(e.amount)}) silinsin? Pul {e.accountName} hesabına geri yazılacaq; tarixçə qalır.</>, { danger: true, confirmLabel: "Sil" }))) return;
    try {
      await api(`/api/expenses/${e.id}`, { method: "DELETE" });
      onNotice("Xərc silindi");
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Silinmədi");
    }
  }

  const pages = data ? Math.max(1, Math.ceil(data.total / 50)) : 1;
  return (
    <>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <section className="mb-5 grid gap-4 sm:grid-cols-3">
        <StatCard label="Seçilmiş dövrdə xərc" value={formatMoney(data?.sum)} tone="rose" hint={`${data?.total ?? 0} qeyd`} />
        <div className="sm:col-span-2 flex items-end justify-end gap-2">
          <Button variant="gold" onClick={() => setEditing("new")}>+ Xərc əlavə et</Button>
        </div>
      </section>
      <Card>
        <div className="mb-4 grid gap-3 sm:grid-cols-5">
          <Input placeholder="Ad, ödəniş edilən şəxs, qeyd" value={filter.q} onChange={(e) => { setPage(0); setFilter({ ...filter, q: e.target.value }); }} aria-label="Axtarış" />
          <Select value={filter.categoryId} onChange={(e) => { setPage(0); setFilter({ ...filter, categoryId: e.target.value }); }} aria-label="Kateqoriya">
            <option value="">Bütün kateqoriyalar</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
          <Input type="date" value={filter.from} onChange={(e) => { setPage(0); setFilter({ ...filter, from: e.target.value }); }} aria-label="Başlanğıc" />
          <Input type="date" value={filter.to} onChange={(e) => { setPage(0); setFilter({ ...filter, to: e.target.value }); }} aria-label="Son" />
          <Button variant="outline" onClick={() => exportCsv(data?.items ?? [])} disabled={!data?.items.length}>Excel (CSV)</Button>
        </div>
        {!data ? <Spinner /> : data.items.length === 0 ? <EmptyState title="Xərc yoxdur" text="“+ Xərc əlavə et” ilə başlayın." /> : (
          <>
            <Table>
              <thead className="bg-slate-50/70"><tr><Th>Tarix</Th><Th>Xərc</Th><Th>Kateqoriya</Th><Th>Hesab</Th><Th>Kimə</Th><Th className="text-right">Məbləğ</Th><Th className="text-right">Əməliyyat</Th></tr></thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((e) => (
                  <tr key={e.id}>
                    <Td>{formatDate(e.expenseDate)}</Td>
                    <Td><span className="font-medium text-navy-800">{e.title}</span>{e.recurringId && <Badge tone="sky">təkrarlanan</Badge>}{e.attachmentCount > 0 && <Badge tone="slate">📎 {e.attachmentCount}</Badge>}<div className="text-xs text-slate-400">{e.createdByName}{e.note ? ` · ${e.note}` : ""}</div></Td>
                    <Td className="text-xs">{e.categoryName}</Td>
                    <Td className="text-xs">{e.accountName}<div className="text-slate-400">{METHOD_LABEL[e.paymentMethod]}</div></Td>
                    <Td className="text-xs">{e.payee ?? "—"}</Td>
                    <Td className="text-right font-semibold tabular-nums text-rose-600">{formatMoney(e.amount)}</Td>
                    <Td className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setEditing(e)}>Redaktə</Button>
                        <Button variant="ghost" className="px-2.5 py-1.5 text-rose-600" onClick={() => remove(e)}>Sil</Button>
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <div className="mt-5 flex items-center justify-between text-sm text-slate-500">
              <span>Səhifə {page + 1} / {pages}</span>
              <div className="flex gap-2">
                <Button variant="outline" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Geri</Button>
                <Button variant="outline" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>İrəli</Button>
              </div>
            </div>
          </>
        )}
      </Card>
      {editing && (
        <ExpenseModal expense={editing === "new" ? null : editing} categories={categories} accounts={accounts} onCategory={onCategory}
          onClose={() => setEditing(null)} onSaved={(m) => { setEditing(null); onNotice(m); load(); }} />
      )}
      {confirmDialog}
    </>
  );
}

function exportCsv(items: Expense[]) {
  const head = ["Tarix", "Xərc", "Kateqoriya", "Hesab", "Ödəniş üsulu", "Kimə", "Məbləğ", "Qeyd"];
  const esc = (v: string | number | null) => `"${String(v ?? "").replace(/"/g, '""')}"`;
  const rows = items.map((e) => [e.expenseDate, e.title, e.categoryName, e.accountName, METHOD_LABEL[e.paymentMethod], e.payee, e.amount.toFixed(2), e.note].map(esc).join(";"));
  const blob = new Blob(["﻿" + [head.map(esc).join(";"), ...rows].join("\r\n")], { type: "text/csv;charset=utf-8" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `xercler-${todayBaku()}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}

function ExpenseModal({ expense, categories, accounts, onCategory, onClose, onSaved }: { expense: Expense | null; categories: ExpenseCategory[]; accounts: CashAccount[]; onCategory: () => void; onClose: () => void; onSaved: (m: string) => void }) {
  const [form, setForm] = useState({
    title: expense?.title ?? "", categoryId: expense?.categoryId ?? "", amount: expense ? String(expense.amount) : "",
    expenseDate: expense?.expenseDate ?? todayBaku(), paymentMethod: (expense?.paymentMethod ?? "CASH") as PaymentMethod,
    accountId: expense?.accountId ?? "", payee: expense?.payee ?? "", note: expense?.note ?? "",
  });
  const [file, setFile] = useState<File | null>(null);
  const [newCat, setNewCat] = useState("");
  const [requestId] = useState(() => crypto.randomUUID());
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function addCategory() {
    if (!newCat.trim()) return;
    try {
      const c = await api<ExpenseCategory>("/api/expense-categories", { method: "POST", json: { name: newCat.trim() } });
      setForm({ ...form, categoryId: c.id });
      setNewCat("");
      onCategory();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Kateqoriya yaradılmadı");
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    setErrors({});
    const body = { ...form, accountId: form.accountId || null, payee: form.payee || null, note: form.note || null };
    try {
      const saved = expense
        ? await api<Expense>(`/api/expenses/${expense.id}`, { method: "PUT", json: { ...body, version: expense.version } })
        : await api<Expense>("/api/expenses", { method: "POST", json: { ...body, clientRequestId: requestId } });
      if (file) {
        const fd = new FormData();
        fd.append("file", file);
        const token = getToken();
        const res = await fetch(`${API_URL}/api/expenses/${saved.id}/attachments`, { method: "POST", body: fd, headers: token ? { Authorization: `Bearer ${token}` } : {} });
        if (!res.ok) {
          const d = await res.json().catch(() => ({}));
          throw new ApiError(res.status, d.error ?? "ERROR", `Xərc saxlandı, amma fayl yüklənmədi: ${d.message ?? "xəta"}`);
        }
      }
      onSaved(expense ? "Xərc yeniləndi" : `Xərc əlavə edildi: ${formatMoney(saved.amount)}`);
    } catch (err) {
      if (err instanceof ApiError) { setError(err.message); setErrors(err.fields ?? {}); } else setError("Saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title={expense ? `Redaktə — ${expense.title}` : "Yeni xərc"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="expense-form" variant="gold" disabled={busy}>{busy ? "Saxlanılır…" : "Saxla"}</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="expense-form" onSubmit={submit} className="space-y-4">
        <Field label="Xərcin adı" error={errors.title}><Input required maxLength={150} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="Məs: Noyabr icarəsi" /></Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Kateqoriya" error={errors.categoryId}>
            <Select required value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
              <option value="">Seçin…</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </Select>
          </Field>
          <Field label="Yeni kateqoriya" hint="İstəyə bağlı">
            <div className="flex gap-2"><Input value={newCat} maxLength={100} onChange={(e) => setNewCat(e.target.value)} /><Button type="button" variant="outline" onClick={addCategory}>+</Button></div>
          </Field>
          <Field label="Məbləğ (₼)" error={errors.amount}><Input required type="number" min="0.01" step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></Field>
          <Field label="Tarix"><Input required type="date" max={todayBaku()} value={form.expenseDate} onChange={(e) => setForm({ ...form, expenseDate: e.target.value })} /></Field>
          <Field label="Ödəniş üsulu">
            <Select value={form.paymentMethod} onChange={(e) => setForm({ ...form, paymentMethod: e.target.value as PaymentMethod, accountId: "" })}>
              {METHODS.map((m) => <option key={m} value={m}>{METHOD_LABEL[m]}</option>)}
            </Select>
          </Field>
          <Field label="Hesab" hint="Boş qalsa: üsula görə əsas hesab">
            <Select value={form.accountId} onChange={(e) => setForm({ ...form, accountId: e.target.value })}>
              <option value="">Avtomatik</option>
              {accounts.filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </Select>
          </Field>
        </div>
        <Field label="Ödəniş edilən şəxs / şirkət"><Input maxLength={150} value={form.payee} onChange={(e) => setForm({ ...form, payee: e.target.value })} /></Field>
        <Field label="Qeyd"><Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></Field>
        <Field label="Qəbz şəkli / sənəd" hint="PNG, JPG və ya PDF, ən çox 5 MB">
          <input type="file" accept="image/png,image/jpeg,application/pdf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-sm" />
        </Field>
        {expense && expense.attachmentCount > 0 && <ExpenseFiles id={expense.id} />}
      </form>
    </Modal>
  );
}

function ExpenseFiles({ id }: { id: string }) {
  const [files, setFiles] = useState<{ id: string; originalName: string | null }[]>([]);
  useEffect(() => { api<{ id: string; originalName: string | null }[]>(`/api/expenses/${id}/attachments`).then(setFiles).catch(() => setFiles([])); }, [id]);
  return (
    <div className="text-sm">
      <p className="mb-1 text-xs text-slate-500">Əlavə olunmuş fayllar:</p>
      {files.map((f) => <button key={f.id} type="button" className="mr-3 text-gold-600 hover:underline" onClick={() => openProtected(`/api/expenses/${id}/attachments/${f.id}`)}>{f.originalName ?? "fayl"}</button>)}
    </div>
  );
}

function RecurringList({ categories, accounts, onNotice }: { categories: ExpenseCategory[]; accounts: CashAccount[]; onNotice: (m: string) => void }) {
  const [items, setItems] = useState<RecurringExpense[] | null>(null);
  const [editing, setEditing] = useState<RecurringExpense | "new" | null>(null);
  const [paying, setPaying] = useState<RecurringExpense | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => { api<RecurringExpense[]>("/api/recurring-expenses").then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi")); }, []);
  useEffect(load, [load]);
  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <p className="text-sm text-slate-500">Plan kimi saxlanır; “Ödə” basılanda faktiki xərc yaranır. Eyni dövr iki dəfə ödənmir.</p>
        <Button variant="gold" onClick={() => setEditing("new")}>+ Təkrarlanan xərc</Button>
      </div>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Təkrarlanan xərc yoxdur" text="Məs: hər ayın 1-i icarə, 5-i internet." /> : (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Ad</Th><Th>Tezlik</Th><Th>Növbəti tarix</Th><Th className="text-right">Məbləğ</Th><Th>Status</Th><Th className="text-right">Əməliyyat</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((r) => (
              <tr key={r.id} className={r.active ? "" : "opacity-50"}>
                <Td><span className="font-medium text-navy-800">{r.title}</span><div className="text-xs text-slate-400">{r.categoryName}{r.endDate ? ` · bitir ${formatDate(r.endDate)}` : ""}</div></Td>
                <Td>{FREQ_LABEL[r.frequency]}</Td>
                <Td className={r.due ? "font-semibold text-rose-600" : ""}>{formatDate(r.nextDueDate)}</Td>
                <Td className="text-right tabular-nums">{formatMoney(r.amount)}</Td>
                <Td>{!r.active ? <Badge tone="slate">deaktiv</Badge> : r.due ? <Badge tone="rose">ödəniləcək</Badge> : <Badge tone="emerald">planlı</Badge>}</Td>
                <Td className="text-right">
                  <div className="flex justify-end gap-1">
                    {r.active && <Button variant="primary" className="px-3 py-1.5" onClick={() => setPaying(r)}>Ödə</Button>}
                    <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setEditing(r)}>Redaktə</Button>
                  </div>
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      {editing && <RecurringModal item={editing === "new" ? null : editing} categories={categories} accounts={accounts} onClose={() => setEditing(null)} onSaved={(m) => { setEditing(null); onNotice(m); load(); }} />}
      {paying && <PayRecurringModal item={paying} accounts={accounts} onClose={() => setPaying(null)} onSaved={(m) => { setPaying(null); onNotice(m); load(); }} />}
    </Card>
  );
}

function RecurringModal({ item, categories, accounts, onClose, onSaved }: { item: RecurringExpense | null; categories: ExpenseCategory[]; accounts: CashAccount[]; onClose: () => void; onSaved: (m: string) => void }) {
  const [form, setForm] = useState({
    title: item?.title ?? "", categoryId: item?.categoryId ?? "", amount: item ? String(item.amount) : "", frequency: item?.frequency ?? "MONTHLY",
    startDate: item?.startDate ?? todayBaku(), endDate: item?.endDate ?? "", accountId: item?.accountId ?? "", payee: item?.payee ?? "", active: item?.active ?? true,
  });
  const [error, setError] = useState<string | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const body = { ...form, endDate: form.endDate || null, accountId: form.accountId || null, payee: form.payee || null };
      await api(item ? `/api/recurring-expenses/${item.id}` : "/api/recurring-expenses", { method: item ? "PUT" : "POST", json: body });
      onSaved(item ? "Yeniləndi" : "Təkrarlanan xərc əlavə edildi");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Saxlanmadı");
    }
  }
  return (
    <Modal open title={item ? `Redaktə — ${item.title}` : "Yeni təkrarlanan xərc"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="rec-form">Saxla</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="rec-form" onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2"><Field label="Ad"><Input required maxLength={150} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="Məs: İcarə" /></Field></div>
        <Field label="Kateqoriya"><Select required value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}><option value="">Seçin…</option>{categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select></Field>
        <Field label="Məbləğ (₼)"><Input required type="number" min="0.01" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></Field>
        <Field label="Tezlik"><Select value={form.frequency} onChange={(e) => setForm({ ...form, frequency: e.target.value as RecurringExpense["frequency"] })}>{Object.entries(FREQ_LABEL).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</Select></Field>
        <Field label="Hesab"><Select value={form.accountId} onChange={(e) => setForm({ ...form, accountId: e.target.value })}><option value="">Avtomatik</option>{accounts.filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
        <Field label="Başlama tarixi (ilk ödəniş günü)"><Input required type="date" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} /></Field>
        <Field label="Bitmə tarixi" hint="İstəyə bağlı"><Input type="date" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} /></Field>
        <Field label="Kimə"><Input maxLength={150} value={form.payee} onChange={(e) => setForm({ ...form, payee: e.target.value })} /></Field>
        {item && <label className="flex items-center gap-2 self-end text-sm"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Aktiv</label>}
      </form>
    </Modal>
  );
}

function PayRecurringModal({ item, accounts, onClose, onSaved }: { item: RecurringExpense; accounts: CashAccount[]; onClose: () => void; onSaved: (m: string) => void }) {
  const [form, setForm] = useState({ amount: String(item.amount), paidOn: todayBaku(), paymentMethod: "CASH" as PaymentMethod, accountId: item.accountId ?? "", note: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await api(`/api/recurring-expenses/${item.id}/pay`, { method: "POST", json: { periodDate: item.nextDueDate, amount: form.amount, paidOn: form.paidOn, paymentMethod: form.paymentMethod, accountId: form.accountId || null, note: form.note || null } });
      onSaved(`${item.title} (${formatDate(item.nextDueDate)}) ödənildi`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Ödənilmədi");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal open title={`Ödə — ${item.title}`} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="payrec" disabled={busy}>Ödənişi qeydə al</Button></>}>
      <p className="mb-4 text-sm text-slate-600">Dövr: <strong>{formatDate(item.nextDueDate)}</strong></p>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="payrec" onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <Field label="Məbləğ (₼)"><Input required type="number" min="0.01" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></Field>
        <Field label="Ödəniş tarixi"><Input required type="date" max={todayBaku()} value={form.paidOn} onChange={(e) => setForm({ ...form, paidOn: e.target.value })} /></Field>
        <Field label="Üsul"><Select value={form.paymentMethod} onChange={(e) => setForm({ ...form, paymentMethod: e.target.value as PaymentMethod })}>{METHODS.map((m) => <option key={m} value={m}>{METHOD_LABEL[m]}</option>)}</Select></Field>
        <Field label="Hesab"><Select value={form.accountId} onChange={(e) => setForm({ ...form, accountId: e.target.value })}><option value="">Avtomatik</option>{accounts.filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
        <div className="sm:col-span-2"><Field label="Qeyd"><Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></Field></div>
      </form>
    </Modal>
  );
}

function BudgetList({ categories, onNotice }: { categories: ExpenseCategory[]; onNotice: (m: string) => void }) {
  const [month, setMonth] = useState(todayBaku().slice(0, 7));
  const [items, setItems] = useState<Budget[] | null>(null);
  const [form, setForm] = useState({ categoryId: "", limit: "" });
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => { api<Budget[]>(`/api/budgets?month=${month}-01`).then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi")); }, [month]);
  useEffect(load, [load]);
  async function save(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      setItems(await api<Budget[]>("/api/budgets", { method: "PUT", json: { categoryId: form.categoryId, month: `${month}-01`, limit: form.limit } }));
      setForm({ categoryId: "", limit: "" });
      onNotice("Büdcə yadda saxlanıldı");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Saxlanmadı");
    }
  }
  async function remove(id: string) {
    await api(`/api/budgets/${id}`, { method: "DELETE" }).catch(() => undefined);
    load();
  }
  const bar = (b: Budget) => b.state === "OVER" ? "bg-rose-500" : b.state === "NEAR" ? "bg-amber-500" : "bg-emerald-500";
  return (
    <Card>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <form onSubmit={save} className="mb-5 grid gap-3 sm:grid-cols-4">
        <Input type="month" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Ay" />
        <Select required value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })} aria-label="Kateqoriya">
          <option value="">Kateqoriya…</option>
          {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </Select>
        <Input required type="number" min="0.01" step="0.01" placeholder="Aylıq limit (₼)" value={form.limit} onChange={(e) => setForm({ ...form, limit: e.target.value })} aria-label="Limit" />
        <Button type="submit" variant="gold">Limiti saxla</Button>
      </form>
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Bu ay üçün büdcə yoxdur" /> : (
        <ul className="space-y-4">
          {items.map((b) => (
            <li key={b.id}>
              <div className="mb-1 flex items-center justify-between text-sm">
                <span className="font-medium text-navy-800">{b.categoryName} {b.state === "OVER" ? <Badge tone="rose">limit keçilib</Badge> : b.state === "NEAR" ? <Badge tone="amber">limitə yaxınlaşır</Badge> : null}</span>
                <span className="tabular-nums">{formatMoney(b.actual)} / {formatMoney(b.limit)} <button className="ml-2 text-xs text-slate-400 hover:text-rose-600" onClick={() => remove(b.id)}>sil</button></span>
              </div>
              <div className="h-2.5 overflow-hidden rounded-full bg-slate-100"><div className={`h-full ${bar(b)}`} style={{ width: `${Math.min(100, b.percent)}%` }} /></div>
              <p className="mt-1 text-xs text-slate-400">{b.percent}% · qalıq {formatMoney(b.remaining)}</p>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function Analysis() {
  const today = todayBaku();
  const [range, setRange] = useState({ from: today.slice(0, 5) + "01-01", to: today });
  const [byCat, setByCat] = useState<ExpenseSummary | null>(null);
  const [byMonth, setByMonth] = useState<ExpenseSummary | null>(null);
  const [byDay, setByDay] = useState<ExpenseSummary | null>(null);
  useEffect(() => {
    const qs = `from=${range.from}&to=${range.to}`;
    api<ExpenseSummary>(`/api/expenses/summary?groupBy=category&${qs}`).then(setByCat).catch(() => setByCat(null));
    api<ExpenseSummary>(`/api/expenses/summary?groupBy=month&${qs}`).then(setByMonth).catch(() => setByMonth(null));
    api<ExpenseSummary>(`/api/expenses/summary?groupBy=day&from=${today.slice(0, 8)}01&to=${today}`).then(setByDay).catch(() => setByDay(null));
  }, [range, today]);
  const Bars = ({ s, label }: { s: ExpenseSummary | null; label: string }) => {
    if (!s) return <Spinner />;
    const max = Math.max(1, ...s.rows.map((r) => r.total));
    return (
      <Card>
        <div className="mb-3 flex items-baseline justify-between"><h3 className="font-serif text-lg font-semibold text-navy-800">{label}</h3><span className="text-sm tabular-nums text-slate-500">Cəmi {formatMoney(s.total)}</span></div>
        {s.rows.length === 0 ? <p className="text-sm text-slate-400">Məlumat yoxdur</p> : (
          <ul className="space-y-2">
            {s.rows.map((r) => (
              <li key={r.key} className="text-sm">
                <div className="flex justify-between"><span>{r.label}</span><span className="tabular-nums">{formatMoney(r.total)}</span></div>
                <div className="h-2 rounded-full bg-slate-100"><div className="h-full rounded-full bg-gradient-to-r from-violet-500 to-rose-500" style={{ width: `${(r.total / max) * 100}%` }} /></div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    );
  };
  return (
    <>
      <div className="mb-4 flex flex-wrap gap-3">
        <Input type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} aria-label="Başlanğıc" />
        <Input type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} aria-label="Son" />
      </div>
      <div className="grid gap-5 lg:grid-cols-2">
        <Bars s={byCat} label="Kateqoriyalar üzrə" />
        <Bars s={byMonth} label="Aylar üzrə" />
        <div className="lg:col-span-2"><Bars s={byDay} label="Bu ay — günlər üzrə" /></div>
      </div>
    </>
  );
}
