"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, Select, Spinner, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, api, openProtected } from "@/lib/api";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import { PERIOD_LABEL, periodText } from "@/lib/plans";
import type { SubscriptionRecord } from "@/lib/types";

type Props = { onError: (m: string | null) => void; onNotice?: (m: string | null) => void };

export const REQUEST_STATUS_LABEL: Record<string, string> = {
  PENDING: "Yoxlanılır",
  APPROVED: "Təsdiqlənib",
  REJECTED: "Rədd edilib",
};
export const REQUEST_STATUS_TONE: Record<string, "amber" | "emerald" | "rose"> = {
  PENDING: "amber",
  APPROVED: "emerald",
  REJECTED: "rose",
};
export const METHOD_TEXT: Record<string, string> = { M10: "M10", CARD: "Bank kartı" };

function errorText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback;
}

function periodOf(s: SubscriptionRecord): string {
  if (s.periodCode === "CUSTOM" || s.periodDays) return `${s.periodDays} gün`;
  if (s.periodCode) return PERIOD_LABEL[s.periodCode];
  return s.periodMonths ? `${s.periodMonths} ay` : "—";
}

const mismatch = (s: SubscriptionRecord) => s.declaredAmount != null && Math.abs(s.declaredAmount - s.amount) > 0.009;

/** SUPER_ADMIN: payment requests (pending first) and the full payment history. */
export function PaymentRequestsTab({ onError, onNotice }: Props) {
  const [status, setStatus] = useState("PENDING");
  const [items, setItems] = useState<SubscriptionRecord[] | null>(null);
  const [open, setOpen] = useState<SubscriptionRecord | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    setItems(null);
    api<SubscriptionRecord[]>(`/api/admin/subscriptions${status ? `?status=${status}` : ""}`)
      .then(setItems)
      .catch((e) => onError(errorText(e, "Sorğular yüklənmədi")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, reload]);

  return (
    <>
      <Card>
        <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
          <Select value={status} onChange={(e) => setStatus(e.target.value)} className="max-w-xs" aria-label="Status filtri">
            <option value="PENDING">Yoxlanılır (təsdiq gözləyir)</option>
            <option value="ACTIVE">Təsdiqlənib — aktiv</option>
            <option value="EXPIRED">Təsdiqlənib — müddəti bitib</option>
            <option value="REJECTED">Rədd edilənlər</option>
            <option value="">Ödəniş tarixçəsi (hamısı)</option>
          </Select>
          <p className="text-sm text-slate-500">{items ? `${items.length} sorğu` : ""}</p>
        </div>
        {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Sorğu yoxdur" /> : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Mağaza</Th><Th>Paket</Th><Th className="text-right">Məbləğ</Th><Th>Üsul</Th><Th>Ödəniş tarixi</Th><Th>Status</Th><Th className="text-right">Əməliyyat</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((s) => (
                <tr key={s.id} className="transition-colors hover:bg-gold-500/5">
                  <Td>
                    <p className="font-medium text-navy-800">{s.storeName}</p>
                    <p className="text-xs text-slate-500">{s.ownerName ?? "—"}{s.ownerPhone ? ` · ${s.ownerPhone}` : ""}</p>
                  </Td>
                  <Td>
                    <p>{s.planName}</p>
                    <p className="text-xs text-slate-500">{periodOf(s)}</p>
                  </Td>
                  <Td className="text-right tabular-nums">
                    {formatMoney(s.amount)}
                    {mismatch(s) && <p className="text-xs font-medium text-rose-600">bildirilən: {formatMoney(s.declaredAmount ?? 0)}</p>}
                  </Td>
                  <Td>{s.paymentMethod ? METHOD_TEXT[s.paymentMethod] : "—"}</Td>
                  <Td>{s.paymentDate ? formatDate(s.paymentDate) : formatDateTime(s.requestedAt)}</Td>
                  <Td><Badge tone={REQUEST_STATUS_TONE[s.requestStatus]}>{REQUEST_STATUS_LABEL[s.requestStatus]}</Badge></Td>
                  <Td className="text-right">
                    <Button variant={s.status === "PENDING" ? "gold" : "outline"} onClick={() => setOpen(s)}>{s.status === "PENDING" ? "Bax və təsdiqlə" : "Ətraflı"}</Button>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {open && (
        <RequestModal
          sub={open}
          onClose={() => setOpen(null)}
          onDone={(msg) => {
            setOpen(null);
            onNotice?.(msg);
            setReload((r) => r + 1);
          }}
          onError={onError}
        />
      )}
    </>
  );
}

function RequestModal({ sub, onClose, onDone, onError }: { sub: SubscriptionRecord; onClose: () => void; onDone: (m: string) => void; onError: (m: string | null) => void }) {
  const [history, setHistory] = useState<SubscriptionRecord[] | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  useEffect(() => {
    api<SubscriptionRecord[]>(`/api/admin/subscriptions?storeId=${sub.storeId}`).then(setHistory).catch(() => setHistory([]));
  }, [sub.storeId]);

  async function approve() {
    const ok = await confirm(
      <>
        <strong>{sub.storeName}</strong> — {sub.planName}, {periodOf(sub)} ({formatMoney(sub.amount)}) ödənişini təsdiqləyirsiniz?
        {mismatch(sub) && <span className="mt-2 block text-xs font-medium text-rose-600">Diqqət: mağazanın bildirdiyi məbləğ {formatMoney(sub.declaredAmount ?? 0)}-dir.</span>}
        <span className="mt-2 block text-xs text-slate-500">Təsdiqdən sonra abunəlik dərhal aktivləşir (qalan aktiv müddət itmir, yeni dövr ondan sonra başlayır).</span>
      </>,
      { title: "Ödənişi təsdiqlə", confirmLabel: "Bəli, təsdiqlə və aktiv et" },
    );
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api<SubscriptionRecord>(`/api/admin/subscriptions/${sub.id}/approve`, { method: "POST" });
      onDone(`Ödəniş təsdiqləndi — ${sub.storeName}: ${formatDate(r.startDate)} – ${formatDate(r.endDate)}`);
    } catch (e) {
      setError(errorText(e, "Təsdiq alınmadı"));
    } finally {
      setBusy(false);
    }
  }

  async function reject(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api(`/api/admin/subscriptions/${sub.id}/reject`, { method: "POST", json: { reason: reason.trim() } });
      onDone(`Sorğu rədd edildi — ${sub.storeName}`);
    } catch (err) {
      setError(errorText(err, "Rədd edilmədi"));
    } finally {
      setBusy(false);
    }
  }

  const row = (label: string, value: React.ReactNode) => (
    <div className="flex justify-between gap-4 border-b border-slate-100 py-1.5 text-sm last:border-0">
      <span className="text-slate-500">{label}</span>
      <span className="text-right font-medium text-navy-800">{value ?? "—"}</span>
    </div>
  );

  return (
    <Modal
      open
      title={`Ödəniş sorğusu — ${sub.storeName}`}
      onClose={onClose}
      footer={
        sub.status === "PENDING" ? (
          rejecting ? (
            <><Button variant="ghost" onClick={() => setRejecting(false)}>Geri</Button><Button type="submit" form="reject-form" variant="danger" disabled={busy || !reason.trim()}>Rədd et</Button></>
          ) : (
            <><Button variant="outline" onClick={() => setRejecting(true)}>Rədd et</Button><Button variant="gold" disabled={busy} onClick={approve}>{busy ? "Təsdiqlənir…" : "Təsdiqlə"}</Button></>
          )
        ) : <Button variant="ghost" onClick={onClose}>Bağla</Button>
      }
    >
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="mb-4 flex items-center justify-between">
        <Badge tone={REQUEST_STATUS_TONE[sub.requestStatus]}>{REQUEST_STATUS_LABEL[sub.requestStatus]}</Badge>
        <span className="text-xs text-slate-500">Sorğu: {formatDateTime(sub.requestedAt)}</span>
      </div>
      <h3 className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">Mağaza</h3>
      {row("Mağaza", sub.storeName)}
      {row("Sahibi", sub.ownerName)}
      {row("E-poçt", sub.ownerEmail)}
      {row("Telefon", sub.ownerPhone ?? sub.storePhone)}
      <h3 className="mb-1 mt-4 text-xs font-semibold uppercase tracking-wider text-slate-500">Ödəniş</h3>
      {row("Paket", sub.planName)}
      {row("Müddət", periodOf(sub) + (sub.periodMonths || sub.periodDays ? ` (${periodText({ months: sub.periodMonths, days: sub.periodDays })})` : ""))}
      {row("Ödəniləcək məbləğ", formatMoney(sub.amount))}
      {row("Bildirilən məbləğ", sub.declaredAmount != null ? <span className={mismatch(sub) ? "text-rose-600" : ""}>{formatMoney(sub.declaredAmount)}</span> : null)}
      {row("Ödəniş üsulu", sub.paymentMethod ? METHOD_TEXT[sub.paymentMethod] : null)}
      {row("Ödəniş edən", sub.payerName)}
      {row("Ödəniş tarixi", sub.paymentDate ? formatDate(sub.paymentDate) : null)}
      {row("Əməliyyat / istinad", sub.payerReference)}
      {row("Qeyd", sub.note)}
      {sub.startDate && row("Aktiv dövr", `${formatDate(sub.startDate)} – ${formatDate(sub.endDate)}`)}
      {sub.rejectReason && row("Rədd səbəbi", sub.rejectReason)}
      {sub.reviewedAt && row("Baxan", `${sub.reviewedByName ?? "—"} · ${formatDateTime(sub.reviewedAt)}`)}
      <div className="mt-3">
        {sub.hasReceipt ? (
          <Button variant="outline" onClick={() => openProtected(`/api/admin/subscriptions/${sub.id}/receipt`).catch((e) => onError(errorText(e, "Çek açılmadı")))}>Çekə bax</Button>
        ) : <p className="text-xs text-slate-500">Çek yüklənməyib (WhatsApp ilə göndərilə bilər).</p>}
      </div>

      {rejecting && (
        <form id="reject-form" onSubmit={reject} className="mt-4">
          <Field label="Rədd səbəbi" hint="Mağaza bu səbəbi görəcək">
            <Input required autoFocus maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Məs: çekdəki məbləğ uyğun deyil" />
          </Field>
        </form>
      )}

      <h3 className="mb-2 mt-5 text-xs font-semibold uppercase tracking-wider text-slate-500">Mağazanın sorğu tarixçəsi</h3>
      {!history ? <Spinner /> : history.length === 0 ? <p className="text-sm text-slate-500">Tarixçə yoxdur.</p> : (
        <ul className="max-h-48 space-y-1.5 overflow-y-auto pr-1 text-sm">
          {history.map((h) => (
            <li key={h.id} className={`flex items-center justify-between gap-2 rounded-lg px-2 py-1 ${h.id === sub.id ? "bg-gold-500/10" : ""}`}>
              <span className="text-slate-700">{formatDate(h.requestedAt)} · {h.planName} · {periodOf(h)}</span>
              <span className="flex items-center gap-2">
                <span className="tabular-nums text-slate-600">{formatMoney(h.amount)}</span>
                <Badge tone={REQUEST_STATUS_TONE[h.requestStatus]}>{REQUEST_STATUS_LABEL[h.requestStatus]}</Badge>
              </span>
            </li>
          ))}
        </ul>
      )}
      {confirmDialog}
    </Modal>
  );
}
