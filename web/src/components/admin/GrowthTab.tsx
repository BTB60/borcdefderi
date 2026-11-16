"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Badge, Button, Card, EmptyState, Field, Input, Select, Spinner, Table, Td, Textarea, Th, useConfirm } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import type { Announcement, Plan, PromoCode } from "@/lib/types";

type Props = { onError: (m: string | null) => void; onNotice?: (m: string | null) => void };

const err = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback);

/** SUPER_ADMIN: free trial for new stores, promo codes and announcements to every store. */
export function GrowthTab({ onError, onNotice }: Props) {
  return (
    <div className="space-y-6">
      <TrialCard onError={onError} onNotice={onNotice} />
      <PromoCard onError={onError} onNotice={onNotice} />
      <AnnouncementsCard onError={onError} onNotice={onNotice} />
    </div>
  );
}

function TrialCard({ onError, onNotice }: Props) {
  const [days, setDays] = useState<string | null>(null);
  const [plan, setPlan] = useState("");
  const [plans, setPlans] = useState<Plan[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<{ days: number; planCode: string | null }>("/api/admin/trial")
      .then((t) => { setDays(String(t.days)); setPlan(t.planCode ?? ""); })
      .catch((e) => onError(err(e, "Sınaq ayarı yüklənmədi")));
    api<Plan[]>("/api/public/plans").then(setPlans).catch(() => setPlans([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    onError(null);
    try {
      const t = await api<{ days: number }>("/api/admin/trial", { method: "PUT", json: { days: Number(days) || 0, planCode: plan || null } });
      onNotice?.(t.days > 0 ? `Yeni mağazalar ${t.days} gün pulsuz istifadə edəcək` : "Pulsuz sınaq söndürüldü");
    } catch (e2) {
      onError(err(e2, "Saxlanmadı"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="font-serif text-lg font-semibold text-navy-800">Pulsuz sınaq</h2>
      <p className="mb-4 text-sm text-slate-500">Yeni qeydiyyatdan keçən mağaza dərhal işləyə bilsin: sınaq müddəti bitəndə abunə olmalıdır. 0 = sınaq yoxdur.</p>
      {days === null ? <Spinner /> : (
        <form onSubmit={save} className="grid gap-4 sm:grid-cols-[160px_1fr_auto] sm:items-end">
          <Field label="Gün sayı">
            <Input type="number" min={0} max={90} value={days} onChange={(e) => setDays(e.target.value)} />
          </Field>
          <Field label="Sınaq paketi" hint="Boş qalsa: tövsiyə olunan (yoxdursa ən bahalı) paket">
            <Select value={plan} onChange={(e) => setPlan(e.target.value)}>
              <option value="">Avtomatik</option>
              {plans.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
            </Select>
          </Field>
          <Button type="submit" disabled={busy}>Saxla</Button>
        </form>
      )}
    </Card>
  );
}

function PromoCard({ onError, onNotice }: Props) {
  const [items, setItems] = useState<PromoCode[] | null>(null);
  const [form, setForm] = useState({ code: "", kind: "percent", value: "", maxUses: "", validUntil: "", note: "" });
  const [busy, setBusy] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    api<PromoCode[]>("/api/admin/promo-codes").then(setItems).catch((e) => onError(err(e, "Promo-kodlar yüklənmədi")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    onError(null);
    try {
      const v = form.value.replace(",", ".");
      await api("/api/admin/promo-codes", {
        method: "POST",
        json: {
          code: form.code,
          percentOff: form.kind === "percent" ? Number(v) : null,
          amountOff: form.kind === "amount" ? v : null,
          maxUses: form.maxUses ? Number(form.maxUses) : null,
          validUntil: form.validUntil || null,
          note: form.note || null,
        },
      });
      onNotice?.(`Promo-kod yaradıldı: ${form.code.toUpperCase()}`);
      setForm({ code: "", kind: "percent", value: "", maxUses: "", validUntil: "", note: "" });
      setReload((r) => r + 1);
    } catch (e2) {
      onError(err(e2, "Promo-kod yaradılmadı"));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(p: PromoCode) {
    try {
      await api(`/api/admin/promo-codes/${p.id}`, {
        method: "PUT",
        json: { percentOff: p.percentOff ?? null, amountOff: p.amountOff ?? null, maxUses: p.maxUses ?? null, validUntil: p.validUntil ?? null, note: p.note ?? null, active: !p.active },
      });
      setReload((r) => r + 1);
    } catch (e) {
      onError(err(e, "Dəyişmədi"));
    }
  }

  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <Card>
      <h2 className="font-serif text-lg font-semibold text-navy-800">Promo-kodlar</h2>
      <p className="mb-4 text-sm text-slate-500">Mağaza ödəniş edəndə kodu yazır, paketin qiyməti avtomatik endirilir. Hər mağaza eyni koddan bir dəfə istifadə edə bilər.</p>
      <form onSubmit={create} className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-6 lg:items-end">
        <Field label="Kod"><Input required value={form.code} onChange={set("code")} placeholder="YAZ2026" className="uppercase" /></Field>
        <Field label="Endirim növü">
          <Select value={form.kind} onChange={set("kind")}>
            <option value="percent">Faiz (%)</option>
            <option value="amount">Manat (₼)</option>
          </Select>
        </Field>
        <Field label={form.kind === "percent" ? "Faiz" : "Məbləğ"}><Input required inputMode="decimal" value={form.value} onChange={set("value")} placeholder={form.kind === "percent" ? "20" : "5"} /></Field>
        <Field label="Limit (neçə dəfə)"><Input type="number" min={1} value={form.maxUses} onChange={set("maxUses")} placeholder="Limitsiz" /></Field>
        <Field label="Son tarix"><Input type="date" value={form.validUntil} onChange={set("validUntil")} /></Field>
        <Button type="submit" disabled={busy}>Yarat</Button>
      </form>
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Promo-kod yoxdur" /> : (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Kod</Th><Th>Endirim</Th><Th className="text-right">İstifadə</Th><Th>Son tarix</Th><Th>Status</Th><Th className="text-right">Əməliyyat</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((p) => (
              <tr key={p.id}>
                <Td className="font-mono font-semibold text-navy-800">{p.code}</Td>
                <Td>{p.percentOff ? `${p.percentOff}%` : formatMoney(p.amountOff ?? 0)}</Td>
                <Td className="text-right tabular-nums">{p.usedCount}{p.maxUses ? ` / ${p.maxUses}` : ""}</Td>
                <Td>{p.validUntil ? formatDate(p.validUntil) : "—"}</Td>
                <Td><Badge tone={p.active ? "emerald" : "slate"}>{p.active ? "Aktiv" : "Söndürülüb"}</Badge></Td>
                <Td className="text-right"><Button variant="outline" onClick={() => toggle(p)}>{p.active ? "Söndür" : "Aktiv et"}</Button></Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}

const LEVEL: Record<Announcement["level"], { label: string; tone: "sky" | "amber" | "emerald" }> = {
  INFO: { label: "Məlumat", tone: "sky" },
  WARNING: { label: "Xəbərdarlıq", tone: "amber" },
  SUCCESS: { label: "Yenilik", tone: "emerald" },
};

function AnnouncementsCard({ onError, onNotice }: Props) {
  const [items, setItems] = useState<Announcement[] | null>(null);
  const [form, setForm] = useState({ title: "", body: "", level: "INFO", endsOn: "", sendNotification: true });
  const [busy, setBusy] = useState(false);
  const [reload, setReload] = useState(0);
  const [confirm, confirmDialog] = useConfirm();

  useEffect(() => {
    api<Announcement[]>("/api/admin/announcements").then(setItems).catch((e) => onError(err(e, "Elanlar yüklənmədi")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!(await confirm("Elan bütün mağazalara göndəriləcək. Davam edək?", { title: "Elan göndər", confirmLabel: "Bəli, göndər" }))) return;
    setBusy(true);
    onError(null);
    try {
      await api("/api/admin/announcements", { method: "POST", json: { ...form, endsOn: form.endsOn || null } });
      onNotice?.("Elan bütün mağazalara göndərildi");
      setForm({ title: "", body: "", level: "INFO", endsOn: "", sendNotification: true });
      setReload((r) => r + 1);
    } catch (e2) {
      onError(err(e2, "Elan göndərilmədi"));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(a: Announcement) {
    try {
      await api(`/api/admin/announcements/${a.id}`, { method: "PUT", json: { title: a.title, body: a.body, level: a.level, endsOn: a.endsOn ?? null, active: !a.active } });
      setReload((r) => r + 1);
    } catch (e) {
      onError(err(e, "Dəyişmədi"));
    }
  }

  async function remove(a: Announcement) {
    if (!(await confirm(`“${a.title}” elanı silinsin?`, { title: "Elanı sil", confirmLabel: "Sil", danger: true }))) return;
    try {
      await api(`/api/admin/announcements/${a.id}`, { method: "DELETE" });
      setReload((r) => r + 1);
    } catch (e) {
      onError(err(e, "Silinmədi"));
    }
  }

  return (
    <Card>
      <h2 className="font-serif text-lg font-semibold text-navy-800">Bütün mağazalara elan</h2>
      <p className="mb-4 text-sm text-slate-500">Elan saytın və telefon tətbiqinin yuxarısında görünür, həm də bildiriş kimi gedir. Söndürəndə və ya son tarixdən sonra gizlənir.</p>
      <form onSubmit={create} className="mb-5 space-y-3">
        <div className="grid gap-3 sm:grid-cols-[1fr_180px_180px]">
          <Field label="Başlıq"><Input required maxLength={150} value={form.title} onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))} placeholder="Yeni funksiya: Z-hesabat telefonda" /></Field>
          <Field label="Növ">
            <Select value={form.level} onChange={(e) => setForm((f) => ({ ...f, level: e.target.value }))}>
              <option value="INFO">Məlumat</option>
              <option value="SUCCESS">Yenilik</option>
              <option value="WARNING">Xəbərdarlıq</option>
            </Select>
          </Field>
          <Field label="Nə vaxta qədər görünsün"><Input type="date" value={form.endsOn} onChange={(e) => setForm((f) => ({ ...f, endsOn: e.target.value }))} /></Field>
        </div>
        <Field label="Mətn"><Textarea required maxLength={2000} value={form.body} onChange={(e) => setForm((f) => ({ ...f, body: e.target.value }))} /></Field>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <label className="flex items-center gap-2 text-sm text-slate-600">
            <input type="checkbox" checked={form.sendNotification} onChange={(e) => setForm((f) => ({ ...f, sendNotification: e.target.checked }))} />
            Bildiriş kimi də göndər
          </label>
          <Button type="submit" disabled={busy}>Göndər</Button>
        </div>
      </form>
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Elan yoxdur" /> : (
        <ul className="divide-y divide-slate-100">
          {items.map((a) => (
            <li key={a.id} className="flex flex-col gap-2 py-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium text-navy-800">{a.title}</span>
                  <Badge tone={LEVEL[a.level].tone}>{LEVEL[a.level].label}</Badge>
                  {!a.active && <Badge tone="slate">Söndürülüb</Badge>}
                </div>
                <p className="mt-1 whitespace-pre-line text-sm text-slate-600">{a.body}</p>
                <p className="mt-1 text-xs text-slate-400">{formatDateTime(a.createdAt)}{a.endsOn ? ` · ${formatDate(a.endsOn)} qədər` : ""}</p>
              </div>
              <div className="flex shrink-0 gap-1">
                <Button variant="outline" onClick={() => toggle(a)}>{a.active ? "Söndür" : "Göstər"}</Button>
                <Button variant="outline" onClick={() => remove(a)}>Sil</Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {confirmDialog}
    </Card>
  );
}
