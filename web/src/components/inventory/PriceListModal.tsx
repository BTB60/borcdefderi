"use client";

import { useState } from "react";
import { Alert, Badge, Button, Modal, Table, Td, Th } from "@/components/ui";
import { API_URL, ApiError, getToken } from "@/lib/api";
import { formatMoney } from "@/lib/format";

type Line = {
  row: number; fileName: string; productId: string | null; productName: string | null; oldPrice: number | null; newPrice: number;
  oldSalePrice: number | null; newSalePrice: number | null; status: "CHANGED" | "SAME" | "NOT_FOUND";
};
type Result = { applied: boolean; changed: number; same: number; notFound: number; lines: Line[] };

/**
 * Tədarükçünün qiymət siyahısı: upload the supplier's Excel file, see which purchase prices change, then apply.
 * Products are matched by barcode, code or exact name.
 */
export function PriceListModal({ onClose, onDone }: { onClose: () => void; onDone: (m: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [keepMargin, setKeepMargin] = useState(true);
  const [preview, setPreview] = useState<Result | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function send(apply: boolean) {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      fd.append("apply", String(apply));
      fd.append("keepMargin", String(keepMargin));
      const token = getToken();
      const res = await fetch(`${API_URL}/api/products/price-list`, { method: "POST", body: fd, headers: token ? { Authorization: `Bearer ${token}` } : {} });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(res.status, body?.error ?? "PRICE_LIST", body?.message ?? "Fayl oxunmadı");
      const r = body as Result;
      if (apply) onDone(`${r.changed} məhsulun alış qiyməti yeniləndi`);
      else setPreview(r);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Fayl oxunmadı");
    } finally {
      setBusy(false);
    }
  }

  const STATUS = { CHANGED: { l: "Dəyişir", t: "amber" as const }, SAME: { l: "Eynidir", t: "slate" as const }, NOT_FOUND: { l: "Tapılmadı", t: "rose" as const } };

  return (
    <Modal open size="xl" title="Tədarükçünün qiymət siyahısı" onClose={onClose}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Bağla</Button>
        {!preview ? <Button variant="gold" disabled={!file || busy} onClick={() => send(false)}>{busy ? "Oxunur…" : "Yoxla"}</Button>
          : <Button variant="gold" disabled={busy || preview.changed === 0} onClick={() => send(true)}>{busy ? "Saxlanır…" : `${preview.changed} qiyməti yenilə`}</Button>}
      </>}>
      <div className="mb-4 space-y-2 text-sm text-slate-600">
        <p>Tədarükçünün göndərdiyi Excel faylını seçin. Faylda <strong>Qiymət</strong> sütunu və <strong>Barkod</strong>, <strong>Kod</strong> və ya <strong>Ad</strong> sütunlarından biri olmalıdır (ruscası da olar: Цена, Штрихкод, Наименование).</p>
        <p>Əvvəl nəyin dəyişəcəyini görəcəksiniz, sonra təsdiqləyirsiniz. Yalnız alış qiyməti dəyişir; qalıqlara toxunulmur.</p>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <div className="flex flex-wrap items-center gap-4">
        <input type="file" accept=".xlsx,.xls" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setPreview(null); }} className="text-sm" />
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" checked={keepMargin} onChange={(e) => { setKeepMargin(e.target.checked); setPreview(null); }} />
          Satış qiymətini də eyni faizlə dəyiş (qazanc faizi qalsın)
        </label>
      </div>
      {preview && (
        <div className="mt-5">
          <div className="mb-3 flex flex-wrap gap-2 text-sm">
            <Badge tone="amber">{preview.changed} dəyişir</Badge>
            <Badge tone="slate">{preview.same} eynidir</Badge>
            <Badge tone="rose">{preview.notFound} tapılmadı</Badge>
          </div>
          <div className="max-h-96 overflow-y-auto">
            <Table>
              <thead className="bg-slate-50/70"><tr><Th>Fayldakı ad</Th><Th>Məhsul</Th><Th className="text-right">Alış: köhnə → yeni</Th><Th className="text-right">Satış</Th><Th>Vəziyyət</Th></tr></thead>
              <tbody className="divide-y divide-slate-100">
                {preview.lines.map((l) => (
                  <tr key={l.row} className={l.status === "NOT_FOUND" ? "bg-rose-50/50" : ""}>
                    <Td className="text-xs text-slate-500">{l.fileName}</Td>
                    <Td className="font-medium text-navy-800">{l.productName ?? "—"}</Td>
                    <Td className="text-right tabular-nums">{l.oldPrice != null ? `${formatMoney(l.oldPrice)} → ` : ""}<b>{formatMoney(l.newPrice)}</b></Td>
                    <Td className="text-right tabular-nums">{l.oldSalePrice != null && l.newSalePrice != null && l.newSalePrice !== l.oldSalePrice
                      ? `${formatMoney(l.oldSalePrice)} → ${formatMoney(l.newSalePrice)}` : l.oldSalePrice != null ? formatMoney(l.oldSalePrice) : "—"}</Td>
                    <Td><Badge tone={STATUS[l.status].t}>{STATUS[l.status].l}</Badge></Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </div>
        </div>
      )}
    </Modal>
  );
}
