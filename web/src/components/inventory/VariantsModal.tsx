"use client";

import { useEffect, useState } from "react";
import { Alert, Badge, Button, Field, Input, Modal, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { Product } from "@/lib/types";

const q = (n: number) => Number(n).toLocaleString("az-AZ", { maximumFractionDigits: 3 });
const split = (s: string) => s.split(/[,;\n]/).map((x) => x.trim()).filter(Boolean);

/**
 * Ölçü / rəng variantları of a model product. Each variant is a product of its own (own barcode, price and stock),
 * so it is sold, counted and reported like any other product.
 */
export function VariantsModal({ product, manage, onClose, onChanged }: { product: Product; manage: boolean; onClose: () => void; onChanged: () => void }) {
  const [items, setItems] = useState<Product[] | null>(null);
  const [sizes, setSizes] = useState("");
  const [colors, setColors] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    api<Product[]>(`/api/products/${product.id}/variants`).then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [product.id]);

  const s = split(sizes);
  const c = split(colors);
  const count = s.length || c.length ? Math.max(s.length, 1) * Math.max(c.length, 1) : 0;

  async function add() {
    setBusy(true);
    setError(null);
    try {
      const before = items?.length ?? 0;
      const list = await api<Product[]>(`/api/products/${product.id}/variants`, { method: "POST", json: { sizes: s, colors: c } });
      setItems(list);
      setSizes("");
      setColors("");
      setNotice(`${list.length - before} yeni variant əlavə olundu`);
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title={`Variantlar — ${product.name}`} onClose={onClose}
      footer={<Button variant="ghost" onClick={onClose}>Bağla</Button>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-3"><Alert kind="success">{notice}</Alert></div>}
      {manage && (
        <div className="mb-5 rounded-2xl border border-slate-200 bg-slate-50/60 p-4">
          <p className="mb-3 text-sm text-slate-600">
            Ölçüləri və rəngləri vergüllə yazın. Hər ölçü + rəng ayrıca məhsul olur: öz barkodu, qiyməti və qalığı ilə.
            Qiymətlər bu məhsuldan götürülür, sonra hər birini ayrıca dəyişə bilərsiniz.
          </p>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Ölçülər" hint="məs. 38, 39, 40, 41 və ya S, M, L, XL">
              <Input value={sizes} onChange={(e) => setSizes(e.target.value)} placeholder="38, 39, 40" />
            </Field>
            <Field label="Rənglər" hint="məs. Qara, Ağ, Boz">
              <Input value={colors} onChange={(e) => setColors(e.target.value)} placeholder="Qara, Ağ" />
            </Field>
          </div>
          <div className="mt-3 flex items-center justify-between gap-3">
            <span className="text-xs text-slate-500">{count > 0 ? `${count} variant yaradılacaq (mövcud olanlar keçiləcək)` : ""}</span>
            <Button onClick={add} disabled={busy || count === 0}>{busy ? "Yaradılır…" : "Variantları yarat"}</Button>
          </div>
        </div>
      )}
      {!items ? <Spinner /> : items.length === 0 ? (
        <p className="py-6 text-center text-sm text-slate-500">Hələ variant yoxdur.</p>
      ) : (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Ölçü</Th><Th>Rəng</Th><Th>Kod / barkod</Th><Th className="text-right">Satış</Th><Th className="text-right">Qalıq</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((v) => (
              <tr key={v.id} className={v.active ? "" : "opacity-50"}>
                <Td className="font-medium text-navy-800">{v.variantSize ?? "—"}</Td>
                <Td>{v.variantColor ?? "—"}</Td>
                <Td className="text-xs text-slate-500">{v.code}{v.barcode ? ` · ${v.barcode}` : ""}</Td>
                <Td className="text-right tabular-nums">{formatMoney(v.salePrice)}</Td>
                <Td className="text-right">
                  <span className="tabular-nums">{q(v.stockQty)} {v.unit}</span>
                  {v.outOfStock && <div><Badge tone="rose">bitib</Badge></div>}
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      {items && items.length > 0 && (
        <p className="mt-3 text-xs text-slate-500">
          Cəmi qalıq: <b>{q(items.reduce((sum, v) => sum + Number(v.stockQty), 0))}</b>. Variantın qiymətini, barkodunu və qalığını
          Anbar siyahısında həmin variantın sətrindən dəyişin (axtarışa model adını yazın).
        </p>
      )}
    </Modal>
  );
}
