"use client";

import { PriceListModal } from "@/components/inventory/PriceListModal";
import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { API_URL, ApiError, api, download, getToken, openProtected } from "@/lib/api";
import { useDebounced } from "@/lib/useDebounced";
import { useAuth } from "@/lib/auth";
import { BulkPriceModal } from "@/components/BulkPriceModal";
import { VariantsModal } from "@/components/inventory/VariantsModal";
import { ProductModal } from "@/components/inventory/ProductModal";
import { LabelDesignControls, LabelPreview, labelSizeOk, loadLabelDesign, saveLabelDesign, type LabelDesign } from "@/components/inventory/LabelDesigner";
import { formatDateTime, formatMoney } from "@/lib/format";
import type { InventorySummary, Party, Product, ProductCategory, StockMovement } from "@/lib/types";
import { UNIT_SUGGESTIONS, isWeighed, unitKind } from "@/lib/units";
import { shortBranch, useBranchStock } from "@/lib/branchStock";

const MOVE_LABEL: Record<StockMovement["kind"], string> = {
  RECEIPT: "Anbara giriş", ISSUE: "Anbardan çıxış", SALE: "Satış", SALE_RETURN: "Satış qaytarıldı", ADJUSTMENT: "Düzəliş", COUNT: "Sayım",
  TRANSFER_OUT: "Filiala göndərildi", TRANSFER_IN: "Filialdan gəldi",
};
const q = (n: number) => Number(n).toLocaleString("az-AZ", { maximumFractionDigits: 3 });

