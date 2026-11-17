"use client";

import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, Select, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { PosTerminal } from "@/components/sales/PosTerminal";
import { TillSettings } from "@/components/sales/TillSettings";
import { ShiftBar, ShiftList } from "@/components/sales/Shift";
import { ApiError, api, openProtected } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import type { Sale, SalePage } from "@/lib/types";

const num = (s: string) => (s.trim() === "" ? 0 : Number(s.replace(",", ".")));

/** Satış: the point-of-sale terminal and the sales list with receipt, returns and cancel. */
export default function SalesPage() {
  const [tab, setTab] = useState<"new" | "list" | "shifts" | "settings">("new");
  // the Windows kassa app: one screen, the till fills it
  const [kiosk, setKiosk] = useState(false);
  useEffect(() => setKiosk(!!window.hesabDesktop), []);
  if (kiosk) {
    return (
      <div className="flex h-full flex-col gap-3">
        <div className="flex shrink-0 flex-wrap items-center gap-3">
          <div className="inline-flex rounded-xl bg-white p-1 shadow-sm ring-1 ring-slate-200" role="tablist">
            {([["new", "Kassa"], ["list", "Satışlar"], ["shifts", "Növbələr"], ["settings", "⚙ Parametrlər"]] as const).map(([k, l]) => (
              <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
                className={`rounded-lg px-5 py-2 text-sm font-semibold transition ${tab === k ? "bg-navy-800 text-white shadow" : "text-slate-600 hover:text-navy-800"}`}>{l}</button>
            ))}
          </div>
          <div className="min-w-0 flex-1 [&>div]:mb-0">{tab === "new" && <ShiftBar />}</div>
        </div>
        <div className="min-h-0 flex-1">
          {tab === "new" ? <PosTerminal fill /> : (
            <div className="h-full overflow-y-auto pr-1">{tab === "list" ? <SalesList /> : tab === "shifts" ? <ShiftList /> : <TillSettings />}</div>
          )}
        </div>
      </div>
    );
  }
  return (
    <>
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-serif text-2xl font-semibold text-navy-800">Satış</h1>
          <p className="text-sm text-slate-500">Kassa terminalı: məhsulu seçin və ya barkodu oxudun, ödənişi qəbul edin</p>
        </div>
        <div className="inline-flex rounded-xl bg-white p-1 shadow-sm ring-1 ring-slate-200" role="tablist">
          {([["new", "Kassa"], ["list", "Satışlar"], ["shifts", "Növbələr"]] as const).map(([k, l]) => (
            <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)} className={`rounded-lg px-4 py-2 text-sm font-medium ${tab === k ? "bg-navy-800 text-white shadow" : "text-slate-600"}`}>{l}</button>
          ))}
        </div>
      </div>
      {tab === "new" ? <><ShiftBar /><PosTerminal /></> : tab === "list" ? <SalesList /> : <ShiftList />}
    </>
  );
}

function SalesList() {
  const today = todayBaku();
  const [filter, setFilter] = useState({ from: today.slice(0, 8) + "01", to: today, q: "", status: "" });
  const [data, setData] = useState<SalePage | null>(null);
  const [open, setOpen] = useState<Sale | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    const qs = new URLSearchParams({ size: "100" });
    Object.entries(filter).forEach(([k, v]) => v && qs.set(k, v));
    api<SalePage>(`/api/sales?${qs}`).then(setData).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [filter]);
  useEffect(load, [load]);
  return (
    <>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <section className="mb-5 grid gap-4 sm:grid-cols-2">
        <StatCard label="Satış sayı" value={String(data?.total ?? "—")} tone="navy" />
        <StatCard label="Xalis satış (qaytarmalar çıxılıb)" value={formatMoney(data?.sum)} tone="emerald" />
      </section>
      <Card>
        <div className="mb-4 grid gap-3 sm:grid-cols-4">
          <Input placeholder="Satış nömrəsi, müştəri, kod" value={filter.q} onChange={(e) => setFilter({ ...filter, q: e.target.value })} aria-label="Axtarış" />
          <Input type="date" value={filter.from} onChange={(e) => setFilter({ ...filter, from: e.target.value })} aria-label="Başlanğıc" />
          <Input type="date" value={filter.to} onChange={(e) => setFilter({ ...filter, to: e.target.value })} aria-label="Son" />
          <Select value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })} aria-label="Status"><option value="">Hamısı</option><option value="COMPLETED">Tamamlanıb</option><option value="CANCELLED">Ləğv edilib</option></Select>
        </div>
        {!data ? <Spinner /> : data.items.length === 0 ? <EmptyState title="Satış yoxdur" /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Nömrə</Th><Th>Tarix</Th><Th>Müştəri</Th><Th className="text-right">Yekun</Th><Th>Ödəniş</Th><Th>Status</Th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((s) => (
                <tr key={s.id} className="cursor-pointer hover:bg-gold-500/5" onClick={() => setOpen(s)}>
                  <Td className="font-medium text-navy-800">{s.number}</Td>
                  <Td>{formatDateTime(s.createdAt)}<div className="text-xs text-slate-400">{s.createdByName}</div></Td>
                  <Td>{s.customerName ?? "—"}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(s.total)}{s.returnedAmount > 0 && <div className="text-xs text-rose-600">−{formatMoney(s.returnedAmount)}</div>}</Td>
                  <Td className="text-xs">{[s.paidCash > 0 && `nağd ${formatMoney(s.paidCash)}`, s.paidCard > 0 && `kart ${formatMoney(s.paidCard)}`, s.creditAmount > 0 && `borc ${formatMoney(s.creditAmount)}`].filter(Boolean).join(" · ")}</Td>
                  <Td>{s.status === "CANCELLED" ? <Badge tone="rose">ləğv</Badge> : s.returnedAmount > 0 ? <Badge tone="amber">qismən qaytarılıb</Badge> : <Badge tone="emerald">tamamlanıb</Badge>}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {open && <SaleModal sale={open} onClose={() => setOpen(null)} onChanged={(s) => { setOpen(s); load(); }} />}
    </>
  );
}

