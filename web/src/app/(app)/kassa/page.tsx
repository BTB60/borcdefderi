"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import type { CashAccount, CashKind, CashMovement, DayReport, MovementKind } from "@/lib/types";

const KIND_LABEL: Record<CashKind, string> = { CASH: "Nağd kassa", BANK: "Bank hesabı", CARD: "Kart hesabı" };
const MOVE_LABEL: Record<MovementKind, string> = {
  OPENING: "Kassa açılışı",
  DEPOSIT: "Mədaxil",
  WITHDRAWAL: "Məxaric",
  TRANSFER_IN: "Köçürmə (daxil)",
  TRANSFER_OUT: "Köçürmə (çıxış)",
  DEBT_COLLECTION: "Borc ödənişi alındı",
  SUPPLIER_PAYMENT: "Şirkətə borc ödənildi",
  EXPENSE: "Xərc",
  EXPENSE_REVERSAL: "Xərc geri qaytarıldı",
  PAYMENT_REVERSAL: "Ödəniş ləğvi / düzəlişi",
  SALE: "Satış",
  SALE_REFUND: "Satış qaytarıldı",
  COUNT_ADJUSTMENT: "Sayım fərqi",
};
const KIND_GRADIENT: Record<CashKind, string> = {
  CASH: "from-emerald-500 to-green-700",
  BANK: "from-sky-600 to-indigo-700",
  CARD: "from-violet-600 to-purple-800",
};

type Action = "opening" | "deposit" | "withdraw" | "transfer" | "count" | "account";