/** Anbar: products, stock levels, receipts / issues / adjustments and movement history. Counts live under /anbar/sayim. */
export default function InventoryPage() {
  const { isOwner, can } = useAuth();
  const [items, setItems] = useState<Product[] | null>(null);
  // Digər filiallarda: the same goods in the owner's other branches
  const branchStock = useBranchStock(items);
  const hasBranches = Object.keys(branchStock).length > 0;
  const [summary, setSummary] = useState<InventorySummary | null>(null);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [suppliers, setSuppliers] = useState<Party[]>([]);
  const [filter, setFilter] = useState({ q: "", categoryId: "", stock: "", active: "true" });
  const [editing, setEditing] = useState<Product | "new" | null>(null);
  const [stockOf, setStockOf] = useState<Product | null>(null);
  const [historyOf, setHistoryOf] = useState<Product | null>(null);
  const [importing, setImporting] = useState(false);
  const [priceList, setPriceList] = useState(false);
  const [bulk, setBulk] = useState(false);
  const [labelsFor, setLabelsFor] = useState<Product | "all" | null>(null);
  const [variantsOf, setVariantsOf] = useState<Product | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();
  const manage = isOwner || can("INVENTORY");

  const searchQ = useDebounced(filter.q);
  const { categoryId, stock: stockFilter, active } = filter;
  const load = useCallback(() => {
    const qs = new URLSearchParams();
    Object.entries({ q: searchQ, categoryId, stock: stockFilter, active }).forEach(([k, v]) => v && qs.set(k, v));
    api<Product[]>(`/api/products?${qs}`).then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
    api<InventorySummary>("/api/products/summary").then(setSummary).catch(() => setSummary(null));
  }, [searchQ, categoryId, stockFilter, active]);
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
          <Button variant="outline" onClick={() => setPriceList(true)}>Qiymət siyahısı</Button>
          <Button variant="outline" onClick={() => setBulk(true)}>Qiymətləri dəyiş</Button>
          <Button variant="outline" onClick={() => setLabelsFor("all")}>Barkodları çap et</Button>
          <Link href="/anbar/qebul" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">Mal qəbulu</Link>
          <Link href="/anbar/sifaris" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">Sifariş siyahısı</Link>
          <Link href="/anbar/sayim" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">Sayım</Link>
          <Link href="/anbar/srok" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">Son istifadə tarixi</Link>
          <Link href="/anbar/yatmis" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">Yatmış mallar</Link>
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
            <thead className="bg-slate-50/70"><tr><Th>Məhsul</Th><Th>Kateqoriya</Th><Th className="text-right">Alış</Th><Th className="text-right">Top 1 · topdan</Th><Th className="text-right">Top 2 · pərakəndə</Th><Th className="text-right">Qalıq</Th>{hasBranches && <Th>Digər filiallarda</Th>}<Th className="text-right">Əməliyyat</Th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((p) => (
                <tr key={p.id} className={p.active ? "" : "opacity-50"}>
                  <Td>
                    <span className="font-medium text-navy-800">{p.name}</span>
                    <div className="text-xs text-slate-400">{p.code}{p.barcode ? ` · ${p.barcode}` : ""}{p.supplierName ? ` · ${p.supplierName}` : ""}</div>
                    {(p.variantCount ?? 0) > 0 && <button onClick={() => setVariantsOf(p)} className="mt-0.5 text-xs font-medium text-gold-600 hover:underline">{p.variantCount} variant (ölçü / rəng)</button>}
                  </Td>
                  <Td className="text-xs">{p.categoryName ?? "—"}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(p.purchasePrice)}</Td>
                  <Td className="text-right tabular-nums text-slate-600">{p.wholesalePrice != null ? formatMoney(p.wholesalePrice) : <span className="text-slate-300">—</span>}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(p.salePrice)}{p.oldPrice && p.oldPrice > p.salePrice ? <div className="text-xs text-rose-600 line-through">{formatMoney(p.oldPrice)}</div> : null}</Td>
                  <Td className="text-right">
                    <span className={`font-semibold tabular-nums ${p.outOfStock ? "text-rose-600" : p.lowStock ? "text-amber-600" : "text-navy-800"}`}>{q(p.stockQty)} {p.unit}</span>
                    {p.outOfStock ? <div><Badge tone="rose">bitib</Badge></div> : p.lowStock ? <div><Badge tone="amber">az qalıb</Badge></div> : null}
                  </Td>
                  {hasBranches && (
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        {(branchStock[p.id] ?? []).map((b) => (
                          <span key={b.storeId} title={b.storeName}
                            className={`rounded-md px-1.5 py-0.5 text-xs tabular-nums ${b.qty > 0 ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-400"}`}>
                            📍 {shortBranch(b.storeName)}: {q(b.qty)}
                          </span>
                        ))}
                      </div>
                    </Td>
                  )}
                  <Td className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setHistoryOf(p)}>Tarixçə</Button>
                      {manage && <Button variant="outline" className="px-2.5 py-1.5" onClick={() => setStockOf(p)}>Stok</Button>}
                      {manage && <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setLabelsFor(p)}>Barkod</Button>}
                      {manage && !p.parentId && <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setVariantsOf(p)}>Variantlar</Button>}
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
      {bulk && <BulkPriceModal categories={categories} suppliers={suppliers} onClose={() => setBulk(false)} onDone={(m) => { setBulk(false); setNotice(m); load(); }} />}
      {editing && <ProductModal product={editing === "new" ? null : editing} categories={categories} suppliers={suppliers}
        onCategory={(c) => setCategories((x) => [...x, c])} onClose={() => setEditing(null)} onSaved={(m) => { setEditing(null); setNotice(m); load(); }} />}
      {stockOf && <StockModal product={stockOf} onClose={() => setStockOf(null)} onSaved={(m) => { setStockOf(null); setNotice(m); load(); }} />}
      {variantsOf && <VariantsModal product={variantsOf} manage={manage} onClose={() => setVariantsOf(null)} onChanged={load} />}
      {historyOf && <HistoryModal product={historyOf} onClose={() => setHistoryOf(null)} />}
      {labelsFor && <LabelModal product={labelsFor === "all" ? null : labelsFor} onClose={() => setLabelsFor(null)} />}
      {priceList && <PriceListModal onClose={() => setPriceList(false)} onDone={(m) => { setPriceList(false); setNotice(m); load(); }} />}
      {importing && <ImportModal onClose={() => setImporting(false)} onDone={(m) => { setImporting(false); setNotice(m); load(); }} />}
      {confirmDialog}
    </>
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
        <p>Sütunlar: <strong>Kod, Barkod, Ad, Kateqoriya, Vahid, Alış qiyməti, Satış qiyməti, Qalıq, Minimum stok, Aktiv, Topdan qiyməti</strong>. Ad, alış və satış qiyməti mütləqdir.</p>
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

