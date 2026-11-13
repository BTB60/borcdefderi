"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { API_URL, ApiError, api, getToken } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney } from "@/lib/format";
import type { InventorySummary, Party, Product, ProductCategory, StockMovement } from "@/lib/types";

const MOVE_LABEL: Record<StockMovement["kind"], string> = {
  RECEIPT: "Anbara giriş", ISSUE: "Anbardan çıxış", SALE: "Satış", SALE_RETURN: "Satış qaytarıldı", ADJUSTMENT: "Düzəliş", COUNT: "Sayım",
};
const q = (n: number) => Number(n).toLocaleString("az-AZ", { maximumFractionDigits: 3 });

/** Anbar: products, stock levels, receipts / issues / adjustments and movement history. Counts live under /anbar/sayim. */
export default function InventoryPage() {
  const { isOwner, can } = useAuth();
  const [items, setItems] = useState<Product[] | null>(null);
  const [summary, setSummary] = useState<InventorySummary | null>(null);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [suppliers, setSuppliers] = useState<Party[]>([]);
  const [filter, setFilter] = useState({ q: "", categoryId: "", stock: "", active: "true" });
  const [editing, setEditing] = useState<Product | "new" | null>(null);
  const [stockOf, setStockOf] = useState<Product | null>(null);
  const [historyOf, setHistoryOf] = useState<Product | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();
  const manage = isOwner || can("INVENTORY");

  const load = useCallback(() => {
    const qs = new URLSearchParams();
    Object.entries(filter).forEach(([k, v]) => v && qs.set(k, v));
    api<Product[]>(`/api/products?${qs}`).then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
    api<InventorySummary>("/api/products/summary").then(setSummary).catch(() => setSummary(null));
  }, [filter]);
  useEffect(load, [load]);
  useEffect(() => {
    api<ProductCategory[]>("/api/product-categories").then(setCategories).catch(() => setCategories([]));
    api<Party[]>("/api/companies").then(setSuppliers).catch(() => setSuppliers([]));
  }, []);

  if (!manage && !can("SALES")) return <Card><EmptyState title="Anbar bölməsi üçün icazəniz yoxdur" /></Card>;

  async function remove(p: Product) {
    if (!(await confirm(<>“{p.name}” silinsin? Hərəkəti və ya satışı olan məhsul silinmir, deaktiv edilir (tarixçə qalır).</>, { danger: true, confirmLabel: "Sil" }))) return;
    try {
      const r = await api<{ deleted: boolean }>(`/api/products/${p.id}`, { method: "DELETE" });
      setNotice(r.deleted ? "Məhsul silindi" : "Məhsulun tarixçəsi olduğu üçün deaktiv edildi");
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Silinmədi");
    }
  }

  return (
    <>
      <PageHeader title="Anbar" subtitle="Məhsullar, qalıqlar və anbar hərəkətləri"
        actions={manage ? <div className="flex gap-2"><Link href="/anbar/sayim" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">Sayım</Link><Button variant="gold" onClick={() => setEditing("new")}>+ Məhsul əlavə et</Button></div> : undefined} />
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <section className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Məhsul sayı" value={String(summary?.activeCount ?? "—")} tone="navy" hint={`cəmi ${summary?.productCount ?? 0}`} />
        <StatCard label="Anbar dəyəri (alış)" value={formatMoney(summary?.costValue)} tone="sky" />
        <StatCard label="Anbar dəyəri (satış)" value={formatMoney(summary?.saleValue)} tone="emerald" />
        <StatCard label="Az qalan / bitən" value={`${summary?.lowStockCount ?? 0} / ${summary?.outOfStockCount ?? 0}`} tone="rose" hint="minimum stokdan aşağı" />
      </section>
      <Card>
        <div className="mb-4 grid gap-3 sm:grid-cols-4">
          <Input placeholder="Ad, kod və ya barkod" value={filter.q} onChange={(e) => setFilter({ ...filter, q: e.target.value })} aria-label="Axtarış" />
          <Select value={filter.categoryId} onChange={(e) => setFilter({ ...filter, categoryId: e.target.value })} aria-label="Kateqoriya">
            <option value="">Bütün kateqoriyalar</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
          <Select value={filter.stock} onChange={(e) => setFilter({ ...filter, stock: e.target.value })} aria-label="Stok">
            <option value="">Bütün qalıqlar</option><option value="LOW">Az qalan</option><option value="OUT">Bitən (0)</option>
          </Select>
          <Select value={filter.active} onChange={(e) => setFilter({ ...filter, active: e.target.value })} aria-label="Status">
            <option value="true">Aktiv</option><option value="false">Deaktiv</option><option value="">Hamısı</option>
          </Select>
        </div>
        {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Məhsul yoxdur" text="“+ Məhsul əlavə et” ilə başlayın." /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Məhsul</Th><Th>Kateqoriya</Th><Th className="text-right">Alış</Th><Th className="text-right">Satış</Th><Th className="text-right">Qalıq</Th><Th className="text-right">Əməliyyat</Th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((p) => (
                <tr key={p.id} className={p.active ? "" : "opacity-50"}>
                  <Td>
                    <span className="font-medium text-navy-800">{p.name}</span>
                    <div className="text-xs text-slate-400">{p.code}{p.barcode ? ` · ${p.barcode}` : ""}{p.supplierName ? ` · ${p.supplierName}` : ""}</div>
                  </Td>
                  <Td className="text-xs">{p.categoryName ?? "—"}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(p.purchasePrice)}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(p.salePrice)}</Td>
                  <Td className="text-right">
                    <span className={`font-semibold tabular-nums ${p.outOfStock ? "text-rose-600" : p.lowStock ? "text-amber-600" : "text-navy-800"}`}>{q(p.stockQty)} {p.unit}</span>
                    {p.outOfStock ? <div><Badge tone="rose">bitib</Badge></div> : p.lowStock ? <div><Badge tone="amber">az qalıb</Badge></div> : null}
                  </Td>
                  <Td className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setHistoryOf(p)}>Tarixçə</Button>
                      {manage && <Button variant="outline" className="px-2.5 py-1.5" onClick={() => setStockOf(p)}>Stok</Button>}
                      {manage && <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setEditing(p)}>Redaktə</Button>}
                      {manage && <Button variant="ghost" className="px-2.5 py-1.5 text-rose-600" onClick={() => remove(p)}>Sil</Button>}
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {editing && <ProductModal product={editing === "new" ? null : editing} categories={categories} suppliers={suppliers}
        onCategory={(c) => setCategories((x) => [...x, c])} onClose={() => setEditing(null)} onSaved={(m) => { setEditing(null); setNotice(m); load(); }} />}
      {stockOf && <StockModal product={stockOf} onClose={() => setStockOf(null)} onSaved={(m) => { setStockOf(null); setNotice(m); load(); }} />}
      {historyOf && <HistoryModal product={historyOf} onClose={() => setHistoryOf(null)} />}
      {confirmDialog}
    </>
  );
}

