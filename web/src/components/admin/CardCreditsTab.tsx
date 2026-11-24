"use client";

import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Spinner, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, api, openProtected } from "@/lib/api";
import { formatDateTime, formatMoney } from "@/lib/format";
import { CREDIT_STATUS, type CreditRequest } from "@/components/CardCredits";

type Props = { onError: (m: string | null) => void; onNotice?: (m: string | null) => void };

/** Kart paketləri: stores' payments for bonus card packages — check the receipt, approve (cards are added) or reject. */
export function CardCreditsTab({ onError, onNotice }: Props) {
  const [status, setStatus] = useState<"PENDING" | "ALL">("PENDING");
  const [items, setItems] = useState<CreditRequest[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  const load = useCallback(() => {
    setItems(null);
    api<CreditRequest[]>(`/api/admin/card-credit-requests?status=${status}`).then(setItems)
      .catch((e) => { setItems([]); onError(e instanceof ApiError ? e.message : "Yüklənmədi"); });
  }, [status, onError]);
  useEffect(() => { load(); }, [load]);

  async function act(r: CreditRequest, kind: "approve" | "reject") {
    const ok = kind === "approve"
      ? await confirm(`${r.storeName}: ${r.quantity} kart (${formatMoney(r.amount)}) təsdiqlənsin? Kartlar mağazanın balansına əlavə olunacaq.`, { confirmLabel: "Təsdiqlə" })
      : await confirm(`${r.storeName}: ${r.quantity} kartlıq sorğu rədd edilsin?`, { confirmLabel: "Rədd et", danger: true });
    if (!ok) return;
    const reason = kind === "reject" ? window.prompt("Səbəb (mağaza görəcək):", "Ödəniş daxil olmayıb") : null;
    setBusy(r.id); onError(null);
    try {
      await api(`/api/admin/card-credit-requests/${r.id}/${kind}`, { method: "POST", json: kind === "reject" ? { reason } : {} });
      onNotice?.(kind === "approve" ? `${r.storeName}: ${r.quantity} kart əlavə olundu` : `${r.storeName}: sorğu rədd edildi`);
      load();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-navy-800">Bonus kart paketləri</h2>
          <p className="text-sm text-slate-500">Mağazalar kart paketi üçün ödəyir; təsdiqləyəndə kartlar onların balansına düşür.</p>
        </div>
        <div className="inline-flex rounded-xl bg-slate-100 p-1 text-sm">
          {(["PENDING", "ALL"] as const).map((s) => (
            <button key={s} type="button" onClick={() => setStatus(s)} className={`rounded-lg px-3 py-1.5 ${status === s ? "bg-white font-semibold shadow" : "text-slate-500"}`}>
              {s === "PENDING" ? "Gözləyənlər" : "Hamısı"}
            </button>
          ))}
        </div>
      </div>
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title={status === "PENDING" ? "Gözləyən sorğu yoxdur" : "Hələ sorğu yoxdur"} /> : (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Mağaza</Th><Th className="text-right">Kart</Th><Th className="text-right">Məbləğ</Th><Th>Ödəniş</Th><Th>Tarix</Th><Th>Status</Th><Th /></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((r) => (
              <tr key={r.id}>
                <Td className="font-medium text-navy-800">{r.storeName}{r.note && <div className="text-xs font-normal text-slate-500">{r.note}</div>}</Td>
                <Td className="text-right font-semibold tabular-nums">{r.quantity}</Td>
                <Td className="text-right tabular-nums">{formatMoney(r.amount)}</Td>
                <Td className="text-xs">{r.paymentMethod ?? "—"}{r.payerReference && <div className="font-mono">{r.payerReference}</div>}
                  {r.hasReceipt && <button type="button" className="text-violet-700 underline" onClick={() => openProtected(`/api/card-credits/requests/${r.id}/receipt`).catch(() => onError("Qəbz açılmadı"))}>Qəbzə bax</button>}
                </Td>
                <Td className="text-xs text-slate-500">{formatDateTime(r.requestedAt)}</Td>
                <Td><Badge tone={CREDIT_STATUS[r.status][1]}>{CREDIT_STATUS[r.status][0]}</Badge>{r.rejectReason && <div className="text-xs text-rose-600">{r.rejectReason}</div>}</Td>
                <Td className="text-right">
                  {r.status === "PENDING" && (
                    <div className="flex justify-end gap-1">
                      <Button className="px-2.5 py-1.5" disabled={busy === r.id} onClick={() => act(r, "approve")}>Təsdiqlə</Button>
                      <Button variant="ghost" className="px-2.5 py-1.5 text-rose-600" disabled={busy === r.id} onClick={() => act(r, "reject")}>Rədd et</Button>
                    </div>
                  )}
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      {confirmDialog}
    </Card>
  );
}
