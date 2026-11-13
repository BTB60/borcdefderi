"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { API_URL, ApiError, api, download, getToken, openProtected } from "@/lib/api";
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
  const [importing, setImporting] = useState(false);
  const [labelsFor, setLabelsFor] = useState<Product | "all" | null>(null);
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

  function exportAs(format: "xlsx" | "pdf") {
    download(`/api/products/export?format=${format}`, `mehsullar.${format}`)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Fayl hazırlanmadı"));
  }

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
        actions={manage ? <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => exportAs("xlsx")}>Excel</Button>
          <Button variant="outline" onClick={() => exportAs("pdf")}>PDF</Button>
          <Button variant="outline" onClick={() => setImporting(true)}>Excel-dən import</Button>
          <Button variant="outline" onClick={() => setLabelsFor("all")}>Barkodları çap et</Button>
          <Link href="/anbar/sayim" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">Sayım</Link>
          <Button variant="gold" onClick={() => setEditing("new")}>+ Məhsul əlavə et</Button>
        </div> : undefined} />
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
                  <Td className="text-right tabular-nums">{formatMoney(p.salePrice)}{p.oldPrice && p.oldPrice > p.salePrice ? <div className="text-xs text-rose-600 line-through">{formatMoney(p.oldPrice)}</div> : null}</Td>
                  <Td className="text-right">
                    <span className={`font-semibold tabular-nums ${p.outOfStock ? "text-rose-600" : p.lowStock ? "text-amber-600" : "text-navy-800"}`}>{q(p.stockQty)} {p.unit}</span>
                    {p.outOfStock ? <div><Badge tone="rose">bitib</Badge></div> : p.lowStock ? <div><Badge tone="amber">az qalıb</Badge></div> : null}
                  </Td>
                  <Td className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setHistoryOf(p)}>Tarixçə</Button>
                      {manage && <Button variant="outline" className="px-2.5 py-1.5" onClick={() => setStockOf(p)}>Stok</Button>}
                      {manage && <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setLabelsFor(p)}>Barkod</Button>}
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
      {labelsFor && <LabelModal product={labelsFor === "all" ? null : labelsFor} onClose={() => setLabelsFor(null)} />}
      {importing && <ImportModal onClose={() => setImporting(false)} onDone={(m) => { setImporting(false); setNotice(m); load(); }} />}
      {confirmDialog}
    </>
  );
}