function ProductModal({ product, categories, suppliers, onCategory, onClose, onSaved }: { product: Product | null; categories: ProductCategory[]; suppliers: Party[]; onCategory: (c: ProductCategory) => void; onClose: () => void; onSaved: (m: string) => void }) {
  const [form, setForm] = useState({
    name: product?.name ?? "", code: product?.code ?? "", barcode: product?.barcode ?? "", categoryId: product?.categoryId ?? "",
    supplierId: product?.supplierId ?? "", unit: product?.unit ?? "ədəd", purchasePrice: product ? String(product.purchasePrice) : "",
    salePrice: product ? String(product.salePrice) : "", minStock: product ? String(product.minStock) : "0", initialStock: "", active: product?.active ?? true,
  });
  const [newCat, setNewCat] = useState("");
  const [image, setImage] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function addCategory() {
    if (!newCat.trim()) return;
    try {
      const c = await api<ProductCategory>("/api/product-categories", { method: "POST", json: { name: newCat.trim() } });
      onCategory(c);
      setForm({ ...form, categoryId: c.id });
      setNewCat("");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Kateqoriya yaradılmadı");
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    setErrors({});
    const body = {
      ...form, code: form.code || null, barcode: form.barcode || null, categoryId: form.categoryId || null, supplierId: form.supplierId || null,
      initialStock: product ? null : form.initialStock || null, version: product?.version,
    };
    try {
      const saved = await api<Product>(product ? `/api/products/${product.id}` : "/api/products", { method: product ? "PUT" : "POST", json: body });
      if (image) {
        const fd = new FormData();
        fd.append("file", image);
        const token = getToken();
        const res = await fetch(`${API_URL}/api/products/${saved.id}/image`, { method: "POST", body: fd, headers: token ? { Authorization: `Bearer ${token}` } : {} });
        if (!res.ok) throw new ApiError(res.status, "IMAGE", "Məhsul saxlandı, amma şəkil yüklənmədi (PNG/JPG, ≤5 MB)");
      }
      onSaved(product ? "Məhsul yeniləndi" : `Məhsul əlavə edildi: ${saved.code}`);
    } catch (err) {
      if (err instanceof ApiError) { setError(err.message); setErrors(err.fields ?? {}); } else setError("Saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title={product ? `Redaktə — ${product.name}` : "Yeni məhsul"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="product-form" variant="gold" disabled={busy}>Saxla</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="product-form" onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2"><Field label="Ad" error={errors.name}><Input required maxLength={200} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field></div>
        <Field label="Məhsul kodu" hint="Boş qalsa avtomatik (P-000001)"><Input maxLength={30} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} /></Field>
        <Field label="Barkod" error={errors.barcode}><Input maxLength={64} value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })} /></Field>
        <Field label="Kateqoriya">
          <div className="flex gap-2">
            <Select value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
              <option value="">—</option>{categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </Select>
          </div>
        </Field>
        <Field label="Yeni kateqoriya"><div className="flex gap-2"><Input value={newCat} onChange={(e) => setNewCat(e.target.value)} /><Button type="button" variant="outline" onClick={addCategory}>+</Button></div></Field>
        <Field label="Təchizatçı (şirkət)">
          <Select value={form.supplierId} onChange={(e) => setForm({ ...form, supplierId: e.target.value })}>
            <option value="">—</option>{suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </Select>
        </Field>
        <Field label="Ölçü vahidi"><Input maxLength={20} value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} placeholder="ədəd, kq, litr" /></Field>
        <Field label="Alış qiyməti (₼)"><Input required type="number" min="0" step="0.01" value={form.purchasePrice} onChange={(e) => setForm({ ...form, purchasePrice: e.target.value })} /></Field>
        <Field label="Satış qiyməti (₼)"><Input required type="number" min="0" step="0.01" value={form.salePrice} onChange={(e) => setForm({ ...form, salePrice: e.target.value })} /></Field>
        <Field label="Minimum stok" hint="Bundan az olanda xəbərdarlıq"><Input type="number" min="0" step="0.001" value={form.minStock} onChange={(e) => setForm({ ...form, minStock: e.target.value })} /></Field>
        {!product && <Field label="Başlanğıc qalıq"><Input type="number" min="0" step="0.001" value={form.initialStock} onChange={(e) => setForm({ ...form, initialStock: e.target.value })} /></Field>}
        <Field label="Şəkil" hint="PNG/JPG, ≤5 MB"><input type="file" accept="image/png,image/jpeg" onChange={(e) => setImage(e.target.files?.[0] ?? null)} className="text-sm" /></Field>
        {product && <label className="flex items-center gap-2 self-end text-sm"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Aktiv (satışda)</label>}
      </form>
    </Modal>
  );
}

