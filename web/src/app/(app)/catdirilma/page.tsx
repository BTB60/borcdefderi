"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, Table, Td, Th, Textarea } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney } from "@/lib/format";
import { waLink, waWebLink } from "@/lib/whatsapp";

type Status = "NEW" | "ASSIGNED" | "ON_THE_WAY" | "DELIVERED" | "CANCELLED";
type Delivery = {
  id: string; number: string; status: Status; saleId: string | null; saleNumber: string | null; saleTotal: number | null;
  customerId: string | null; recipientName: string; recipientPhone: string; address: string; note: string | null;
  courierUserId: string | null; courierName: string | null; courierPhone: string | null; fee: number;
  collectAmount: number; collectedAmount: number | null; scheduledFor: string | null; createdAt: string;
  assignedAt: string | null; pickedUpAt: string | null; deliveredAt: string | null; cancelledAt: string | null;
  cancelReason: string | null; trackToken: string; createdByName: string;
};
type Courier = { userId: string; fullName: string; phone: string | null; activeDeliveries: number };
type CourierSummary = { courierUserId: string | null; courierName: string; delivered: number; active: number; cancelled: number; fees: number; collected: number };

const DELIVERY_STATUS: Record<Status, { label: string; tone: "slate" | "sky" | "amber" | "emerald" | "rose" }> = {
  NEW: { label: "Yeni", tone: "slate" },
  ASSIGNED: { label: "Kuryerə verilib", tone: "sky" },
  ON_THE_WAY: { label: "Yoldadır", tone: "amber" },
  DELIVERED: { label: "Çatdırılıb", tone: "emerald" },
  CANCELLED: { label: "Ləğv edilib", tone: "rose" },
};

const trackUrl = (token: string) => `${typeof window !== "undefined" ? window.location.origin : ""}/izle/${token}`;

