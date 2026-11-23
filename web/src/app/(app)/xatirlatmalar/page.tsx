"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import { waLink, waWebLink } from "@/lib/whatsapp";

type Reminder = {
  id: string; customerId: string; customerName: string; customerPhone: string | null; kind: "DUE_SOON" | "OVERDUE";
  amount: number; dueDate: string; remindOn: string; status: "PENDING" | "SENT" | "SKIPPED"; channel: string | null;
  handledAt: string | null; message: string;
};
type Settings = { enabled: boolean; daysBefore: number; repeatDays: number };
type SmsStatus = { configured: boolean; autoReminders: boolean; sentThisMonth: number; monthlyLimit: number };

const CHANNEL: Record<string, string> = { WHATSAPP: "WhatsApp", SMS: "SMS", CALL: "Zəng", OTHER: "Digər" };

/** Avtomatik borc xatırlatmaları: the system lists who to remind every day; one tap sends the ready message. */
export default function RemindersPage() {
  const { isOwner, can } = useAuth();
  const allowed = isOwner || can("CUSTOMER_DEBTS");
  const [tab, setTab] = useState<"PENDING" | "SENT" | "SKIPPED">("PENDING");
  const [items, setItems] = useState<Reminder[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [edited, setEdited] = useState<Record<string, string>>({});
  const [sms, setSms] = useState<SmsStatus | null>(null);
  const [smsBusy, setSmsBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => { if (allowed) api<SmsStatus>("/api/settings/sms").then(setSms).catch(() => setSms(null)); }, [allowed]);

  async function sendAllSms() {
    setSmsBusy(true);
    setError(null);
    try {
      const r = await api<{ sent: number }>("/api/reminders/send-sms", { method: "POST" });
      setNotice(r.sent > 0 ? `${r.sent} xatırlatma SMS ilə göndərildi` : "Heç bir SMS göndərilmədi (telefon nömrəsi olan gözləyən xatırlatma yoxdur və ya limit bitib)");
      load();
      api<SmsStatus>("/api/settings/sms").then(setSms).catch(() => undefined);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setSmsBusy(false);
    }
  }

  const load = useCallback(() => {
    setItems(null);
    api<Reminder[]>(`/api/reminders?status=${tab}`).then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [tab]);
  useEffect(() => { if (allowed) load(); }, [allowed, load]);

  if (!allowed) return <Card><EmptyState title="Bu bölmə üçün icazəniz yoxdur" /></Card>;

  async function mark(r: Reminder, action: "sent" | "skip", channel?: string) {
    try {
      await api(`/api/reminders/${r.id}/${action}`, { method: "POST", json: action === "sent" ? { channel } : undefined });
      setItems((list) => list?.filter((x) => x.id !== r.id) ?? null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    }
  }

  function send(r: Reminder, channel: "WHATSAPP" | "SMS") {
    const text = edited[r.id] ?? r.message;
    const url = channel === "WHATSAPP"
      ? (window.innerWidth >= 1024 ? waWebLink(r.customerPhone, text) : waLink(r.customerPhone, text))
      : `sms:${(r.customerPhone ?? "").replace(/[^+0-9]/g, "")}?body=${encodeURIComponent(text)}`;
    if (!url) { setError("Müştərinin telefon nömrəsi yoxdur və ya düzgün deyil"); return; }
    window.open(url, "_blank", "noopener");
    void mark(r, "sent", channel);
  }

  const total = items?.reduce((s, r) => s + r.amount, 0) ?? 0;

  return (
    <>
      <PageHeader title="Borc xatırlatmaları"
        subtitle="Sistem hər gün son ödəmə tarixi yaxınlaşan və keçən müştəriləri özü tapır. Hazır mesajı bir toxunuşla göndərin."
        actions={<>
          {sms?.configured && tab === "PENDING" && (items?.length ?? 0) > 0 && (
            <Button variant="gold" disabled={smsBusy} onClick={sendAllSms}>{smsBusy ? "Göndərilir…" : "Hamısını SMS ilə göndər"}</Button>
          )}
          {isOwner && <Button variant="outline" onClick={() => setSettingsOpen(true)}>Parametrlər</Button>}
        </>} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {sms?.autoReminders && (
        <div className="mb-4"><Alert kind="info">Avtomatik SMS açıqdır: hər səhər 9:00-da xatırlatmalar müştərilərə SMS ilə özü gedir. Bu ay: {sms.sentThisMonth} / {sms.monthlyLimit} SMS.</Alert></div>
      )}
      <div className="mb-4 inline-flex rounded-xl bg-slate-100 p-1" role="tablist">
        {([["PENDING", "Göndəriləcək"], ["SENT", "Göndərilib"], ["SKIPPED", "Keçilib"]] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`rounded-lg px-4 py-1.5 text-sm font-medium ${tab === k ? "bg-white text-navy-800 shadow" : "text-slate-500"}`}>{l}</button>
        ))}
      </div>
      {!items ? <Spinner /> : items.length === 0 ? (
        <Card><EmptyState title={tab === "PENDING" ? "Bu gün xatırlatma yoxdur" : "Siyahı boşdur"} /></Card>
      ) : (
        <>
          {tab === "PENDING" && <p className="mb-3 text-sm text-slate-600">{items.length} müştəri · cəmi <b>{formatMoney(total)}</b></p>}
          <div className="grid gap-3 lg:grid-cols-2">
            {items.map((r) => (
              <Card key={r.id}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <Link href={`/kontragentlar/musteri/${r.customerId}`} className="font-semibold text-navy-800 hover:text-gold-600 hover:underline">{r.customerName}</Link>
                    <p className="text-xs text-slate-500">{r.customerPhone ?? "telefon yoxdur"} · son tarix {formatDate(r.dueDate)}</p>
                  </div>
                  <div className="text-right">
                    <Badge tone={r.kind === "OVERDUE" ? "rose" : "amber"}>{r.kind === "OVERDUE" ? "Gecikib" : "Tarixi yaxınlaşır"}</Badge>
                    <p className="mt-1 font-serif text-lg font-semibold tabular-nums text-navy-800">{formatMoney(r.amount)}</p>
                  </div>
                </div>
                {tab === "PENDING" ? (
                  <>
                    <textarea value={edited[r.id] ?? r.message} onChange={(e) => setEdited({ ...edited, [r.id]: e.target.value })} rows={3}
                      className="mt-3 w-full rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-gold-500" />
                    <div className="mt-2 flex flex-wrap gap-2">
                      <Button variant="gold" className="px-3 py-1.5" onClick={() => send(r, "WHATSAPP")}>WhatsApp</Button>
                      <Button variant="outline" className="px-3 py-1.5" onClick={() => send(r, "SMS")}>SMS</Button>
                      <Button variant="outline" className="px-3 py-1.5" onClick={() => mark(r, "sent", "CALL")}>Zəng etdim</Button>
                      <Button variant="ghost" className="px-3 py-1.5" onClick={() => mark(r, "skip")}>Keç</Button>
                    </div>
                  </>
                ) : (
                  <p className="mt-2 text-xs text-slate-500">
                    {r.status === "SENT" ? `${CHANNEL[r.channel ?? "OTHER"]} ilə göndərilib` : "Keçilib"} · {r.handledAt ? formatDateTime(r.handledAt) : ""}
                  </p>
                )}
              </Card>
            ))}
          </div>
        </>
      )}
      {settingsOpen && <SettingsModal onClose={() => setSettingsOpen(false)} onSaved={() => { setSettingsOpen(false); load(); api<SmsStatus>("/api/settings/sms").then(setSms).catch(() => undefined); }} />}
    </>
  );
}

function SettingsModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [s, setS] = useState<Settings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [sms, setSms] = useState<SmsStatus | null>(null);
  const [testPhone, setTestPhone] = useState("");
  const [testResult, setTestResult] = useState<string | null>(null);
  useEffect(() => {
    api<Settings>("/api/settings/reminders").then(setS).catch(() => setError("Yüklənmədi"));
    api<SmsStatus>("/api/settings/sms").then(setSms).catch(() => setSms(null));
  }, []);

  async function test() {
    setTestResult(null);
    try {
      const r = await api<{ sent: boolean }>("/api/settings/sms/test", { method: "POST", json: { phone: testPhone } });
      setTestResult(r.sent ? "Test SMS göndərildi" : "SMS göndərilmədi — provayder cavab vermədi");
    } catch (e) {
      setTestResult(e instanceof ApiError ? e.message : "Alınmadı");
    }
  }

  async function save() {
    if (!s) return;
    setBusy(true);
    try {
      await api("/api/settings/reminders", { method: "PUT", json: s });
      if (sms?.configured) await api("/api/settings/sms", { method: "PUT", json: { autoReminders: sms.autoReminders } });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title="Xatırlatma parametrləri" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button onClick={save} disabled={busy || !s}>Saxla</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      {!s ? <Spinner /> : (
        <div className="space-y-4">
          <label className="flex items-center gap-2 text-sm font-medium text-navy-800">
            <input type="checkbox" className="h-4 w-4" checked={s.enabled} onChange={(e) => setS({ ...s, enabled: e.target.checked })} />
            Avtomatik xatırlatmalar açıqdır
          </label>
          <Field label="Son ödəmə tarixindən neçə gün əvvəl xatırlatsın" hint="0 — elə həmin gün">
            <Input type="number" min={0} max={30} value={s.daysBefore} onChange={(e) => setS({ ...s, daysBefore: Number(e.target.value) })} />
          </Field>
          <Field label="Gecikmiş borcu neçə gündən bir yenidən xatırlatsın">
            <Input type="number" min={1} max={60} value={s.repeatDays} onChange={(e) => setS({ ...s, repeatDays: Number(e.target.value) })} />
          </Field>
          <p className="text-xs text-slate-500">Xatırlatma yalnız “son ödəmə tarixi” qoyulmuş borclar üçün yaranır. Hər səhər saat 9:00-da siyahı yenilənir.</p>
          <div className="border-t border-slate-100 pt-4">
            <p className="mb-2 text-sm font-semibold text-navy-800">Avtomatik SMS</p>
            {!sms ? <Spinner /> : !sms.configured ? (
              <p className="text-xs text-slate-500">SMS xidməti hələ qoşulmayıb. Qoşulandan sonra xatırlatmalar müştərilərə özü SMS ilə gedə biləcək.</p>
            ) : (
              <div className="space-y-3">
                <label className="flex items-center gap-2 text-sm font-medium text-navy-800">
                  <input type="checkbox" className="h-4 w-4" checked={sms.autoReminders} onChange={(e) => setSms({ ...sms, autoReminders: e.target.checked })} />
                  Xatırlatmaları hər səhər SMS ilə özü göndərsin
                </label>
                <p className="text-xs text-slate-500">Bu ay göndərilib: {sms.sentThisMonth} / {sms.monthlyLimit} SMS.</p>
                <div className="flex gap-2">
                  <Input placeholder="Test üçün nömrə: 050 123 45 67" value={testPhone} onChange={(e) => setTestPhone(e.target.value)} />
                  <Button variant="outline" type="button" disabled={!testPhone.trim()} onClick={test}>Test SMS</Button>
                </div>
                {testResult && <p className="text-xs text-slate-600">{testResult}</p>}
              </div>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}