function StockModal({ product, onClose, onSaved }: { product: Product; onClose: () => void; onSaved: (m: string) => void }) {
  const [form, setForm] = useState({ kind: "RECEIPT", quantity: "", unitCost: String(product.purchasePrice), note: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const m = await api<StockMovement>("/api/inventory/movements", { method: "POST", json: { productId: product.id, kind: form.kind, quantity: form.quantity, unitCost: form.kind === "RECEIPT" ? form.unitCost || null : null, note: form.note || null } });
      onSaved(`${product.name}: yeni qalıq ${q(m.balanceAfter)} ${product.unit}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Saxlanmadı");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal open title={`Stok — ${product.name}`} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="stock-form" disabled={busy}>Təsdiqlə</Button></>}>
      <p className="mb-4 text-sm text-slate-600">İndiki qalıq: <strong>{q(product.stockQty)} {product.unit}</strong></p>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="stock-form" onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <Field label="Əməliyyat">
          <Select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
            <option value="RECEIPT">Anbara giriş</option><option value="ISSUE">Anbardan çıxış</option>
            <option value="ADJUSTMENT">Düzəliş (±)</option>
          </Select>
        </Field>
        <Field label={form.kind === "ADJUSTMENT" ? "Fərq (+ və ya −)" : "Miqdar"}>
          <Input required autoFocus type="number" step="0.001" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} />
        </Field>
        {form.kind === "RECEIPT" && <Field label="Alış qiyməti (₼)" hint="Fərqlidirsə orta qiymət hesablanır"><Input type="number" min="0" step="0.01" value={form.unitCost} onChange={(e) => setForm({ ...form, unitCost: e.target.value })} /></Field>}
        <div className={form.kind === "RECEIPT" ? "" : "sm:col-span-2"}><Field label="Qeyd"><Input maxLength={500} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></Field></div>
      </form>
      <p className="mt-3 text-xs text-slate-400">Anbar sayımı “Anbar → Sayım” bölməsindən aparılır. Anbara giriş pul hərəkəti yaratmır. Təchizatçıya ödəniş “Şirkətlər” və ya “Kassa” bölməsindən edilir.</p>
    </Modal>
  );
}

function HistoryModal({ product, onClose }: { product: Product; onClose: () => void }) {
  const [items, setItems] = useState<StockMovement[] | null>(null);
  useEffect(() => { api<StockMovement[]>(`/api/inventory/movements?productId=${product.id}`).then(setItems).catch(() => setItems([])); }, [product.id]);
  return (
    <Modal open size="xl" title={`Hərəkətlər — ${product.name}`} onClose={onClose}>
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Hərəkət yoxdur" /> : (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Vaxt</Th><Th>Növ</Th><Th className="text-right">Miqdar</Th><Th className="text-right">Qalıq</Th><Th>Qeyd</Th><Th>İşçi</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((m) => (
              <tr key={m.id}>
                <Td className="whitespace-nowrap text-xs">{formatDateTime(m.createdAt)}</Td>
                <Td className="text-xs">{MOVE_LABEL[m.kind]}</Td>
                <Td className={`text-right tabular-nums ${m.quantity > 0 ? "text-emerald-700" : "text-rose-600"}`}>{m.quantity > 0 ? "+" : ""}{q(m.quantity)}</Td>
                <Td className="text-right tabular-nums">{q(m.balanceAfter)}</Td>
                <Td className="text-xs text-slate-500">{m.note ?? "—"}</Td>
                <Td className="text-xs">{m.actorName}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Modal>
  );
}