/** Kassa və bank: balances, money in / out, transfers, count and end-of-day report. */
export default function CashPage() {
  const { isOwner, can } = useAuth();
  const [accounts, setAccounts] = useState<CashAccount[] | null>(null);
  const [moves, setMoves] = useState<CashMovement[] | null>(null);
  const [day, setDay] = useState<DayReport | null>(null);
  const [tab, setTab] = useState<"moves" | "day">("moves");
  const [filter, setFilter] = useState({ accountId: "", from: "", to: "", kind: "" });
  const [dayDate, setDayDate] = useState(todayBaku());
  const [action, setAction] = useState<{ type: Action; accountId?: string; edit?: CashAccount } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const qs = new URLSearchParams();
      Object.entries(filter).forEach(([k, v]) => v && qs.set(k, v));
      const [a, m] = await Promise.all([api<CashAccount[]>("/api/cash/accounts"), api<CashMovement[]>(`/api/cash/movements?${qs}`)]);
      setAccounts(a);
      setMoves(m);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yüklənmədi");
    }
  }, [filter]);

  const loadDay = useCallback(() => {
    api<DayReport>(`/api/cash/day-report?date=${dayDate}`).then(setDay).catch((e) => setError(e instanceof ApiError ? e.message : "Hesabat yüklənmədi"));
  }, [dayDate]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { if (tab === "day") loadDay(); }, [tab, loadDay]);

  if (!isOwner && !can("CASH")) {
    return <Card><EmptyState title="Kassa bölməsi üçün icazəniz yoxdur" text="Mağaza sahibindən “Kassa və bank” icazəsi istəyin." /></Card>;
  }

  const total = (k: CashKind) => (accounts ?? []).filter((a) => a.kind === k).reduce((s, a) => s + a.balance, 0);
  const done = (msg: string) => { setAction(null); setNotice(msg); load(); if (tab === "day") loadDay(); };

  return (
    <>
      <PageHeader
        title="Kassa və bank"
        subtitle="Pulun harada olduğu və hərəkəti. Köçürmə gəlir və ya xərc sayılmır."
        actions={
          <>
            <Button variant="outline" onClick={() => setAction({ type: "transfer" })}>Köçürmə</Button>
            <Button variant="outline" onClick={() => setAction({ type: "withdraw" })}>Məxaric</Button>
            <Button variant="gold" onClick={() => setAction({ type: "deposit" })}>+ Mədaxil</Button>
          </>
        }
      />
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <section className="mb-6 grid gap-4 sm:grid-cols-3">
        {(["CASH", "BANK", "CARD"] as CashKind[]).map((k) => (
          <div key={k} className={`rounded-3xl bg-gradient-to-br ${KIND_GRADIENT[k]} p-5 text-white shadow-lg`}>
            <p className="text-sm text-white/80">{{ CASH: "Nağd kassalar", BANK: "Bank hesabları", CARD: "Kart hesabları" }[k]}</p>
            <p className={`mt-1 font-serif text-3xl font-semibold tabular-nums ${total(k) < 0 ? "text-rose-200" : ""}`}>{formatMoney(total(k))}</p>
          </div>
        ))}
      </section>

      <Card className="mb-6">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-serif text-lg font-semibold text-navy-800">Hesablar</h2>
          {isOwner && <Button variant="outline" onClick={() => setAction({ type: "account" })}>+ Hesab</Button>}
        </div>
        {!accounts ? <Spinner /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Ad</Th><Th>Növ</Th><Th className="text-right">Qalıq</Th><Th className="text-right">Əməliyyat</Th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {accounts.map((a) => (
                <tr key={a.id} className={a.active ? "" : "opacity-50"}>
                  <Td><span className="font-medium text-navy-800">{a.name}</span>{a.isDefault && <Badge tone="gold">əsas</Badge>}{!a.active && <Badge tone="slate">deaktiv</Badge>}</Td>
                  <Td>{KIND_LABEL[a.kind]}</Td>
                  <Td className={`text-right font-semibold tabular-nums ${a.balance < 0 ? "text-rose-600" : "text-navy-800"}`}>{formatMoney(a.balance)}</Td>
                  <Td className="text-right">
                    <div className="flex justify-end gap-1">
                      {isOwner && <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setAction({ type: "opening", accountId: a.id })}>Açılış</Button>}
                      <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setAction({ type: "count", accountId: a.id })}>Sayım</Button>
                      {isOwner && <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setAction({ type: "account", edit: a })}>Redaktə</Button>}
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
        <p className="mt-3 text-xs text-slate-400">Borc ödənişləri avtomatik düşür: nağd → əsas kassa, kart → kart hesabı, köçürmə/digər → bank. Başlanğıcda kassadakı real pulu “Açılış” ilə daxil edin.</p>
      </Card>

      <Card>
        <div className="mb-4 inline-flex rounded-xl bg-slate-100 p-1" role="tablist">
          {([["moves", "Hərəkətlər"], ["day", "Gün sonu hesabatı"]] as const).map(([k, l]) => (
            <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)} className={`rounded-lg px-4 py-1.5 text-sm font-medium ${tab === k ? "bg-white text-navy-800 shadow" : "text-slate-500"}`}>{l}</button>
          ))}
        </div>
        {tab === "moves" && (
          <>
            <div className="mb-4 grid gap-3 sm:grid-cols-4">
              <Select value={filter.accountId} onChange={(e) => setFilter({ ...filter, accountId: e.target.value })} aria-label="Hesab">
                <option value="">Bütün hesablar</option>
                {accounts?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </Select>
              <Select value={filter.kind} onChange={(e) => setFilter({ ...filter, kind: e.target.value })} aria-label="Növ">
                <option value="">Bütün növlər</option>
                {(Object.keys(MOVE_LABEL) as MovementKind[]).map((k) => <option key={k} value={k}>{MOVE_LABEL[k]}</option>)}
              </Select>
              <Input type="date" value={filter.from} onChange={(e) => setFilter({ ...filter, from: e.target.value })} aria-label="Başlanğıc" />
              <Input type="date" value={filter.to} onChange={(e) => setFilter({ ...filter, to: e.target.value })} aria-label="Son" />
            </div>
            {!moves ? <Spinner /> : moves.length === 0 ? <EmptyState title="Hərəkət yoxdur" /> : (
              <Table>
                <thead className="bg-slate-50/70"><tr><Th>Tarix</Th><Th>Hesab</Th><Th>Növ</Th><Th>Qeyd</Th><Th>İşçi</Th><Th className="text-right">Məbləğ</Th></tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {moves.map((m) => (
                    <tr key={m.id}>
                      <Td>{formatDate(m.occurredOn)}<div className="text-xs text-slate-400">{formatDateTime(m.createdAt)}</div></Td>
                      <Td>{m.accountName}</Td>
                      <Td className="text-xs">{MOVE_LABEL[m.kind]}</Td>
                      <Td className="max-w-[16rem] truncate text-xs text-slate-500">{m.note ?? "—"}</Td>
                      <Td className="text-xs">{m.actorName}</Td>
                      <Td className={`text-right font-semibold tabular-nums ${m.direction === "IN" ? "text-emerald-700" : "text-rose-600"}`}>{m.direction === "IN" ? "+" : "−"}{formatMoney(m.amount)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}
          </>
        )}
        {tab === "day" && (
          <>
            <div className="mb-4 flex gap-2">
              <Input type="date" max={todayBaku()} value={dayDate} onChange={(e) => setDayDate(e.target.value)} aria-label="Tarix" />
            </div>
            {!day ? <Spinner /> : (
              <Table>
                <thead className="bg-slate-50/70"><tr><Th>Hesab</Th><Th className="text-right">Gün əvvəli</Th><Th className="text-right">Daxil</Th><Th className="text-right">Çıxış</Th><Th className="text-right">Gün sonu</Th></tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {day.accounts.map((a) => (
                    <tr key={a.accountId}>
                      <Td>
                        <span className="font-medium text-navy-800">{a.accountName}</span>
                        <div className="text-xs text-slate-400">{Object.entries(a.byKind).map(([k, v]) => `${MOVE_LABEL[k as MovementKind] ?? k}: ${formatMoney(v)}`).join(" · ") || "hərəkət yoxdur"}</div>
                      </Td>
                      <Td className="text-right tabular-nums">{formatMoney(a.opening)}</Td>
                      <Td className="text-right tabular-nums text-emerald-700">{formatMoney(a.in)}</Td>
                      <Td className="text-right tabular-nums text-rose-600">{formatMoney(a.out)}</Td>
                      <Td className="text-right font-semibold tabular-nums">{formatMoney(a.closing)}</Td>
                    </tr>
                  ))}
                  <tr className="bg-slate-50 font-semibold">
                    <Td>Cəmi</Td>
                    <Td className="text-right tabular-nums">{formatMoney(day.totalOpening)}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(day.totalIn)}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(day.totalOut)}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(day.totalClosing)}</Td>
                  </tr>
                </tbody>
              </Table>
            )}
            <p className="mt-3 text-xs text-slate-400">Kassalararası köçürmə hesabların arasında görünür, cəmi dəyişmir.</p>
          </>
        )}
      </Card>

      {action && accounts && (
        action.type === "account"
          ? <AccountModal edit={action.edit} onClose={() => setAction(null)} onDone={done} />
          : <MoveModal type={action.type} accounts={accounts.filter((a) => a.active)} accountId={action.accountId} onClose={() => setAction(null)} onDone={done} />
      )}
    </>
  );
}

function MoveModal({ type, accounts, accountId, onClose, onDone }: { type: Exclude<Action, "account">; accounts: CashAccount[]; accountId?: string; onClose: () => void; onDone: (msg: string) => void }) {
  const [form, setForm] = useState({ accountId: accountId ?? accounts.find((a) => a.kind === "CASH" && a.isDefault)?.id ?? accounts[0]?.id ?? "", toAccountId: "", amount: "", date: todayBaku(), note: "" });
  const [requestId] = useState(() => crypto.randomUUID());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const title = { opening: "Kassa açılışı", deposit: "Mədaxil", withdraw: "Məxaric", transfer: "Köçürmə", count: "Kassa sayımı (inventarizasiya)" }[type];
  const acc = accounts.find((a) => a.id === form.accountId);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      if (type === "transfer") {
        await api("/api/cash/transfer", { method: "POST", json: { fromAccountId: form.accountId, toAccountId: form.toAccountId, amount: form.amount, date: form.date, note: form.note || null, clientRequestId: requestId } });
        onDone(`Köçürmə edildi: ${formatMoney(Number(form.amount))}`);
      } else if (type === "count") {
        const r = await api<{ difference: number }>("/api/cash/count", { method: "POST", json: { accountId: form.accountId, countedAmount: form.amount, note: form.note || null } });
        onDone(r.difference === 0 ? "Sayım uyğundur, fərq yoxdur" : `Sayım fərqi qeydə alındı: ${formatMoney(r.difference)}`);
      } else {
        await api(`/api/cash/${type}`, { method: "POST", json: { accountId: form.accountId, amount: form.amount, date: form.date, note: form.note || null, clientRequestId: requestId } });
        onDone(`${title}: ${formatMoney(Number(form.amount))}`);
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Əməliyyat alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title={title} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="move-form" disabled={busy}>{busy ? "Saxlanılır…" : "Təsdiqlə"}</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="move-form" onSubmit={submit} className="space-y-4">
        <Field label={type === "transfer" ? "Haradan" : "Hesab"}>
          <Select required value={form.accountId} onChange={(e) => setForm({ ...form, accountId: e.target.value })}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name} — {formatMoney(a.balance)}</option>)}
          </Select>
        </Field>
        {type === "transfer" && (
          <Field label="Haraya">
            <Select required value={form.toAccountId} onChange={(e) => setForm({ ...form, toAccountId: e.target.value })}>
              <option value="">Seçin…</option>
              {accounts.filter((a) => a.id !== form.accountId).map((a) => <option key={a.id} value={a.id}>{a.name} — {formatMoney(a.balance)}</option>)}
            </Select>
          </Field>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={type === "count" ? "Sayılan pul (₼)" : "Məbləğ (₼)"} hint={type === "count" && acc ? `Sistemdə: ${formatMoney(acc.balance)}` : undefined}>
            <Input required autoFocus type="number" min={type === "count" ? "0" : "0.01"} step="0.01" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
          </Field>
          {type !== "count" && (
            <Field label="Tarix"><Input required type="date" max={todayBaku()} value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></Field>
          )}
        </div>
        <Field label="Qeyd"><Input maxLength={500} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} placeholder={type === "deposit" ? "Məs: sahib pul qoydu" : type === "withdraw" ? "Məs: sahib pul götürdü" : ""} /></Field>
        {type === "opening" && <p className="text-xs text-slate-500">Açılış hər hesab üçün bir dəfə daxil edilir. Sonrakı fərqləri “Sayım” ilə düzəldin.</p>}
        {(type === "deposit" || type === "withdraw") && <p className="text-xs text-slate-500">Mədaxil və məxaric gəlir və ya xərc sayılmır (sahibin pul qoyması/götürməsi). Xərc üçün “Xərclər” bölməsindən istifadə edin.</p>}
      </form>
    </Modal>
  );
}