function ProductModal({ product, categories, suppliers, onCategory, onClose, onSaved }: { product: Product | null; categories: ProductCategory[]; suppliers: Party[]; onCategory: (c: ProductCategory) => void; onClose: () => void; onSaved: (m: string) => void }) {
  const [form, setForm] = useState({
    name: product?.name ?? "", code: product?.code ?? "", barcode: product?.barcode ?? "", categoryId: product?.categoryId ?? "",
    supplierId: product?.supplierId ?? "", unit: product?.unit ?? "ədəd", purchasePrice: product ? String(product.purchasePrice) : "",
    salePrice: product ? String(product.salePrice) : "", minStock: product ? String(product.minStock) : "0", initialStock: "", active: product?.active ?? true,
    oldPrice: product?.oldPrice ? String(product.oldPrice) : "",
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
      oldPrice: form.oldPrice ? form.oldPrice : (product?.oldPrice ? "0" : null),
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
        <Field label="Endirimdən əvvəlki qiymət (₼)" hint="Boş = endirim yoxdur. Barkod etiketində xətli göstərilir"><Input type="number" min="0" step="0.01" value={form.oldPrice} onChange={(e) => setForm({ ...form, oldPrice: e.target.value })} /></Field>
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

interface ImportResult { applied: boolean; created: number; updated: number; unchanged: number; errors: { row: number; message: string }[] }

/** Excel import: the exported file (or the template) with edits; the whole file is rejected when a row is wrong. */
function ImportModal({ onClose, onDone }: { onClose: () => void; onDone: (m: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const token = getToken();
      const res = await fetch(`${API_URL}/api/products/import`, { method: "POST", body: fd, headers: token ? { Authorization: `Bearer ${token}` } : {} });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(res.status, body?.error ?? "IMPORT", body?.message ?? "Import alınmadı");
      const r = body as ImportResult;
      if (r.applied) onDone(`Import tamamlandı: ${r.created} yeni, ${r.updated} yenilənən, ${r.unchanged} dəyişməyən məhsul`);
      else setResult(r);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Import alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title="Excel-dən import" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Bağla</Button><Button type="submit" form="import-form" variant="gold" disabled={!file || busy}>{busy ? "Yüklənir…" : "Import et"}</Button></>}>
      <div className="mb-4 space-y-2 text-sm text-slate-600">
        <p>Sütunlar: <strong>Kod, Barkod, Ad, Kateqoriya, Vahid, Alış qiyməti, Satış qiyməti, Qalıq, Minimum stok, Aktiv</strong>. Ad, alış və satış qiyməti mütləqdir.</p>
        <p>Kodu (və ya barkodu) mövcud məhsula uyğun gələn sətir həmin məhsulu yeniləyir, qalanları yeni məhsul kimi əlavə olunur. Mövcud məhsulların qalığı importla dəyişmir — bunun üçün Sayım istifadə edin. Səhv sətir olsa, heç nə saxlanmır.</p>
        <p>
          <button type="button" className="font-medium text-navy-800 underline" onClick={() => download("/api/products/import-template", "mehsul-import-sablonu.xlsx")}>Boş şablonu yüklə</button>
          {" "}və ya yuxarıdakı “Excel” düyməsi ilə indiki məhsulları yükləyib dəyişdirin.
        </p>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="import-form" onSubmit={submit}>
        <input type="file" accept=".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} className="text-sm" />
      </form>
      {result && !result.applied && (
        <div className="mt-4">
          <Alert>Faylda {result.errors.length} səhv var, heç nə saxlanmadı. Düzəldib yenidən yükləyin.</Alert>
          <ul className="mt-3 max-h-64 space-y-1 overflow-y-auto text-sm">
            {result.errors.map((er, i) => <li key={i}><span className="font-semibold text-rose-600">{er.row ? `Sətir ${er.row}` : "Fayl"}:</span> {er.message}</li>)}
          </ul>
        </div>
      )}
    </Modal>
  );
}

const LABEL_SIZES = [
  { key: "small", title: "Balaca", w: 40, h: 25, show: ["name", "price", "old"] },
  { key: "medium", title: "Orta", w: 58, h: 40, show: ["store", "name", "price", "old"] },
  { key: "large", title: "Böyük", w: 100, h: 50, show: ["store", "name", "code", "price", "old"] },
] as const;
const LABEL_PARTS = [["store", "Mağaza adı"], ["name", "Məhsulun adı"], ["code", "Kod və vahid"], ["price", "Qiymət"], ["old", "Endirim (köhnə qiymət xətli)"]] as const;
const LABEL_KEY = "hesabpro.labelDesign.v1";

interface LabelDesign { size: string; w: number; h: number; barcode: number; font: number; show: string[] }

function loadDesign(): LabelDesign {
  try {
    const raw = localStorage.getItem(LABEL_KEY);
    if (raw) return JSON.parse(raw) as LabelDesign;
  } catch { /* storage unavailable */ }
  return { size: "medium", w: 58, h: 40, barcode: 100, font: 100, show: ["store", "name", "price", "old"] };
}

/** Barcode labels with a designer: size (preset or own mm), barcode / text scale, parts to show; PDF opens in a new tab. */
function LabelModal({ product, onClose }: { product: Product | null; onClose: () => void }) {
  const [d, setD] = useState<LabelDesign>(loadDesign);
  const [copies, setCopies] = useState(1);
  const [perStock, setPerStock] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function pick(key: string) {
    const p = LABEL_SIZES.find((s) => s.key === key);
    setD(p ? { ...d, size: key, w: p.w, h: p.h, show: [...p.show] } : { ...d, size: "custom" });
  }

  async function print() {
    if (d.w < 20 || d.w > 150 || d.h < 12 || d.h > 120) { setError("Etiket ölçüsü: en 20–150 mm, hündürlük 12–120 mm"); return; }
    setBusy(true);
    setError(null);
    try { localStorage.setItem(LABEL_KEY, JSON.stringify(d)); } catch { /* ignore */ }
    const qs = new URLSearchParams({ size: "custom", w: String(d.w), h: String(d.h), barcode: String(d.barcode), font: String(d.font), show: d.show.join(",") });
    if (product) qs.set("ids", product.id);
    if (perStock) qs.set("perStock", "true"); else qs.set("copies", String(copies));
    try {
      await openProtected(`/api/products/labels?${qs}`);
      onClose();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Etiketlər hazırlanmadı");
    } finally {
      setBusy(false);
    }
  }

  // preview (approximate; the PDF is the real label)
  const scale = Math.min(300 / d.w, 160 / d.h);
  const base = Math.max(6, Math.min(14, d.h * 0.25)) * (d.font / 100) * scale * 0.36;
  const sale = product?.salePrice ?? 10;
  const old = product ? product.oldPrice ?? null : 15;
  const discount = d.show.includes("old") && old != null && old > sale;
  const wide = d.show.includes("price") && d.w / d.h >= 1.8;
  const money = (v: number) => `${v.toFixed(2).replace(".", ",")} AZN`;
  const priceEl = (
    <div className={wide ? "text-right" : "flex items-end justify-between"}>
      {discount && <div className="text-slate-500 line-through" style={{ fontSize: base * 0.95 }}>{money(old!)}</div>}
      <div className={wide || discount ? "" : "w-full text-center"} style={{ fontSize: base * 1.7, fontWeight: 900, lineHeight: 1 }}>{money(sale)}</div>
    </div>
  );
  const bars = (
    <div className="flex h-full w-full items-center justify-center">
      <div className="flex h-full flex-col items-center" style={{ width: `${d.barcode}%`, height: `${d.barcode}%` }}>
        <div className="w-full flex-1" style={{ background: "repeating-linear-gradient(90deg,#000 0 2px,#fff 2px 4px,#000 4px 5px,#fff 5px 8px)" }} />
        <div style={{ fontSize: Math.max(6, base * 0.8), letterSpacing: 1 }}>{product?.barcode ?? product?.code ?? "4760000000011"}</div>
      </div>
    </div>
  );

  return (
    <Modal open size="xl" title={product ? `Barkod çap et — ${product.name}` : "Bütün barkodları çap et"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" disabled={busy} onClick={print}>{busy ? "Hazırlanır…" : "Çap et"}</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <div className="grid gap-6 md:grid-cols-2">
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Ölçü</p>
          <div className="flex flex-wrap gap-2">
            {LABEL_SIZES.map((s) => (
              <button key={s.key} type="button" onClick={() => pick(s.key)}
                className={`rounded-full border px-3 py-1.5 text-sm ${d.size === s.key ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>{s.title} {s.w}×{s.h}</button>
            ))}
            <button type="button" onClick={() => pick("custom")}
              className={`rounded-full border px-3 py-1.5 text-sm ${d.size === "custom" ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>Öz ölçüm</button>
          </div>
          {d.size === "custom" && (
            <div className="mt-3 grid grid-cols-2 gap-3">
              <Field label="En (mm)" hint="20–150"><Input type="number" min={20} max={150} value={d.w} onChange={(e) => setD({ ...d, w: Number(e.target.value) })} /></Field>
              <Field label="Hündürlük (mm)" hint="12–120"><Input type="number" min={12} max={120} value={d.h} onChange={(e) => setD({ ...d, h: Number(e.target.value) })} /></Field>
            </div>
          )}
          <p className="mb-2 mt-5 text-xs font-semibold uppercase tracking-wider text-slate-500">Dizayn</p>
          <label className="flex items-center gap-3 text-sm"><span className="w-28">Barkod ölçüsü</span>
            <input type="range" min={30} max={100} step={5} value={d.barcode} onChange={(e) => setD({ ...d, barcode: Number(e.target.value) })} className="flex-1" />
            <span className="w-10 text-right">{d.barcode}%</span></label>
          <label className="mt-2 flex items-center gap-3 text-sm"><span className="w-28">Yazı ölçüsü</span>
            <input type="range" min={50} max={200} step={10} value={d.font} onChange={(e) => setD({ ...d, font: Number(e.target.value) })} className="flex-1" />
            <span className="w-10 text-right">{d.font}%</span></label>
          <div className="mt-3 flex flex-wrap gap-2">
            {LABEL_PARTS.map(([k, t]) => (
              <label key={k} className="flex items-center gap-1.5 rounded-full border border-slate-200 px-3 py-1 text-sm">
                <input type="checkbox" checked={d.show.includes(k)} onChange={(e) => setD({ ...d, show: e.target.checked ? [...d.show, k] : d.show.filter((x) => x !== k) })} />{t}
              </label>
            ))}
          </div>
          <label className="mt-5 flex items-center gap-2 text-sm">
            <input type="checkbox" checked={perStock} onChange={(e) => setPerStock(e.target.checked)} />
            {product ? "Qalıq qədər (anbarda neçə ədəd varsa)" : "Hər məhsuldan qalıq qədər"}
          </label>
          {!perStock && (
            <div className="mt-3 flex items-center gap-3 text-sm">
              <span>{product ? "Nüsxə sayı" : "Hər məhsuldan"}</span>
              <Input type="number" min={1} max={500} value={copies} onChange={(e) => setCopies(Math.max(1, Math.min(500, Number(e.target.value) || 1)))} className="w-24" />
            </div>
          )}
        </div>
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Önizləmə</p>
          <div className="flex min-h-[180px] items-center justify-center rounded-xl bg-slate-100 p-4">
            <div className="flex flex-col overflow-hidden rounded border border-slate-400 bg-white text-black shadow"
              style={{ width: d.w * scale, height: d.h * scale, padding: Math.min(d.w, d.h) * 0.06 * scale }}>
              {d.show.includes("store") && <div className="truncate text-slate-600" style={{ fontSize: base * 0.7 }}>Mağaza</div>}
              {d.show.includes("name") && <div className={d.h >= 35 ? "line-clamp-2" : "truncate"} style={{ fontSize: base, fontWeight: 800, lineHeight: 1.1 }}>{product?.name ?? "Məhsulun adı"}</div>}
              {d.show.includes("code") && <div style={{ fontSize: base * 0.75 }}>{product?.code ?? "P-000001"} · {product?.unit ?? "ədəd"}</div>}
              <div className="flex min-h-0 flex-1 items-end gap-2">
                <div className="h-full min-w-0 flex-1">{bars}</div>
                {wide && d.show.includes("price") && <div>{discount && <div className="text-right font-extrabold" style={{ fontSize: base * 0.75 }}>ENDİRİM</div>}{priceEl}</div>}
              </div>
              {!wide && d.show.includes("price") && priceEl}
            </div>
          </div>
          <p className="mt-3 text-xs text-slate-500">Dizayn bu brauzerdə yadda saxlanır. PDF yeni pəncərədə açılır; çap pəncərəsində barkod printerini seçin və kağız ölçüsünü etiket ölçüsünə uyğun qoyun.</p>
        </div>
      </div>
    </Modal>
  );
}
