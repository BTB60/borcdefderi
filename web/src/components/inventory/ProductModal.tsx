"use client";

import { useState, type FormEvent } from "react";
import { Alert, Button, Field, Input, Modal, Select } from "@/components/ui";
import { API_URL, ApiError, api, getToken } from "@/lib/api";
import type { Party, Product, ProductCategory } from "@/lib/types";
import { UNIT_SUGGESTIONS, isWeighed, unitKind } from "@/lib/units";

/**
 * Məhsul kartı: create or edit a product with everything the store sets on it (code, barcode, category, supplier,
 * unit, Top 1 / Top 2 prices, discount, minimum stock, warranty, IMEI / serial, picture). Used in Anbar and, for a
 * product that is not in the store yet, in Mal qəbulu: there "Məhsul Sayı" is the quantity of the receipt line
 * (the stock comes in with the receipt), so nothing is booked twice.
 */
export function ProductModal({ product, categories, suppliers, onCategory, onClose, onSaved, initial, forReceipt }: {
  product: Product | null; categories: ProductCategory[]; suppliers: Party[]; onCategory: (c: ProductCategory) => void;
  onClose: () => void; onSaved: (m: string, saved: Product, quantity: number) => void;
  /** Prefilled values for a new product (e.g. the scanned barcode and the receipt's supplier). */
  initial?: { name?: string; barcode?: string; supplierId?: string };
  /** Made from a goods receipt: the count goes to the receipt line, not to the opening stock. */
  forReceipt?: boolean;
}) {
  const [form, setForm] = useState({
    name: product?.name ?? initial?.name ?? "", code: product?.code ?? "", barcode: product?.barcode ?? initial?.barcode ?? "",
    categoryId: product?.categoryId ?? "", supplierId: product?.supplierId ?? initial?.supplierId ?? "", unit: product?.unit ?? "ədəd", purchasePrice: product ? String(product.purchasePrice) : "",
    salePrice: product ? String(product.salePrice) : "", minStock: product ? String(product.minStock) : "0", initialStock: forReceipt ? "1" : "", active: product?.active ?? true,
    oldPrice: product?.oldPrice ? String(product.oldPrice) : "",
    wholesalePrice: product?.wholesalePrice != null ? String(product.wholesalePrice) : "",
    trackSerial: product?.trackSerial ?? false,
    warrantyMonths: product?.warrantyMonths ? String(product.warrantyMonths) : "",
  });
  const [newCat, setNewCat] = useState("");
  const [version, setVersion] = useState(product?.version);
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
      initialStock: product || forReceipt ? null : form.initialStock || null, version,
      oldPrice: form.oldPrice ? form.oldPrice : (product?.oldPrice ? "0" : null),
      wholesalePrice: form.wholesalePrice ? form.wholesalePrice : (product?.wholesalePrice != null ? "0" : null),
      warrantyMonths: form.warrantyMonths ? Number(form.warrantyMonths) : 0,
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
      let result = saved;
      if (image) result = await api<Product>(`/api/products/${saved.id}`).catch(() => saved);
      onSaved(product ? "Məhsul yeniləndi" : `Məhsul əlavə edildi: ${saved.code}`, result, Number(String(form.initialStock).replace(",", ".")) || 0);
    } catch (err) {
      if (err instanceof ApiError && err.code === "VERSION_CONFLICT" && product) {
        // changed elsewhere meanwhile (a goods receipt, a price change): take the latest version and keep the form
        try {
          const fresh = await api<Product>(`/api/products/${product.id}`);
          setVersion(fresh.version);
          setError("Məhsul bu arada başqa yerdə dəyişib (məs. mal qəbulu). Son məlumat yükləndi — yazdıqlarınızı yoxlayıb yenidən “Saxla” basın.");
        } catch {
          setError(err.message);
        }
      } else if (err instanceof ApiError) { setError(err.message); setErrors(err.fields ?? {}); } else setError("Saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title={product ? `Redaktə — ${product.name}` : "Yeni məhsul"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="product-form" variant="gold" disabled={busy}>{forReceipt ? "Yarat və qaiməyə əlavə et" : "Saxla"}</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="product-form" onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2"><Field label="Ad" error={errors.name}><Input required autoFocus={!!forReceipt && !form.name} maxLength={200} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field></div>
        <Field label="Məhsul kodu" hint="Boş qalsa avtomatik (P-000001)"><Input maxLength={30} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} /></Field>
        <Field label="Barkod" error={errors.barcode}>
          <div className="flex gap-2">
            <Input maxLength={64} value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })} />
            <Button type="button" variant="outline" title="Avtomatik barkod" onClick={() => setForm({ ...form, barcode: randomEan13() })}>Avtomatik</Button>
          </div>
        </Field>
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
        <Field label="Ölçü vahidi" hint={isWeighed(form.unit) ? "Kassada çəki qram və ya kq ilə yazılır; qiymət 1 " + (unitKind(form.unit) === "g" ? "qram" : "kq") + " üçündür" : "ədəd — tam say; kq / qram — çəki ilə satış"}>
          <Input maxLength={20} list="unit-options" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} placeholder="ədəd, kq, qram, litr" />
          <datalist id="unit-options">{UNIT_SUGGESTIONS.map((u) => <option key={u} value={u} />)}</datalist>
        </Field>
        <Field label="Alış qiyməti (₼)"><Input required type="number" min="0" step="0.01" value={form.purchasePrice} onChange={(e) => setForm({ ...form, purchasePrice: e.target.value })} /></Field>
        <Field label="Top 1 — topdan qiyməti (₼)" hint="Boş = topdan satışda da pərakəndə qiymət"><Input type="number" min="0" step="0.01" value={form.wholesalePrice} onChange={(e) => setForm({ ...form, wholesalePrice: e.target.value })} /></Field>
        <Field label="Top 2 — pərakəndə satış qiyməti (₼)" hint="Kassa həmişə bu qiymətlə satır"><Input required type="number" min="0" step="0.01" value={form.salePrice} onChange={(e) => setForm({ ...form, salePrice: e.target.value })} /></Field>
        <Field label="Endirimdən əvvəlki qiymət (₼)" hint="Boş = endirim yoxdur. Barkod etiketində xətli göstərilir"><Input type="number" min="0" step="0.01" value={form.oldPrice} onChange={(e) => setForm({ ...form, oldPrice: e.target.value })} /></Field>
        <Field label="Minimum stok" hint="Bundan az olanda xəbərdarlıq"><Input type="number" min="0" step="0.001" value={form.minStock} onChange={(e) => setForm({ ...form, minStock: e.target.value })} /></Field>
        {!product && (
          <Field label="Məhsul Sayı" hint={forReceipt ? "Qaiməyə bu say ilə düşür; anbara qaimə yekunlaşanda əlavə olunur" : undefined}>
            <Input type="number" min="0" step="0.001" required={forReceipt} value={form.initialStock} onChange={(e) => setForm({ ...form, initialStock: e.target.value })} />
          </Field>
        )}
        <Field label="Zəmanət (ay)" hint="Boş = zəmanət yoxdur. Çekdə və zəmanət axtarışında görünür">
          <Input type="number" min="0" max="120" step="1" value={form.warrantyMonths} onChange={(e) => setForm({ ...form, warrantyMonths: e.target.value })} />
        </Field>
        <label className="flex items-start gap-2 self-end text-sm">
          <input type="checkbox" className="mt-0.5" checked={form.trackSerial} onChange={(e) => setForm({ ...form, trackSerial: e.target.checked })} />
          <span>IMEI / seriya nömrəsi soruşulsun<span className="block text-xs text-slate-500">Telefon, elektronika: kassada hər ədədin nömrəsi oxudulur</span></span>
        </label>
        <Field label="Şəkil" hint="PNG/JPG, ≤5 MB"><input type="file" accept="image/png,image/jpeg" onChange={(e) => setImage(e.target.files?.[0] ?? null)} className="text-sm" /></Field>
        {product && <label className="flex items-center gap-2 self-end text-sm"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Aktiv (satışda)</label>}
      </form>
    </Modal>
  );
}

/** A random in-store EAN-13 (prefix 20 — a shop's own goods) with a valid check digit. */
export function randomEan13(): string {
  const r = new Uint8Array(10);
  crypto.getRandomValues(r);
  const d = [2, 0, ...Array.from(r, (x) => x % 10)];
  const sum = d.reduce((a, x, i) => a + x * (i % 2 ? 3 : 1), 0);
  return d.join("") + ((10 - (sum % 10)) % 10);
}