/** Barcode labels with a designer: size (preset or own mm), barcode / text scale, parts to show; PDF opens in a new tab. */
function LabelModal({ product, onClose }: { product: Product | null; onClose: () => void }) {
  const [d, setD] = useState<LabelDesign>(loadLabelDesign);
  const [copies, setCopies] = useState(1);
  const [ownCount, setOwnCount] = useState(false);
  // all products: a count per product, quick fill "as many as in stock" / "N of each"
  const [all, setAll] = useState<Product[] | null>(null);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [bulkMode, setBulkMode] = useState<"stock" | "each">("stock");
  const [each, setEach] = useState(1);
  const stockCount = (p: Product) => Math.max(0, Math.ceil(p.stockQty));
  const fill = (list: Product[], mode: "stock" | "each", n: number) =>
    setCounts(Object.fromEntries(list.map((p) => [p.id, mode === "stock" ? Math.min(500, stockCount(p)) : n])));
  useEffect(() => {
    if (product) return;
    api<Product[]>("/api/products?active=true").then((l) => { setAll(l); fill(l, "stock", 1); }).catch(() => setAll([]));
  }, [product]);
  const total = product ? (ownCount ? copies : Math.max(1, Math.min(500, stockCount(product)))) : Object.values(counts).reduce((a, b) => a + b, 0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function print() {
    if (!labelSizeOk(d)) { setError("Etiket ölçüsü: en 20–150 mm, hündürlük 12–120 mm"); return; }
    setBusy(true);
    setError(null);
    saveLabelDesign(d);
    const qs = new URLSearchParams({ size: "custom", w: String(d.w), h: String(d.h), barcode: String(d.barcode), font: String(d.font), show: d.show.join(",") });
    if (total <= 0) { setError("Ən azı 1 etiket seçin"); setBusy(false); return; }
    qs.set("items", product ? `${product.id}:${total}` : Object.entries(counts).filter(([, n]) => n > 0).map(([id, n]) => `${id}:${n}`).join(","));
    try {
      await openProtected(`/api/products/labels?${qs}`);
      onClose();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Etiketlər hazırlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title={product ? `Barkod çap et — ${product.name}` : "Bütün barkodları çap et"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" disabled={busy} onClick={print}>{busy ? "Hazırlanır…" : `Çap et (${total})`}</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <div className="grid gap-6 md:grid-cols-2">
        <div>
          <LabelDesignControls d={d} onChange={setD} />
          {d.show.includes("old") && (product ? !(product.oldPrice && product.oldPrice > product.salePrice) : all != null && !all.some((p) => p.oldPrice && p.oldPrice > p.salePrice)) && (
            <p className="mt-2 text-xs text-amber-700">
              {product
                ? "Bu məhsulda endirim yoxdur. Məhsulu redaktə edib “Endirimdən əvvəlki qiymət” yazın — etiketdə xətli çıxacaq."
                : "Heç bir məhsulda köhnə qiymət yazılmayıb. Endirim yalnız “Endirimdən əvvəlki qiymət” yazılmış məhsulların etiketində xətli çıxır."}
            </p>
          )}
          <p className="mb-2 mt-5 text-xs font-semibold uppercase tracking-wider text-slate-500">Neçə etiket</p>
          {product ? (
            <>
              <div className="flex gap-2">
                <button type="button" onClick={() => setOwnCount(false)} className={`rounded-full border px-3 py-1.5 text-sm ${!ownCount ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>Qalıq qədər ({stockCount(product)})</button>
                <button type="button" onClick={() => setOwnCount(true)} className={`rounded-full border px-3 py-1.5 text-sm ${ownCount ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>Özüm seçirəm</button>
              </div>
              {ownCount && (
                <div className="mt-3 flex items-center gap-3 text-sm">
                  <span>Nüsxə sayı</span>
                  <Input type="number" min={1} max={500} value={copies} onChange={(e) => setCopies(Math.max(1, Math.min(500, Number(e.target.value) || 1)))} className="w-24" />
                </div>
              )}
              {!ownCount && stockCount(product) === 0 && <p className="mt-2 text-xs text-slate-500">Anbarda qalıq yoxdur — 1 etiket çap olunacaq</p>}
            </>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2">
                <button type="button" onClick={() => { setBulkMode("stock"); fill(all ?? [], "stock", each); }} className={`rounded-full border px-3 py-1.5 text-sm ${bulkMode === "stock" ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>Qalıq qədər</button>
                <button type="button" onClick={() => { setBulkMode("each"); fill(all ?? [], "each", each); }} className={`rounded-full border px-3 py-1.5 text-sm ${bulkMode === "each" ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-300"}`}>Hər birindən</button>
                {bulkMode === "each" && <Input type="number" min={1} max={500} value={each} onChange={(e) => { const n = Math.max(1, Math.min(500, Number(e.target.value) || 1)); setEach(n); fill(all ?? [], "each", n); }} className="w-20" />}
              </div>
              {!all ? <Spinner /> : (
                <div className="mt-3 max-h-64 divide-y divide-slate-100 overflow-y-auto rounded-xl border border-slate-200">
                  {all.map((p) => (
                    <div key={p.id} className="flex items-center gap-2 px-3 py-2 text-sm">
                      <input type="checkbox" checked={(counts[p.id] ?? 0) > 0} onChange={(e) => setCounts({ ...counts, [p.id]: e.target.checked ? Math.max(1, stockCount(p)) : 0 })} />
                      <span className="min-w-0 flex-1 truncate">{p.name} <span className="text-xs text-slate-400">· anbarda {stockCount(p)}</span></span>
                      <Input type="number" min={0} max={500} value={counts[p.id] ?? 0} onChange={(e) => setCounts({ ...counts, [p.id]: Math.max(0, Math.min(500, Number(e.target.value) || 0)) })} className="w-20" />
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
          <p className="mt-3 text-sm font-semibold text-navy-800">Cəmi: {total} etiket</p>
        </div>
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Önizləmə</p>
          <LabelPreview d={d} product={product} />
          <p className="mt-3 text-xs text-slate-500">Dizayn mağaza üzrə saxlanılır (Parametrlər → Barkod etiketi dizaynı). PDF yeni pəncərədə açılır; çap pəncərəsində barkod printerini seçin və kağız ölçüsünü etiket ölçüsünə uyğun qoyun.</p>
        </div>
      </div>
    </Modal>
  );
}
