"use client";

import { useEffect, useState } from "react";
import { Alert, Badge, Button, Card, Field, Input, Select, useConfirm } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatMoney, todayBaku } from "@/lib/format";
import { waLink } from "@/lib/whatsapp";

export type InstallmentItem = { seq: number; dueDate: string; amount: number; paid: number; status: "PAID" | "OVERDUE" | "DUE_SOON" | "PARTIAL" | "UPCOMING"; daysLate: number };
export type InstallmentPlan = {
  id: string; customerId: string; customerName: string; customerPhone: string | null; total: number; paid: number; left: number;
  overdue: number; status: "ACTIVE" | "COMPLETED"; startedAt: string; note: string | null; next?: InstallmentItem | null; items: InstallmentItem[];
};

export const ITEM_STATUS: Record<InstallmentItem["status"], { label: string; tone: "emerald" | "rose" | "amber" | "sky" | "slate" }> = {
  PAID: { label: "Ödənib", tone: "emerald" },
  OVERDUE: { label: "Gecikir", tone: "rose" },
  DUE_SOON: { label: "Vaxtı çatır", tone: "amber" },
  PARTIAL: { label: "Qismən", tone: "sky" },
  UPCOMING: { label: "Gözlənilir", tone: "slate" },
};

/** WhatsApp text about the next part (or the late sum). */
export function installmentText(store: string, p: InstallmentPlan): string {
  const n = p.next;
  if (p.overdue > 0) {
    return `Hörmətli ${p.customerName}, ${store} mağazasına taksit ödənişiniz gecikir: ${formatMoney(p.overdue)}. Zəhmət olmasa ödənişi edin. Qalan borc: ${formatMoney(p.left)}.`;
  }
  return n
    ? `Hörmətli ${p.customerName}, ${store} mağazasına növbəti taksit ödənişiniz ${formatDate(n.dueDate)} tarixindədir: ${formatMoney(n.amount - n.paid)}. Qalan borc: ${formatMoney(p.left)}.`
    : `Hörmətli ${p.customerName}, taksit borcunuz tam ödənib. Təşəkkür edirik!`;
}

function addMonths(iso: string, n: number) {
  const d = new Date(iso + "T00:00:00");
  d.setMonth(d.getMonth() + n);
  return d.toISOString().slice(0, 10);
}

