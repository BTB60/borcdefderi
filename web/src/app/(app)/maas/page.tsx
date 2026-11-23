"use client";

import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney, todayBaku } from "@/lib/format";

type Row = {
  userId: string; fullName: string; jobTitle: string | null; payType: "MONTHLY" | "HOURLY" | null; rate: number | null; latePenalty: number;
  days: number; minutes: number; lateDays: number; gross: number; lateDeduction: number; advances: number; paid: number; toPay: number;
};
type Sheet = { month: string; rows: Row[]; totalGross: number; totalToPay: number };
type Entry = { id: string; kind: "ADVANCE" | "PAYMENT"; amount: number; month: string; note: string | null; createdByName: string; createdAt: string };

const hours = (m: number) => `${Math.floor(m / 60)} s ${m % 60} dəq`;
const METHODS: [string, string][] = [["CASH", "Nağd (kassadan)"], ["CARD", "Kart"], ["TRANSFER", "Bank köçürməsi"]];

/** İşçi maaşı: monthly salary or hourly pay from Davamiyyət, minus late days and advances; advances and payments leave the cash desk. */
export default function PayrollPage() {
  const { isOwner } = useAuth();
  const [month, setMonth] = useState(todayBaku().slice(0, 7));
  const [sheet, setSheet] = useState<Sheet | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<Row | null>(null);
  const [paying, setPaying] = useState<{ row: Row; kind: "advance" | "pay" } | null>(null);
  const [history, setHistory] = useState<Row | null>(null);

  const load = useCallback(() => {
    setSheet(null);
    api<Sheet>(`/api/payroll?month=${month}`).then(setSheet).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [month]);
  useEffect(() => { if (isOwner) load(); }, [isOwner, load]);

  if (!isOwner) return <Card><EmptyState title="Bu bölmə yalnız mağaza sahibi üçündür" /></Card>;

  return (
    <>
      <PageHeader title="İşçi maaşı" subtitle="Davamiyyətə görə hesablanır: iş saatı, gecikmə, avans və ödənişlər"
        actions={<Input type="month" value={month} max={todayBaku().slice(0, 7)} onChange={(e) => e.target.value && setMonth(e.target.value)} className="w-44" />} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {!sheet ? <Spinner /> : sheet.rows.length === 0 ? (
        <Card><EmptyState title="İşçi yoxdur" text="İşçilər bölməsindən işçi əlavə edin." /></Card>
      ) : (
        <>
          <div className="mb-4 grid gap-3 sm:grid-cols-2">
            <StatCard label="Hesablanmış maaş" value={formatMoney(sheet.totalGross)} hint={sheet.month} />
            <StatCard label="Ödəniləcək qalıq" value={formatMoney(sheet.totalToPay)} hint="avans və ödənişlərdən sonra" tone="rose" />
          </div>
          <Card>
            <Table>
              <thead className="bg-slate-50/70"><tr>
                <Th>İşçi</Th><Th>Maaş</Th><Th className="text-right">Gün / saat</Th><Th className="text-right">Gecikmə</Th><Th className="text-right">Hesablanıb</Th>
                <Th className="text-right">Avans</Th><Th className="text-right">Ödənilib</Th><Th className="text-right">Qalıq</Th><Th />
              </tr></thead>
              <tbody className="divide-y divide-slate-100">
                {sheet.rows.map((r) => (
                  <tr key={r.userId}>
                    <Td className="font-medium text-navy-800"><button type="button" className="hover:underline" onClick={() => setHistory(r)}>{r.fullName}</button></Td>
                    <Td>
                      {r.payType ? (
                        <span className="text-sm">{formatMoney(r.rate ?? 0)} / {r.payType === "HOURLY" ? "saat" : "ay"}</span>
                      ) : <Badge tone="amber">təyin edilməyib</Badge>}
                      <button type="button" className="ml-2 text-xs text-gold-600 hover:underline" onClick={() => setEditing(r)}>dəyiş</button>
                    </Td>
                    <Td className="text-right tabular-nums">{r.days} gün<div className="text-xs text-slate-500">{hours(r.minutes)}</div></Td>
                    <Td className="text-right tabular-nums">{r.lateDays > 0 ? <span className="text-rose-600">{r.lateDays} dəfə · −{formatMoney(r.lateDeduction)}</span> : "—"}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(r.gross)}</Td>
                    <Td className="text-right tabular-nums">{r.advances > 0 ? formatMoney(r.advances) : "—"}</Td>
                    <Td className="text-right tabular-nums">{r.paid > 0 ? formatMoney(r.paid) : "—"}</Td>
                    <Td className="text-right font-semibold tabular-nums">{formatMoney(r.toPay)}</Td>
                    <Td className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setPaying({ row: r, kind: "advance" })}>Avans</Button>
                        <Button variant="gold" className="px-2.5 py-1.5" disabled={r.toPay <= 0} onClick={() => setPaying({ row: r, kind: "pay" })}>Maaşı ödə</Button>
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <p className="mt-3 text-xs text-slate-500">
              Saatlıq maaş “Davamiyyət”dəki iş saatından hesablanır, aylıq maaş sabitdir. Gecikmə hər gecikdiyi gün üçün çıxılır.
              Avans və maaş ödənişi kassadan “İşçi maaşı” xərci kimi çıxır və mənfəət hesabatında görünür.
            </p>
          </Card>
        </>
      )}
      {editing && <SettingsModal row={editing} onClose={() => setEditing(null)} onSaved={(s) => { setEditing(null); setSheet(s); }} />}
      {paying && <PayModal row={paying.row} kind={paying.kind} month={month} onClose={() => setPaying(null)}
        onSaved={(s, msg) => { setPaying(null); setSheet(s); setNotice(msg); }} />}
      {history && <HistoryModal row={history} month={month} onClose={() => setHistory(null)} />}
    </>
  );
}

function SettingsModal({ row, onClose, onSaved }: { row: Row; onClose: () => void; onSaved: (s: Sheet) => void }) {
  const [form, setForm] = useState({ payType: row.payType ?? "MONTHLY", rate: row.rate != null ? String(row.rate) : "", latePenalty: String(row.latePenalty || "") });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function save() {
    setBusy(true);
    try {
      onSaved(await api<Sheet>(`/api/payroll/${row.userId}/settings`, { method: "PUT", json: { payType: form.payType, rate: form.rate || null, latePenalty: form.latePenalty || 0 } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal open title={`Maaş — ${row.fullName}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button onClick={save} disabled={busy || !form.rate}>Saxla</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="space-y-4">
        <Field label="Maaş növü">
          <Select value={form.payType} onChange={(e) => setForm({ ...form, payType: e.target.value as "MONTHLY" | "HOURLY" })}>
            <option value="MONTHLY">Aylıq sabit maaş</option>
            <option value="HOURLY">Saatlıq (davamiyyətə görə)</option>
          </Select>
        </Field>
        <Field label={form.payType === "HOURLY" ? "1 saat üçün (₼)" : "Aylıq maaş (₼)"}>
          <Input type="number" min="0" step="0.01" value={form.rate} onChange={(e) => setForm({ ...form, rate: e.target.value })} />
        </Field>
        <Field label="Hər gecikmə üçün çıxılır (₼)" hint="0 — gecikmə maaşa təsir etmir. Gecikmə “Davamiyyət → İş saatı”na görə hesablanır">
          <Input type="number" min="0" step="0.01" value={form.latePenalty} onChange={(e) => setForm({ ...form, latePenalty: e.target.value })} />
        </Field>
      </div>
    </Modal>
  );
}

function PayModal({ row, kind, month, onClose, onSaved }: { row: Row; kind: "advance" | "pay"; month: string; onClose: () => void; onSaved: (s: Sheet, msg: string) => void }) {
  const [amount, setAmount] = useState(kind === "pay" ? String(row.toPay) : "");
  const [method, setMethod] = useState("CASH");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function save() {
    setBusy(true);
    try {
      const s = await api<Sheet>(`/api/payroll/${row.userId}/${kind}`, { method: "POST", json: { amount, month, paymentMethod: method, note: note || null } });
      onSaved(s, `${row.fullName}: ${formatMoney(Number(amount))} ${kind === "advance" ? "avans verildi" : "maaş ödənildi"}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal open title={`${kind === "advance" ? "Avans" : "Maaş ödənişi"} — ${row.fullName}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" onClick={save} disabled={busy || !(Number(amount) > 0)}>Təsdiqlə</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="space-y-4">
        {kind === "pay" && <p className="text-sm text-slate-600">Bu ay üçün qalıq: <b>{formatMoney(row.toPay)}</b></p>}
        <Field label="Məbləğ (₼)"><Input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus /></Field>
        <Field label="Necə verilir">
          <Select value={method} onChange={(e) => setMethod(e.target.value)}>{METHODS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select>
        </Field>
        <Field label="Qeyd"><Input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} /></Field>
        <p className="text-xs text-slate-500">{month} ayına yazılır. Pul kassadan “İşçi maaşı” xərci kimi çıxır.</p>
      </div>
    </Modal>
  );
}

function HistoryModal({ row, month, onClose }: { row: Row; month: string; onClose: () => void }) {
  const [list, setList] = useState<Entry[] | null>(null);
  useEffect(() => { api<Entry[]>(`/api/payroll/${row.userId}/entries?month=${month}`).then(setList).catch(() => setList([])); }, [row.userId, month]);
  return (
    <Modal open title={`${row.fullName} — ${month}`} onClose={onClose} footer={<Button variant="ghost" onClick={onClose}>Bağla</Button>}>
      {!list ? <Spinner /> : list.length === 0 ? <p className="py-4 text-center text-sm text-slate-500">Bu ay avans və ödəniş yoxdur.</p> : (
        <div className="divide-y divide-slate-100">
          {list.map((e) => (
            <div key={e.id} className="flex items-center justify-between py-2 text-sm">
              <div><Badge tone={e.kind === "ADVANCE" ? "amber" : "emerald"}>{e.kind === "ADVANCE" ? "Avans" : "Maaş"}</Badge>
                <span className="ml-2 text-xs text-slate-500">{formatDateTime(e.createdAt)} · {e.createdByName}{e.note ? ` · ${e.note}` : ""}</span></div>
              <span className="font-semibold tabular-nums">{formatMoney(e.amount)}</span>
            </div>
          ))}
        </div>
      )}
    </Modal>
  );
}