/** Çatdırılma və kuryer: create, give to a courier, follow the status; a courier sees only their own deliveries. */
export default function DeliveriesPage() {
  const { isOwner, can, me } = useAuth();
  const manager = isOwner || can("SALES");
  const courierOnly = !manager && me?.jobTitle === "COURIER";
  const [tab, setTab] = useState<"ACTIVE" | "DELIVERED" | "CANCELLED" | "REPORT">("ACTIVE");
  const [items, setItems] = useState<Delivery[] | null>(null);
  const [report, setReport] = useState<CourierSummary[] | null>(null);
  const [couriers, setCouriers] = useState<Courier[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [assigning, setAssigning] = useState<Delivery | null>(null);

  const fail = (e: unknown) => setError(e instanceof ApiError ? e.message : "Alınmadı");
  const load = useCallback(() => {
    if (tab === "REPORT") { setReport(null); api<CourierSummary[]>("/api/deliveries/summary").then(setReport).catch(fail); return; }
    setItems(null);
    api<Delivery[]>(`/api/deliveries?status=${tab}`).then(setItems).catch(fail);
  }, [tab]);
  useEffect(load, [load]);
  useEffect(() => { if (manager) api<Courier[]>("/api/deliveries/couriers").then(setCouriers).catch(() => setCouriers([])); }, [manager]);

  if (!manager && !courierOnly) return <Card><EmptyState title="Bu bölmə üçün icazəniz yoxdur" /></Card>;

  async function act(d: Delivery, action: "pick-up" | "deliver" | "cancel", json?: unknown) {
    setError(null);
    try {
      await api(`/api/deliveries/${d.id}/${action}`, { method: "POST", json: json ?? {} });
      load();
    } catch (e) {
      fail(e);
    }
  }

  async function deliver(d: Delivery) {
    let amount: number | undefined;
    if (d.collectAmount > 0) {
      const v = window.prompt(`Müştəridən alınan məbləğ (gözlənilən ${d.collectAmount} ₼):`, String(d.collectAmount));
      if (v === null) return;
      amount = Number(v.replace(",", "."));
      if (Number.isNaN(amount) || amount < 0) { setError("Məbləğ düzgün deyil"); return; }
    }
    await act(d, "deliver", amount === undefined ? {} : { collectedAmount: amount });
  }

  async function cancel(d: Delivery) {
    const reason = window.prompt("Ləğv səbəbi (istəyə görə):", "");
    if (reason === null) return;
    await act(d, "cancel", { reason });
  }

  function share(d: Delivery) {
    const text = `Salam, ${d.recipientName}! Sifarişiniz (${d.number}) yoldadır. Çatdırılmanı buradan izləyin: ${trackUrl(d.trackToken)}`;
    const url = window.innerWidth >= 1024 ? waWebLink(d.recipientPhone, text) : waLink(d.recipientPhone, text);
    if (!url) { setError("Alıcının telefon nömrəsi düzgün deyil"); return; }
    window.open(url, "_blank", "noopener");
  }

  const tabs = (courierOnly
    ? [["ACTIVE", "Aktiv"], ["DELIVERED", "Çatdırılıb"]]
    : [["ACTIVE", "Aktiv"], ["DELIVERED", "Çatdırılıb"], ["CANCELLED", "Ləğv edilib"], ["REPORT", "Kuryer hesabatı"]]) as [typeof tab, string][];

  return (
    <>
      <PageHeader title={courierOnly ? "Mənim çatdırılmalarım" : "Çatdırılma"}
        subtitle={courierOnly ? "Sizə verilən sifarişlər. Götürəndə “Yola çıxdım”, təhvil verəndə “Çatdırdım” basın." : "Sifarişi kuryerə verin, statusu izləyin, müştəriyə izləmə linki göndərin."}
        actions={manager ? <Button onClick={() => setCreating(true)}>+ Yeni çatdırılma</Button> : undefined} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <div className="mb-4 inline-flex flex-wrap rounded-xl bg-slate-100 p-1" role="tablist">
        {tabs.map(([k, l]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`rounded-lg px-4 py-1.5 text-sm font-medium ${tab === k ? "bg-white text-navy-800 shadow" : "text-slate-500"}`}>{l}</button>
        ))}
      </div>

      {tab === "REPORT" ? (
        <Card>
          <p className="mb-3 text-sm text-slate-500">Bu ay üzrə. “Alınan” — kuryerin müştəridən aldığı nağd pul (mağazaya təhvil verməlidir).</p>
          {!report ? <Spinner /> : report.length === 0 ? <EmptyState title="Bu ay kuryerə verilən sifariş yoxdur" /> : (
            <Table>
              <thead className="bg-slate-50/70"><tr><Th>Kuryer</Th><Th className="text-right">Çatdırıb</Th><Th className="text-right">Aktiv</Th><Th className="text-right">Ləğv</Th><Th className="text-right">Çatdırılma haqqı</Th><Th className="text-right">Alınan nağd</Th></tr></thead>
              <tbody className="divide-y divide-slate-100">
                {report.map((r) => (
                  <tr key={(r.courierUserId ?? "") + r.courierName}>
                    <Td className="font-medium text-navy-800">{r.courierName}{!r.courierUserId && <span className="ml-1 text-xs text-slate-400">(kənar)</span>}</Td>
                    <Td className="text-right tabular-nums">{r.delivered}</Td>
                    <Td className="text-right tabular-nums">{r.active}</Td>
                    <Td className="text-right tabular-nums">{r.cancelled}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(r.fees)}</Td>
                    <Td className="text-right font-semibold tabular-nums">{formatMoney(r.collected)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      ) : !items ? <Spinner /> : items.length === 0 ? (
        <Card><EmptyState title={tab === "ACTIVE" ? "Aktiv çatdırılma yoxdur" : "Siyahı boşdur"} /></Card>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {items.map((d) => {
            const st = DELIVERY_STATUS[d.status];
            const open = ["NEW", "ASSIGNED", "ON_THE_WAY"].includes(d.status);
            return (
              <Card key={d.id}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-semibold text-navy-800">{d.recipientName} <span className="whitespace-nowrap text-xs font-normal text-slate-400">{d.number}</span></p>
                    <a href={`tel:${d.recipientPhone}`} className="text-sm text-gold-600 hover:underline">{d.recipientPhone}</a>
                    <p className="mt-1 text-sm text-slate-700">📍 {d.address}</p>
                    {d.note && <p className="mt-1 text-xs text-slate-500">{d.note}</p>}
                  </div>
                  <div className="text-right">
                    <Badge tone={st.tone}>{st.label}</Badge>
                    {d.collectAmount > 0 && <p className="mt-1 whitespace-nowrap text-sm font-semibold text-navy-800">Alınacaq: {formatMoney(d.collectAmount)}</p>}
                    {d.collectedAmount != null && <p className="mt-1 text-xs text-emerald-700">Alındı: {formatMoney(d.collectedAmount)}</p>}
                  </div>
                </div>
                <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
                  {d.saleNumber && <span>Satış {d.saleNumber} · {formatMoney(d.saleTotal)}</span>}
                  {d.fee > 0 && <span>Haqq: {formatMoney(d.fee)}</span>}
                  <span>Kuryer: {d.courierName ?? "təyin edilməyib"}{d.courierPhone ? ` · ${d.courierPhone}` : ""}</span>
                  <span>{formatDateTime(d.deliveredAt ?? d.cancelledAt ?? d.pickedUpAt ?? d.assignedAt ?? d.createdAt)}</span>
                  {d.cancelReason && <span>Səbəb: {d.cancelReason}</span>}
                </div>
                {open && (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {manager && <Button variant="outline" className="px-3 py-1.5" onClick={() => setAssigning(d)}>{d.courierName ? "Kuryeri dəyiş" : "Kuryerə ver"}</Button>}
                    {d.status === "ASSIGNED" && <Button variant="gold" className="px-3 py-1.5" onClick={() => act(d, "pick-up")}>Yola çıxdı</Button>}
                    {d.status !== "NEW" && <Button className="px-3 py-1.5" onClick={() => deliver(d)}>Çatdırıldı</Button>}
                    <Button variant="ghost" className="px-3 py-1.5" onClick={() => share(d)}>Müştəriyə link</Button>
                    {manager && <Button variant="ghost" className="px-3 py-1.5 text-rose-600" onClick={() => cancel(d)}>Ləğv et</Button>}
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}

      {creating && <CreateModal couriers={couriers} onClose={() => setCreating(false)} onSaved={() => { setCreating(false); setTab("ACTIVE"); load(); }} />}
      {assigning && <AssignModal delivery={assigning} couriers={couriers} onClose={() => setAssigning(null)} onSaved={() => { setAssigning(null); load(); }} />}
    </>
  );
}

function CourierFields({ couriers, value, onChange }: {
  couriers: Courier[]; value: { courierUserId: string; courierName: string; courierPhone: string };
  onChange: (v: { courierUserId: string; courierName: string; courierPhone: string }) => void;
}) {
  return (
    <>
      <Field label="Kuryer" hint={couriers.length === 0 ? "İşçilər bölməsində vəzifəsi “Kuryer” olan işçi əlavə edin və ya kənar kuryerin adını yazın" : undefined}>
        <Select value={value.courierUserId} onChange={(e) => onChange({ ...value, courierUserId: e.target.value })}>
          <option value="">{couriers.length ? "— kənar kuryer / sonra —" : "— kənar kuryer —"}</option>
          {couriers.map((c) => <option key={c.userId} value={c.userId}>{c.fullName}{c.activeDeliveries ? ` (${c.activeDeliveries} aktiv)` : ""}</option>)}
        </Select>
      </Field>
      {!value.courierUserId && (
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Kənar kuryerin adı"><Input value={value.courierName} maxLength={150} onChange={(e) => onChange({ ...value, courierName: e.target.value })} placeholder="məs. Taksi Elvin" /></Field>
          <Field label="Kuryerin telefonu"><Input value={value.courierPhone} maxLength={30} onChange={(e) => onChange({ ...value, courierPhone: e.target.value })} /></Field>
        </div>
      )}
    </>
  );
}

function CreateModal({ couriers, onClose, onSaved }: { couriers: Courier[]; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ recipientName: "", recipientPhone: "", address: "", note: "", fee: "", collectAmount: "" });
  const [courier, setCourier] = useState({ courierUserId: "", courierName: "", courierPhone: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api("/api/deliveries", {
        method: "POST", json: {
          ...form, fee: form.fee || 0, collectAmount: form.collectAmount || 0,
          courierUserId: courier.courierUserId || null, courierName: courier.courierUserId ? null : courier.courierName || null,
          courierPhone: courier.courierUserId ? null : courier.courierPhone || null,
        },
      });
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title="Yeni çatdırılma" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="delivery-form" disabled={busy}>Yarat</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <form id="delivery-form" onSubmit={submit} className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Alıcının adı"><Input required maxLength={150} value={form.recipientName} onChange={(e) => setForm({ ...form, recipientName: e.target.value })} /></Field>
          <Field label="Telefon"><Input required maxLength={30} value={form.recipientPhone} onChange={(e) => setForm({ ...form, recipientPhone: e.target.value })} placeholder="050 123 45 67" /></Field>
        </div>
        <Field label="Ünvan"><Textarea required rows={2} maxLength={500} value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} /></Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Qapıda alınacaq məbləğ (₼)" hint="Nağd ödəniş çatdırılanda alınırsa"><Input type="number" min="0" step="0.01" value={form.collectAmount} onChange={(e) => setForm({ ...form, collectAmount: e.target.value })} /></Field>
          <Field label="Çatdırılma haqqı (₼)"><Input type="number" min="0" step="0.01" value={form.fee} onChange={(e) => setForm({ ...form, fee: e.target.value })} /></Field>
        </div>
        <Field label="Qeyd"><Input maxLength={500} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} placeholder="məs. 18:00-dan sonra, domofon 25" /></Field>
        <CourierFields couriers={couriers} value={courier} onChange={setCourier} />
      </form>
    </Modal>
  );
}

function AssignModal({ delivery, couriers, onClose, onSaved }: { delivery: Delivery; couriers: Courier[]; onClose: () => void; onSaved: () => void }) {
  const [courier, setCourier] = useState({ courierUserId: delivery.courierUserId ?? "", courierName: delivery.courierUserId ? "" : delivery.courierName ?? "", courierPhone: delivery.courierUserId ? "" : delivery.courierPhone ?? "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    try {
      await api(`/api/deliveries/${delivery.id}/assign`, {
        method: "POST", json: courier.courierUserId ? { courierUserId: courier.courierUserId } : { courierName: courier.courierName, courierPhone: courier.courierPhone || null },
      });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title={`Kuryer — ${delivery.number}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button onClick={save} disabled={busy || (!courier.courierUserId && !courier.courierName.trim())}>Saxla</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="space-y-4"><CourierFields couriers={couriers} value={courier} onChange={setCourier} /></div>
    </Modal>
  );
}