function SaleModal({ sale, onClose, onChanged }: { sale: Sale; onClose: () => void; onChanged: (s: Sale) => void }) {
  const { mayChange } = useAuth();
  const [ret, setRet] = useState<Record<string, string>>({});
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, confirmDialog] = useConfirm();
  const active = sale.status === "COMPLETED";

  async function doReturn() {
    const items = Object.entries(ret).filter(([, v]) => num(v) > 0).map(([saleItemId, quantity]) => ({ saleItemId, quantity }));
    if (!items.length) return;
    setBusy(true);
    setError(null);
    try {
      onChanged(await api<Sale>(`/api/sales/${sale.id}/returns`, { method: "POST", json: { items, reason: reason || null } }));
      setRet({});
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Qaytarılmadı");
    } finally {
      setBusy(false);
    }
  }
  async function cancel() {
    if (!(await confirm(<>{sale.number} ləğv edilsin? Mallar anbara qayıdır, borc azalır, ödənilən pul kassadan qaytarılır.</>, { danger: true, confirmLabel: "Ləğv et" }))) return;
    setBusy(true);
    try {
      onChanged(await api<Sale>(`/api/sales/${sale.id}/cancel`, { method: "POST", json: { reason: reason || null } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Ləğv edilmədi");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal open size="xl" title={`Satış ${sale.number}`} onClose={onClose}
      footer={<>
        <Button variant="outline" onClick={() => openProtected(`/api/sales/${sale.id}/receipt`)}>Çek (PDF)</Button>
        <Button variant="outline" onClick={() => openProtected(`/api/sales/${sale.id}/invoice`)}>Qaimə-faktura</Button>
        {active && mayChange(sale.createdBy) && <Button variant="danger" disabled={busy} onClick={cancel}>Satışı ləğv et</Button>}
        <Button variant="ghost" onClick={onClose}>Bağla</Button>
      </>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <p className="mb-3 text-sm text-slate-600">{formatDate(sale.soldOn)} · {sale.createdByName}{sale.customerName ? ` · ${sale.customerName}` : ""}</p>
      <Table>
        <thead className="bg-slate-50/70"><tr><Th>Məhsul</Th><Th className="text-right">Miqdar</Th><Th className="text-right">Qiymət</Th><Th className="text-right">Cəmi</Th>{active && <Th>Qaytar</Th>}</tr></thead>
        <tbody className="divide-y divide-slate-100">
          {sale.items.map((i) => (
            <tr key={i.id}>
              <Td>{i.name}{i.returnedQty > 0 && <div className="text-xs text-rose-600">qaytarılıb {i.returnedQty}</div>}</Td>
              <Td className="text-right tabular-nums">{i.quantity}</Td>
              <Td className="text-right tabular-nums">{formatMoney(i.unitPrice)}</Td>
              <Td className="text-right tabular-nums">{formatMoney(i.lineTotal)}</Td>
              {active && <Td>{i.quantity - i.returnedQty > 0 ? <Input type="number" min="0" max={i.quantity - i.returnedQty} step="0.001" className="w-24" value={ret[i.id] ?? ""} onChange={(e) => setRet({ ...ret, [i.id]: e.target.value })} aria-label="Qaytarılan miqdar" /> : "—"}</Td>}
            </tr>
          ))}
        </tbody>
      </Table>
      <div className="mt-3 grid gap-1 text-sm">
        {sale.discount > 0 && <div className="flex justify-between"><span>Endirim</span><span>−{formatMoney(sale.discount)}</span></div>}
        <div className="flex justify-between font-semibold"><span>Yekun</span><span>{formatMoney(sale.total)}</span></div>
        <div className="flex justify-between text-slate-500"><span>Nağd / Kart / Borc</span><span>{formatMoney(sale.paidCash)} / {formatMoney(sale.paidCard)} / {formatMoney(sale.creditAmount)}</span></div>
        {sale.returns.map((r) => <div key={r.id} className="flex justify-between text-rose-600"><span>Qaytarma {formatDate(r.returnedOn)} (borcdan {formatMoney(r.debtReduced)}, pul {formatMoney(r.refunded)})</span><span>−{formatMoney(r.amount)}</span></div>)}
      </div>
      {active && (
        <div className="mt-4 flex flex-wrap items-end gap-2">
          <div className="flex-1"><Field label="Səbəb"><Input maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} /></Field></div>
          <Button variant="primary" disabled={busy || !Object.values(ret).some((v) => num(v) > 0)} onClick={doReturn}>Seçilənləri qaytar</Button>
        </div>
      )}
      <p className="mt-3 text-xs text-slate-400">Qaytarma əvvəlcə bu satışın ödənilməmiş borcunu azaldır, qalanı kassadan pul kimi qaytarılır.</p>
      {confirmDialog}
    </Modal>
  );
}
