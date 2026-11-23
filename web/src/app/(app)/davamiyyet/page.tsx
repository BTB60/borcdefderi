"use client";

import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, todayBaku } from "@/lib/format";

type AttendanceRecord = {
  id: string; userId: string; fullName: string; workDate: string; checkIn: string; checkOut: string | null;
  minutes: number; late: boolean; note: string | null; edited: boolean; open: boolean;
};
type PersonSummary = { userId: string; fullName: string; days: number; lateDays: number; minutes: number; presentNow: boolean };
type Settings = { workStart: string | null; graceMinutes: number };

const time = (v: string | null) => v ? new Date(v).toLocaleTimeString("az-AZ", { timeZone: "Asia/Baku", hour: "2-digit", minute: "2-digit" }) : "—";
const hours = (m: number) => `${Math.floor(m / 60)} saat ${m % 60} dəq`;
/** "2026-11-23T09:05" in Baku time, for <input type="datetime-local">. */
const localInput = (v: string | null) => {
  if (!v) return "";
  const d = new Date(new Date(v).toLocaleString("en-US", { timeZone: "Asia/Baku" }));
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
};
const toIso = (local: string) => (local ? `${local}:00+04:00` : null);

/** İşçi davamiyyəti: Gəldim / Getdim for everyone; the owner sees who came when, lateness and hours. */
export default function AttendancePage() {
  const { isOwner } = useAuth();
  const today = todayBaku();
  const [current, setCurrent] = useState<AttendanceRecord | null | undefined>(undefined);
  const [records, setRecords] = useState<AttendanceRecord[] | null>(null);
  const [summary, setSummary] = useState<PersonSummary[] | null>(null);
  const [range, setRange] = useState({ from: today.slice(0, 8) + "01", to: today, userId: "" });
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<AttendanceRecord | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);

  const fail = (e: unknown) => setError(e instanceof ApiError ? e.message : "Alınmadı");

  const load = useCallback(() => {
    api<AttendanceRecord | null>("/api/attendance/current").then((r) => setCurrent(r ?? null)).catch(() => setCurrent(null));
    const qs = new URLSearchParams({ from: range.from, to: range.to });
    if (range.userId) qs.set("userId", range.userId);
    api<AttendanceRecord[]>(`/api/attendance?${qs}`).then(setRecords).catch(fail);
    if (isOwner) api<PersonSummary[]>(`/api/attendance/summary?from=${range.from}&to=${range.to}`).then(setSummary).catch(fail);
  }, [range, isOwner]);
  useEffect(load, [load]);

  async function clock(kind: "check-in" | "check-out") {
    setBusy(true);
    setError(null);
    try {
      await api(`/api/attendance/${kind}`, { method: "POST", json: note.trim() ? { note: note.trim() } : {} });
      setNote("");
      load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }

  const present = summary?.filter((p) => p.presentNow).length ?? 0;

  return (
    <>
      <PageHeader title="Davamiyyət" subtitle="İşə gələndə “Gəldim”, gedəndə “Getdim” düyməsini basın."
        actions={isOwner ? <Button variant="outline" onClick={() => setSettingsOpen(true)}>İş saatı</Button> : undefined} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <Card className="mb-5">
        {current === undefined ? <Spinner /> : (
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              {current ? (
                <>
                  <p className="text-sm text-slate-500">İşdəsiniz</p>
                  <p className="font-serif text-2xl font-semibold text-navy-800">{time(current.checkIn)}-dən bəri · {hours(current.minutes)}</p>
                  {current.late && <Badge tone="amber">gecikmə ilə gəlmisiniz</Badge>}
                </>
              ) : (
                <>
                  <p className="text-sm text-slate-500">Hələ “Gəldim” qeyd etməmisiniz</p>
                  <p className="font-serif text-2xl font-semibold text-navy-800">{new Date().toLocaleTimeString("az-AZ", { timeZone: "Asia/Baku", hour: "2-digit", minute: "2-digit" })}</p>
                </>
              )}
            </div>
            <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
              <Input className="min-w-0 flex-1 sm:w-56" placeholder="Qeyd (istəyə görə)" value={note} onChange={(e) => setNote(e.target.value)} maxLength={300} />
              {current
                ? <Button variant="outline" disabled={busy} onClick={() => clock("check-out")}>Getdim</Button>
                : <Button variant="gold" disabled={busy} onClick={() => clock("check-in")}>Gəldim</Button>}
            </div>
          </div>
        )}
      </Card>

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Field label="Başlanğıc"><Input type="date" value={range.from} max={range.to} onChange={(e) => setRange({ ...range, from: e.target.value })} /></Field>
        <Field label="Son"><Input type="date" value={range.to} min={range.from} onChange={(e) => setRange({ ...range, to: e.target.value })} /></Field>
        {isOwner && summary && (
          <Field label="İşçi">
            <Select value={range.userId} onChange={(e) => setRange({ ...range, userId: e.target.value })}>
              <option value="">Hamısı</option>
              {summary.map((p) => <option key={p.userId} value={p.userId}>{p.fullName}</option>)}
            </Select>
          </Field>
        )}
      </div>

      {isOwner && summary && (
        <>
          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            <StatCard label="İndi işdədir" value={`${present} nəfər`} tone="emerald" />
            <StatCard label="Gecikmələr" value={String(summary.reduce((s, p) => s + p.lateDays, 0))} hint="seçilmiş dövrdə" tone="rose" />
            <StatCard label="Cəmi iş saatı" value={hours(summary.reduce((s, p) => s + p.minutes, 0))} hint="seçilmiş dövrdə" />
          </div>
          <Card className="mb-5">
            <h2 className="mb-3 font-semibold text-navy-800">İşçilər üzrə</h2>
            <Table>
              <thead className="bg-slate-50/70"><tr><Th>İşçi</Th><Th className="text-right">Gəldiyi gün</Th><Th className="text-right">Gecikdiyi gün</Th><Th className="text-right">İş saatı</Th><Th>İndi</Th></tr></thead>
              <tbody className="divide-y divide-slate-100">
                {summary.map((p) => (
                  <tr key={p.userId}>
                    <Td className="font-medium text-navy-800">{p.fullName}</Td>
                    <Td className="text-right tabular-nums">{p.days}</Td>
                    <Td className={`text-right tabular-nums ${p.lateDays > 0 ? "text-rose-600" : ""}`}>{p.lateDays}</Td>
                    <Td className="text-right tabular-nums">{hours(p.minutes)}</Td>
                    <Td>{p.presentNow ? <Badge tone="emerald">işdədir</Badge> : <span className="text-xs text-slate-400">—</span>}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </Card>
        </>
      )}

      <Card>
        <h2 className="mb-3 font-semibold text-navy-800">{isOwner ? "Bütün qeydlər" : "Mənim qeydlərim"}</h2>
        {!records ? <Spinner /> : records.length === 0 ? <EmptyState title="Bu dövrdə qeyd yoxdur" /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Tarix</Th>{isOwner && <Th>İşçi</Th>}<Th>Gəldi</Th><Th>Getdi</Th><Th className="text-right">Müddət</Th><Th>Qeyd</Th>{isOwner && <Th />}</tr></thead>
            <tbody className="divide-y divide-slate-100">
              {records.map((r) => (
                <tr key={r.id}>
                  <Td className="whitespace-nowrap">{formatDate(r.workDate)}</Td>
                  {isOwner && <Td className="font-medium text-navy-800">{r.fullName}</Td>}
                  <Td className="whitespace-nowrap">{time(r.checkIn)} {r.late && <Badge tone="amber">gecikib</Badge>}</Td>
                  <Td className="whitespace-nowrap">{r.open ? <Badge tone="emerald">işdədir</Badge> : time(r.checkOut)}</Td>
                  <Td className="text-right tabular-nums">{hours(r.minutes)}</Td>
                  <Td className="text-xs text-slate-500">{r.note ?? ""}{r.edited ? " (düzəldilib)" : ""}</Td>
                  {isOwner && <Td className="text-right"><Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setEditing(r)}>Düzəlt</Button></Td>}
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      {editing && <EditModal record={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
      {settingsOpen && <SettingsModal onClose={() => setSettingsOpen(false)} onSaved={() => { setSettingsOpen(false); load(); }} />}
    </>
  );
}

function EditModal({ record, onClose, onSaved }: { record: AttendanceRecord; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ checkIn: localInput(record.checkIn), checkOut: localInput(record.checkOut), note: record.note ?? "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    try {
      await api(`/api/attendance/${record.id}`, { method: "PUT", json: { checkIn: toIso(form.checkIn), checkOut: toIso(form.checkOut), note: form.note } });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title={`Düzəliş — ${record.fullName}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button onClick={save} disabled={busy || !form.checkIn}>Saxla</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="space-y-4">
        <Field label="Gəldi"><Input type="datetime-local" value={form.checkIn} onChange={(e) => setForm({ ...form, checkIn: e.target.value })} /></Field>
        <Field label="Getdi" hint={record.open ? "Boş qalsa, işçi hələ işdə sayılır" : undefined}>
          <Input type="datetime-local" value={form.checkOut} onChange={(e) => setForm({ ...form, checkOut: e.target.value })} />
        </Field>
        <Field label="Qeyd"><Input value={form.note} maxLength={300} onChange={(e) => setForm({ ...form, note: e.target.value })} /></Field>
      </div>
    </Modal>
  );
}

function SettingsModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [s, setS] = useState<Settings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api<Settings>("/api/settings/attendance").then(setS).catch(() => setError("Yüklənmədi")); }, []);

  async function save() {
    if (!s) return;
    setBusy(true);
    try {
      await api("/api/settings/attendance", { method: "PUT", json: s });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title="İş saatı" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button onClick={save} disabled={busy || !s}>Saxla</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      {!s ? <Spinner /> : (
        <div className="space-y-4">
          <Field label="İşin başlama saatı" hint="Boş qalsa, gecikmə hesablanmır">
            <Input type="time" value={s.workStart ?? ""} onChange={(e) => setS({ ...s, workStart: e.target.value || null })} />
          </Field>
          <Field label="Güzəşt (dəqiqə)" hint="Bu qədər gec gələn hələ gecikmiş sayılmır">
            <Input type="number" min={0} max={180} value={s.graceMinutes} onChange={(e) => setS({ ...s, graceMinutes: Number(e.target.value) })} />
          </Field>
        </div>
      )}
    </Modal>
  );
}