/** Taksit qrafiki on the customer card: make the schedule, see what is paid / late, remind by WhatsApp. */
export function InstallmentCard({ customerId, storeName, onChange }: { customerId: string; storeName: string; onChange?: () => void }) {
  const base = `/api/customers/${customerId}/installments`;
  const [data, setData] = useState<{ plan?: InstallmentPlan | null; openBalance: number } | null>(null);
  const [form, setForm] = useState(false);
  const [total, setTotal] = useState("");
  const [count, setCount] = useState("6");
  const [first, setFirst] = useState(() => addMonths(todayBaku(), 1));
  const [every, setEvery] = useState("1");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, confirmDialog] = useConfirm();

  const load = () => api<{ plan?: InstallmentPlan | null; openBalance: number }>(base).then((d) => { setData(d); setTotal(String(d.openBalance)); }).catch(() => undefined);
  useEffect(() => { void load(); }, [customerId]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return null;
  const plan = data.plan ?? null;
  const t = Number(total.replace(",", ".")) || 0;
  const c = Math.max(0, Math.floor(Number(count) || 0));
  const part = c >= 2 ? Math.floor((t / c) * 100) / 100 : 0;

  async function create() {
    setBusy(true);
    setError(null);
    try {
      await api(base, { method: "POST", json: { total: t, count: c, firstDate: first, everyMonths: Number(every) } });
      setForm(false);
      await load();
      onChange?.();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Qrafik qurulmadı");
    } finally {
      setBusy(false);
    }
  }

  async function close() {
    if (!(await confirm("Taksit qrafiki bağlansın? Borc özü dəyişmir, yalnız qrafik silinir.", { title: "Qrafiki bağla", confirmLabel: "Bəli, bağla", danger: true }))) return;
    await api(base, { method: "DELETE" });
    await load();
  }

  const wa = plan ? waLink(plan.customerPhone, installmentText(storeName, plan)) : null;

  return (
    <Card>
      {confirmDialog}
      <div className="mb-3 flex items-baseline justify-between gap-2">
        <h2 className="font-serif text-lg font-semibold text-navy-800">Taksit qrafiki</h2>
        {plan && <Badge tone={plan.status === "COMPLETED" ? "emerald" : plan.overdue > 0 ? "rose" : "navy"}>{plan.status === "COMPLETED" ? "Tam ödənib" : plan.overdue > 0 ? `Gecikir ${formatMoney(plan.overdue)}` : "Aktiv"}</Badge>}
      </div>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}

      {plan ? (
        <div className="space-y-3">
          <div>
            <div className="flex justify-between text-sm"><span className="text-slate-500">Ödənib</span><span className="font-semibold tabular-nums text-navy-800">{formatMoney(plan.paid)} / {formatMoney(plan.total)}</span></div>
            <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-emerald-500" style={{ width: `${Math.min(100, (plan.paid / plan.total) * 100)}%` }} /></div>
          </div>
          <ul className="divide-y divide-slate-100 text-sm">
            {plan.items.map((i) => (
              <li key={i.seq} className="flex items-center justify-between gap-2 py-2">
                <span className="text-slate-500">{i.seq}. {formatDate(i.dueDate)}</span>
                <span className="flex items-center gap-2">
                  <span className="tabular-nums font-medium text-navy-800">{formatMoney(i.amount)}</span>
                  <Badge tone={ITEM_STATUS[i.status].tone}>{ITEM_STATUS[i.status].label}{i.status === "OVERDUE" ? ` ${i.daysLate} gün` : ""}</Badge>
                </span>
              </li>
            ))}
          </ul>
          <p className="text-xs text-slate-500">Müştəri “Borc ödə” ilə ödədikcə hissələr ardıcıl ödənmiş sayılır.</p>
          <div className="flex flex-wrap gap-2">
            {wa && plan.status !== "COMPLETED" && <a href={wa} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-3 py-2 text-sm font-medium text-white hover:bg-emerald-500">WhatsApp xatırlatma</a>}
            <Button variant="ghost" onClick={() => void close()}>Qrafiki bağla</Button>
          </div>
        </div>
      ) : !form ? (
        <div className="space-y-3">
          <p className="text-sm text-slate-500">Borcu aylara bölün: hər hissənin tarixi olur, gecikən hissələr qırmızı görünür.</p>
          <Button variant="outline" disabled={data.openBalance <= 0} onClick={() => setForm(true)}>Borcu hissələrə böl</Button>
          {data.openBalance <= 0 && <p className="text-xs text-slate-400">Müştərinin açıq borcu yoxdur.</p>}
        </div>
      ) : (
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Məbləğ (₼)"><Input inputMode="decimal" value={total} onChange={(e) => setTotal(e.target.value)} /></Field>
            <Field label="Hissə sayı"><Input inputMode="numeric" value={count} onChange={(e) => setCount(e.target.value.replace(/\D/g, ""))} /></Field>
            <Field label="İlk ödəniş"><Input type="date" value={first} min={todayBaku()} onChange={(e) => setFirst(e.target.value)} /></Field>
            <Field label="Aralıq">
              <Select value={every} onChange={(e) => setEvery(e.target.value)}>
                <option value="1">Hər ay</option>
                <option value="2">2 aydan bir</option>
                <option value="3">3 aydan bir</option>
              </Select>
            </Field>
          </div>
          {c >= 2 && t > 0 && <p className="rounded-xl bg-slate-50 px-3 py-2 text-sm text-slate-600">{c} × {formatMoney(part)}{part * c !== t ? ` (sonuncu ${formatMoney(Math.round((t - part * (c - 1)) * 100) / 100)})` : ""} · son ödəniş {formatDate(addMonths(first, (c - 1) * Number(every)))}</p>}
          <div className="flex gap-2">
            <Button variant="gold" disabled={busy || c < 2 || t <= 0} onClick={() => void create()}>Qrafik qur</Button>
            <Button variant="ghost" onClick={() => setForm(false)}>Ləğv et</Button>
          </div>
        </div>
      )}
    </Card>
  );
}
