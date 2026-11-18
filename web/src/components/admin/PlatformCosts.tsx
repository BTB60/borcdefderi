"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Badge, Button, Card, Field, Input, Modal, Select, Spinner, StatCard, Textarea, useConfirm } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatMoney, todayBaku } from "@/lib/format";

type Payment = { id: string; paidOn: string; amount: number; currency: string; note: string | null; byName: string };
type Cost = {
  id: string; name: string; provider: string | null; url: string | null; amount: number; currency: "USD" | "AZN" | "EUR";
  period: "MONTHLY" | "YEARLY" | "ONCE"; nextDue: string | null; daysLeft: number | null;
  status: "FREE" | "OK" | "SOON" | "DUE" | "OVERDUE" | "NO_DATE"; note: string | null; active: boolean; monthlyAzn: number; payments: Payment[];
};
type Summary = { monthlyCostAzn: number; yearlyCostAzn: number; incomeThisMonth: number; netThisMonth: number; paidThisYearAzn: number; dueSoon: number; overdue: number; costs: Cost[] };

const PERIOD: Record<Cost["period"], string> = { MONTHLY: "aylıq", YEARLY: "illik", ONCE: "bir dəfə" };
const SIGN: Record<Cost["currency"], string> = { USD: "$", AZN: "₼", EUR: "€" };
const money = (v: number, c: Cost["currency"]) => (c === "AZN" ? formatMoney(v) : `${SIGN[c]}${Number(v).toFixed(2)}`);
const err = (e: unknown, f: string) => (e instanceof ApiError ? e.message : f);

function StatusBadge({ c }: { c: Cost }) {
  if (!c.active) return <Badge tone="slate">Söndürülüb</Badge>;
  switch (c.status) {
    case "FREE": return <Badge tone="emerald">Pulsuz plan</Badge>;
    case "OVERDUE": return <Badge tone="rose">Gecikib {Math.abs(c.daysLeft ?? 0)} gün</Badge>;
    case "DUE": return <Badge tone="rose">Bu gün ödənməli</Badge>;
    case "SOON": return <Badge tone="amber">{c.daysLeft} gün qalıb</Badge>;
    case "NO_DATE": return <Badge tone="slate">Tarix yazılmayıb</Badge>;
    default: return <Badge tone="sky">{c.daysLeft} gün qalıb</Badge>;
  }
}

/** SUPER_ADMIN: what running HesabPro costs — server, database, domain, e-mail — when to pay, what was paid. */
export function PlatformCostsTab({ onError, onNotice }: { onError: (m: string | null) => void; onNotice?: (m: string | null) => void }) {
  const [data, setData] = useState<Summary | null>(null);
  const [edit, setEdit] = useState<Cost | "new" | null>(null);
  const [pay, setPay] = useState<Cost | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  const load = () => api<Summary>("/api/admin/costs").then(setData).catch((e) => onError(err(e, "Xərclər yüklənmədi")));
  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return <Spinner />;

  async function remove(c: Cost) {
    if (!(await confirm(`“${c.name}” silinsin? Ödənişi varsa, yalnız söndürüləcək.`, { title: "Xərci sil", confirmLabel: "Bəli", danger: true }))) return;
    await api(`/api/admin/costs/${c.id}`, { method: "DELETE" }).catch((e) => onError(err(e, "Silinmədi")));
    void load();
  }

  return (
    <div className="space-y-6">
      {confirmDialog}
      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Aylıq xərc" value={formatMoney(data.monthlyCostAzn)} hint={`İllik ≈ ${formatMoney(data.yearlyCostAzn)}`} tone="rose" />
        <StatCard label="Bu ayın gəliri (abunə)" value={formatMoney(data.incomeThisMonth)} tone="emerald" />
        <StatCard label="Bu ay xalis" value={formatMoney(data.netThisMonth)} hint="gəlir − aylıq xərc" tone={data.netThisMonth >= 0 ? "gold" : "rose"} />
        <StatCard label="Bu il ödənib" value={formatMoney(data.paidThisYearAzn)} hint={data.overdue ? `${data.overdue} ödəniş gecikib` : data.dueSoon ? `${data.dueSoon} ödəniş yaxındır` : "Gecikən yoxdur"} tone={data.overdue ? "rose" : "navy"} />
      </section>
      {(data.overdue > 0 || data.dueSoon > 0) && (
        <div className={`rounded-2xl px-5 py-3 text-sm font-medium ${data.overdue ? "bg-rose-50 text-rose-700 ring-1 ring-rose-200" : "bg-amber-50 text-amber-800 ring-1 ring-amber-200"}`}>
          ⚠️ {data.overdue ? `${data.overdue} ödənişin vaxtı keçib — xidmət dayana bilər.` : ""} {data.dueSoon ? `${data.dueSoon} ödənişin vaxtı 7 gün içində çatır.` : ""}
        </div>
      )}

      <Card>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="font-serif text-lg font-semibold text-navy-800">Server, baza, domen və digər ödənişlər</h2>
            <p className="text-sm text-slate-500">Məbləğləri və tarixləri provayderin ödəniş səhifəsindən yazın. 1 $ ≈ 1,70 ₼, 1 € ≈ 1,85 ₼ hesablanır.</p>
          </div>
          <Button variant="gold" onClick={() => setEdit("new")}>+ Xərc əlavə et</Button>
        </div>
        <ul className="divide-y divide-slate-100">
          {data.costs.map((c) => (
            <li key={c.id} className={`py-4 ${c.active ? "" : "opacity-50"}`}>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="font-semibold text-navy-800">{c.name}</p>
                  <p className="text-xs text-slate-500">
                    {c.provider ?? "—"} · {c.amount > 0 ? `${money(c.amount, c.currency)} ${PERIOD[c.period]}` : "pulsuz"}
                    {c.nextDue && ` · növbəti ödəniş ${formatDate(c.nextDue)}`}
                    {c.url && <> · <a href={c.url} target="_blank" rel="noopener noreferrer" className="text-navy-700 underline">ödəniş səhifəsi ↗</a></>}
                  </p>
                  {c.note && <p className="mt-1 text-xs text-slate-400">{c.note}</p>}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <StatusBadge c={c} />
                  {c.active && c.amount > 0 && <Button variant="outline" className="px-3 py-1.5" onClick={() => setPay(c)}>Ödədim</Button>}
                  <Button variant="ghost" className="px-3 py-1.5" onClick={() => setEdit(c)}>Dəyiş</Button>
                  <button type="button" onClick={() => void remove(c)} className="px-1 text-slate-400 hover:text-rose-600" aria-label={`${c.name} sil`}>×</button>
                </div>
              </div>
              {c.payments.length > 0 && (
                <p className="mt-2 text-xs text-slate-500">
                  Ödənişlər: {c.payments.slice(0, 4).map((p) => `${formatDate(p.paidOn)} — ${money(p.amount, p.currency as Cost["currency"])}`).join(" · ")}
                  {c.payments.length > 4 ? ` · +${c.payments.length - 4}` : ""}
                </p>
              )}
            </li>
          ))}
        </ul>
      </Card>

      {edit && <CostModal cost={edit === "new" ? null : edit} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); onNotice?.("Yadda saxlandı"); void load(); }} />}
      {pay && <PayModal cost={pay} onClose={() => setPay(null)} onSaved={() => { setPay(null); onNotice?.("Ödəniş qeyd olundu, növbəti tarix yeniləndi"); void load(); }} />}
    </div>
  );
}