function AccountModal({ edit, onClose, onDone }: { edit?: CashAccount; onClose: () => void; onDone: (msg: string) => void }) {
  const [form, setForm] = useState({ name: edit?.name ?? "", kind: edit?.kind ?? ("CASH" as CashKind), isDefault: edit?.isDefault ?? false, active: edit?.active ?? true });
  const [error, setError] = useState<string | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api(edit ? `/api/cash/accounts/${edit.id}` : "/api/cash/accounts", { method: edit ? "PUT" : "POST", json: form });
      onDone(edit ? "Hesab yeniləndi" : "Hesab yaradıldı");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Saxlanmadı");
    }
  }
  return (
    <Modal open title={edit ? `Redaktə — ${edit.name}` : "Yeni hesab"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="acc-form">Saxla</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="acc-form" onSubmit={submit} className="space-y-4">
        <Field label="Ad"><Input required maxLength={100} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Məs: Kassa 2, Kapital Bank" /></Field>
        {!edit && (
          <Field label="Növ">
            <Select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as CashKind })}>
              {(Object.keys(KIND_LABEL) as CashKind[]).map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
            </Select>
          </Field>
        )}
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={form.isDefault} onChange={(e) => setForm({ ...form, isDefault: e.target.checked })} /> Əsas hesab (ödənişlər bura düşür)</label>
        {edit && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Aktiv</label>}
      </form>
    </Modal>
  );
}