function CostModal({ cost, onClose, onSaved }: { cost: Cost | null; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({
    name: cost?.name ?? "", provider: cost?.provider ?? "", url: cost?.url ?? "", amount: cost ? String(cost.amount) : "",
    currency: cost?.currency ?? "USD", period: cost?.period ?? "MONTHLY", nextDue: cost?.nextDue ?? "", note: cost?.note ?? "", active: cost?.active ?? true,
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const json = { ...f, amount: f.amount || "0", nextDue: f.nextDue || null };
      await api(cost ? `/api/admin/costs/${cost.id}` : "/api/admin/costs", { method: cost ? "PUT" : "POST", json });
      onSaved();
    } catch (err2) {
      setError(err(err2, "Saxlanmadı"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title={cost ? "Xərci dəyiş" : "Yeni xərc"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" type="submit" form="cost-form" disabled={busy}>Yadda saxla</Button></>}>
      <form id="cost-form" onSubmit={save} className="grid gap-3 sm:grid-cols-2">
        {error && <div className="sm:col-span-2 text-sm text-rose-600">{error}</div>}
        <Field label="Ad"><Input required maxLength={100} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="məs. Server (backend API)" /></Field>
        <Field label="Provayder"><Input maxLength={100} value={f.provider} onChange={(e) => setF({ ...f, provider: e.target.value })} placeholder="Render, Neon, Hostinger…" /></Field>
        <Field label="Məbləğ (0 = pulsuz)"><Input inputMode="decimal" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value.replace(",", ".") })} /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Valyuta"><Select value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value as Cost["currency"] })}><option value="USD">$ USD</option><option value="AZN">₼ AZN</option><option value="EUR">€ EUR</option></Select></Field>
          <Field label="Dövr"><Select value={f.period} onChange={(e) => setF({ ...f, period: e.target.value as Cost["period"] })}><option value="MONTHLY">Aylıq</option><option value="YEARLY">İllik</option><option value="ONCE">Bir dəfə</option></Select></Field>
        </div>
        <Field label="Növbəti ödəniş tarixi"><Input type="date" value={f.nextDue} onChange={(e) => setF({ ...f, nextDue: e.target.value })} /></Field>
        <Field label="Ödəniş səhifəsi (https://…)"><Input value={f.url} onChange={(e) => setF({ ...f, url: e.target.value })} /></Field>
        <div className="sm:col-span-2"><Field label="Qeyd"><Textarea rows={2} maxLength={500} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} /></Field></div>
        {cost && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} /> Aktiv</label>}
      </form>
    </Modal>
  );
}

function PayModal({ cost, onClose, onSaved }: { cost: Cost; onClose: () => void; onSaved: () => void }) {
  const [paidOn, setPaidOn] = useState(todayBaku());
  const [amount, setAmount] = useState(String(cost.amount));
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal open title={`Ödədim — ${cost.name}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" disabled={busy} onClick={async () => {
        setBusy(true);
        try { await api(`/api/admin/costs/${cost.id}/paid`, { method: "POST", json: { paidOn, amount: amount || "0", note } }); onSaved(); }
        catch (e) { setError(err(e, "Qeyd olunmadı")); setBusy(false); }
      }}>Qeyd et</Button></>}>
      <div className="space-y-3">
        {error && <p className="text-sm text-rose-600">{error}</p>}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Ödəniş günü"><Input type="date" value={paidOn} max={todayBaku()} onChange={(e) => setPaidOn(e.target.value)} /></Field>
          <Field label={`Məbləğ (${SIGN[cost.currency]})`}><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(",", "."))} /></Field>
        </div>
        <Field label="Qeyd (istəyə bağlı)"><Input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} placeholder="məs. kartın son 4 rəqəmi, qəbz nömrəsi" /></Field>
        <p className="text-xs text-slate-500">Növbəti ödəniş tarixi özü {cost.period === "YEARLY" ? "1 il" : cost.period === "MONTHLY" ? "1 ay" : "—"} irəli keçəcək.</p>
      </div>
    </Modal>
  );
}
