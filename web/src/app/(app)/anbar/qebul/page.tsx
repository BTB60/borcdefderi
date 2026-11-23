"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import { PaymentModal } from "@/components/debts/DebtModals";
import type { Debt, Party, Product, ProductCategory, Purchase } from "@/lib/types";
import { ProductModal } from "@/components/inventory/ProductModal";

type Line = { product: Product; quantity: string; unitCost: string; expiryDate: string; salePrice: string };

/** The receipt being filled in, kept in this browser so a closed window or a lost connection loses nothing. */
type Draft = { companyId: string; invoice: string; dueDate: string; paid: string; method: string; note: string; lines: Line[]; savedAt: string };
const DRAFT_KEY = "hp_purchase_draft";
const readDraft = (): Draft | null => {
  try { return JSON.parse(localStorage.getItem(DRAFT_KEY) ?? "null") as Draft | null; } catch { return null; }
};
const writeDraft = (d: Draft | null) => {
  try { if (d) localStorage.setItem(DRAFT_KEY, JSON.stringify(d)); else localStorage.removeItem(DRAFT_KEY); } catch { /* storage blocked */ }
};

const num = (s: string) => {
  const n = Number(String(s).replace(",", "."));
  return Number.isFinite(n) ? n : 0;
};
const round2 = (n: number) => Math.round(n * 100) / 100;
const newId = () => (typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : undefined);

/** Mal qəbulu: goods from a supplier in one document (stock, purchase price and supplier debt at once). */
export default function PurchasesPage() {
  const { isOwner, can, me } = useAuth();
  const manage = isOwner || can("INVENTORY");
  const canPay = isOwner || can("COMPANY_PAYABLES");
  const [paying, setPaying] = useState<{ debt: Debt; purchaseId: string } | null>(null);

  /** "Borcu ödə": the receipt's own supplier debt, paid with the usual payment form. */
  async function payDebt(p: Purchase) {
    if (!p.debtId) return;
    setError(null);
    try {
      setPaying({ debt: await api<Debt>(`/api/debts/${p.debtId}`), purchaseId: p.id });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Borc açılmadı");
    }
  }
  const [items, setItems] = useState<Purchase[] | null>(null);
  const [creating, setCreating] = useState(false);
  const [viewing, setViewing] = useState<Purchase | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = () => api<Purchase[]>("/api/purchases").then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  useEffect(() => { if (manage) load(); }, [manage]);

  if (!manage) return <Card><EmptyState title="Mal qəbulu üçün icazəniz yoxdur" /></Card>;

  return (
    <>
      <PageHeader title="Mal qəbulu" subtitle="Təchizatçıdan gələn mal: anbar qalığı, alış qiyməti və şirkətə borc bir sənəddə"
        actions={<div className="flex flex-wrap gap-2">
          <Link href="/anbar" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500 hover:text-gold-600">← Anbar</Link>
          <Button variant="gold" onClick={() => setCreating(true)}>+ Yeni mal qəbulu</Button>
        </div>} />
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <Card>
        {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Hələ mal qəbulu yoxdur" /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Nömrə</Th><Th>Tarix</Th><Th>Təchizatçı</Th><Th>Qaimə</Th><Th className="text-right">Məbləğ</Th><Th className="text-right">Ödənildi</Th><Th className="text-right">Borc</Th><Th>İşçi</Th>{canPay && <Th />}</tr></thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((p) => (
                <tr key={p.id} className="cursor-pointer hover:bg-slate-50" onClick={() => api<Purchase>(`/api/purchases/${p.id}`).then(setViewing)}>
                  <Td className="font-semibold text-navy-800">{p.number}</Td>
                  <Td>{formatDate(p.receivedOn)}</Td>
                  <Td>{p.companyName ?? <span className="text-slate-400">—</span>}</Td>
                  <Td className="text-xs">{p.supplierInvoice ?? "—"}</Td>
                  <Td className="text-right font-semibold tabular-nums">{formatMoney(p.total)}</Td>
                  <Td className="text-right tabular-nums text-emerald-700">{formatMoney(p.paid)}</Td>
                  <Td className="text-right tabular-nums">{p.unpaid > 0 ? <span className="text-rose-600">{formatMoney(p.unpaid)}</span> : "—"}</Td>
                  <Td className="text-xs">{p.createdByName}</Td>
                  {canPay && <Td className="text-right">
                    {p.unpaid > 0 && p.debtId && (
                      <Button variant="outline" onClick={(e) => { e.stopPropagation(); payDebt(p); }}>Borcu ödə</Button>
                    )}
                  </Td>}
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {creating && <PurchaseForm onClose={() => setCreating(false)} onSaved={(p) => {
        setCreating(false);
        setNotice(`${p.number} qəbul edildi: ${p.items.length} məhsul, ${formatMoney(p.total)}`);
        load();
      }} />}
      {viewing && (
        <Modal open title={`${viewing.number} · ${formatDate(viewing.receivedOn)}`} onClose={() => setViewing(null)}>
          <p className="mb-3 text-sm text-slate-600">
            {viewing.companyName ?? "Təchizatçısız"}{viewing.supplierInvoice ? ` · qaimə ${viewing.supplierInvoice}` : ""} · {viewing.createdByName} · {formatDateTime(viewing.createdAt)}
          </p>
          <Table>
            <thead><tr><Th>Məhsul</Th><Th className="text-right">Miqdar</Th><Th className="text-right">Qiymət</Th><Th className="text-right">Cəmi</Th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {viewing.items.map((i) => (
                <tr key={i.id}><Td>{i.name}{i.expiryDate ? <div className="text-xs text-slate-500">son istifadə: {formatDate(i.expiryDate)}</div> : null}{i.returnedQty ? <div className="text-xs text-rose-600">qaytarılıb: {Number(i.returnedQty)}</div> : null}</Td><Td className="text-right tabular-nums">{Number(i.quantity)}</Td>
                  <Td className="text-right tabular-nums">{formatMoney(i.unitCost)}</Td><Td className="text-right font-semibold tabular-nums">{formatMoney(i.lineTotal)}</Td></tr>
              ))}
            </tbody>
          </Table>
          <div className="mt-3 flex flex-wrap justify-end gap-3 text-sm">
            <Badge tone="navy">Cəmi {formatMoney(viewing.total)}</Badge>
            <Badge tone="emerald">Ödənildi {formatMoney(viewing.paid)}</Badge>
            {viewing.unpaid > 0 && <Badge tone="rose">Borc {formatMoney(viewing.unpaid)}</Badge>}
            {canPay && viewing.unpaid > 0 && viewing.debtId && <Button variant="gold" onClick={() => payDebt(viewing)}>Borcu ödə</Button>}
            {!!viewing.returnedAmount && <Badge tone="amber">Qaytarılıb {formatMoney(viewing.returnedAmount)}</Badge>}
          </div>
          {viewing.note && <p className="mt-3 text-sm text-slate-500">{viewing.note}</p>}
          {canPay && <ChangeSupplier purchase={viewing} onDone={(p, msg) => { setViewing(p); setNotice(msg); load(); }} />}
          <ReturnToSupplier purchase={viewing} onDone={(p, msg) => { setViewing(p); setNotice(msg); load(); }} />
        </Modal>
      )}
      {paying && (
        <PaymentModal debt={paying.debt} storeName={me?.storeName ?? ""} canNotify={false}
          onClose={() => setPaying(null)}
          onSaved={() => {
            const id = paying.purchaseId;
            setPaying(null);
            setNotice("Təchizatçıya ödəniş qeyd edildi");
            load();
            if (viewing?.id === id) api<Purchase>(`/api/purchases/${id}`).then(setViewing).catch(() => undefined);
          }} />
      )}
    </>
  );
}

/**
 * Tədarükçünü dəyiş: a receipt entered under the wrong supplier (or none) moves to the right one. The goods and stock
 * stay; what is still owed for the receipt moves from the old supplier's debt to the new one's.
 */
function ChangeSupplier({ purchase, onDone }: { purchase: Purchase; onDone: (p: Purchase, msg: string) => void }) {
  const [open, setOpen] = useState(false);
  const [suppliers, setSuppliers] = useState<Party[] | null>(null);
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();
  useEffect(() => { if (open && !suppliers) api<Party[]>("/api/companies").then(setSuppliers).catch(() => setSuppliers([])); }, [open, suppliers]);

  const owed = purchase.companyId ? Math.max(0, Math.min(purchase.unpaid, round2(purchase.total - (purchase.returnedAmount ?? 0)))) : 0;

  async function save() {
    const name = suppliers?.find((s) => s.id === target)?.name ?? "";
    const ok = await confirm(
      <>
        <span className="block"><b>{purchase.number}</b>: {purchase.companyName ?? "Təchizatçısız"} → <b>{name}</b></span>
        {owed > 0 && <span className="mt-1 block">Bu qaimənin borcu ({formatMoney(owed)}) {purchase.companyName} hesabından çıxıb {name} hesabına keçəcək.</span>}
        <span className="mt-1 block text-slate-500">Mal, anbar qalığı və qiymətlər dəyişmir.</span>
      </>, { title: "Tədarükçünü dəyiş", confirmLabel: "Bəli, dəyiş" });
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      const p = await api<Purchase>(`/api/purchases/${purchase.id}/supplier`, { method: "POST", json: { companyId: target } });
      setOpen(false);
      onDone(p, `${p.number}: tədarükçü “${name}” olaraq dəyişdirildi`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <div className="mt-4 flex justify-end">
        <Button variant="outline" onClick={() => setOpen(true)}>{purchase.companyId ? "Tədarükçünü dəyiş" : "Tədarükçü təyin et"}</Button>
      </div>
    );
  }
  return (
    <div className="mt-4 rounded-2xl bg-slate-50 p-4 ring-1 ring-slate-200">
      <p className="mb-2 text-sm font-semibold text-navy-800">Düzgün tədarükçü</p>
      {error && <div className="mb-2"><Alert>{error}</Alert></div>}
      {!suppliers ? <Spinner /> : (
        <Select value={target} onChange={(e) => setTarget(e.target.value)}>
          <option value="">— Seçin —</option>
          {suppliers.filter((s) => s.id !== purchase.companyId).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </Select>
      )}
      <p className="mt-2 text-xs text-slate-500">Mal və anbar dəyişmir. Qaimənin ödənilməmiş borcu yeni tədarükçünün hesabına keçir; qaimə ilə edilən ödəniş köhnə tədarükçünün tarixçəsində qalır (onun balansına təsir etmir).</p>
      <div className="mt-3 flex justify-end gap-2">
        <Button variant="ghost" onClick={() => setOpen(false)}>Bağla</Button>
        <Button variant="gold" disabled={busy || !target} onClick={save}>Dəyiş</Button>
      </div>
      {confirmDialog}
    </div>
  );
}

/** Send some or all goods of a receipt back to the supplier (or cancel the whole receipt). */
function ReturnToSupplier({ purchase, onDone }: { purchase: Purchase; onDone: (p: Purchase, msg: string) => void }) {
  const left = purchase.items.map((i) => ({ ...i, left: Number(i.quantity) - Number(i.returnedQty ?? 0) })).filter((i) => i.left > 0);
  const [open, setOpen] = useState(false);
  const [qty, setQty] = useState<Record<string, string>>({});
  const [refund, setRefund] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (left.length === 0) return <p className="mt-4 text-sm font-medium text-rose-700">Bütün mallar təchizatçıya qaytarılıb.</p>;

  async function send(all: boolean) {
    setBusy(true);
    setError(null);
    try {
      const items = all ? [] : left.map((i) => ({ itemId: i.id, quantity: Number((qty[i.id] ?? "").replace(",", ".")) })).filter((i) => i.quantity > 0);
      if (!all && items.length === 0) { setError("Qaytarılan miqdarı yazın"); setBusy(false); return; }
      const p = await api<Purchase>(`/api/purchases/${purchase.id}/returns`, { method: "POST", json: { items, refundToCash: refund } });
      setOpen(false);
      onDone(p, all ? `${p.number} ləğv edildi: mallar təchizatçıya qaytarıldı` : `${p.number}: mallar təchizatçıya qaytarıldı`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <div className="mt-4 flex justify-end">
        <Button variant="outline" className="border-rose-300 text-rose-700" onClick={() => setOpen(true)}>Təchizatçıya qaytar / ləğv et</Button>
      </div>
    );
  }
  return (
    <div className="mt-4 rounded-2xl bg-rose-50/60 p-4 ring-1 ring-rose-200">
      <p className="mb-2 text-sm font-semibold text-navy-800">Təchizatçıya qaytarılan mallar</p>
      {error && <div className="mb-2"><Alert>{error}</Alert></div>}
      <div className="space-y-2">
        {left.map((i) => (
          <label key={i.id} className="flex items-center justify-between gap-3 text-sm">
            <span>{i.name} <span className="text-xs text-slate-500">(qalıb {i.left})</span></span>
            <Input className="h-8 w-24 text-right" inputMode="decimal" placeholder="0" value={qty[i.id] ?? ""} onChange={(e) => setQty({ ...qty, [i.id]: e.target.value })} />
          </label>
        ))}
      </div>
      {purchase.companyId && (
        <label className="mt-3 flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" className="h-4 w-4 accent-navy-800" checked={refund} onChange={(e) => setRefund(e.target.checked)} />
          Borcdan artıq qalan (əvvəl ödənmiş) pulu təchizatçı geri verdi — kassaya yaz
        </label>
      )}
      <p className="mt-2 text-xs text-slate-500">Mal anbardan çıxılır, dəyəri təchizatçıya borcunuzdan çıxılır.</p>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <Button variant="ghost" onClick={() => setOpen(false)}>Bağla</Button>
        <Button variant="outline" disabled={busy} onClick={() => send(false)}>Seçilənləri qaytar</Button>
        <Button variant="danger" disabled={busy} onClick={() => send(true)}>Hamısını qaytar (ləğv et)</Button>
      </div>
    </div>
  );
}

function PurchaseForm({ onClose, onSaved }: { onClose: () => void; onSaved: (p: Purchase) => void }) {
  const [products, setProducts] = useState<Product[]>([]);
  const [suppliers, setSuppliers] = useState<Party[]>([]);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [lines, setLines] = useState<Line[]>([]);
  const [query, setQuery] = useState("");
  const [companyId, setCompanyId] = useState("");
  const [invoice, setInvoice] = useState("");
  const [dueDate, setDueDate] = useState("");
  const [paid, setPaid] = useState("");
  const [method, setMethod] = useState("CASH");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(newId());
  const searchRef = useRef<HTMLInputElement>(null);
  const [creatingProduct, setCreatingProduct] = useState<string | null>(null);
  const [restored, setRestored] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  useEffect(() => {
    api<Product[]>("/api/products?active=true").then(setProducts).catch(() => setProducts([]));
    api<Party[]>("/api/companies").then(setSuppliers).catch(() => setSuppliers([]));
    api<ProductCategory[]>("/api/product-categories").then(setCategories).catch(() => setCategories([]));
    // a receipt left half done (window closed, connection lost) comes back
    const d = readDraft();
    if (d && d.lines?.length) {
      setCompanyId(d.companyId); setInvoice(d.invoice); setDueDate(d.dueDate); setPaid(d.paid); setMethod(d.method); setNote(d.note);
      setLines(d.lines.map((l) => ({ ...l, salePrice: l.salePrice ?? String(l.product.salePrice ?? "") })));
      setRestored(d.savedAt);
    }
  }, []);
  useEffect(() => {
    writeDraft(lines.length ? { companyId, invoice, dueDate, paid, method, note, lines, savedAt: new Date().toISOString() } : null);
  }, [companyId, invoice, dueDate, paid, method, note, lines]);

  function clearDraft() {
    setLines([]); setCompanyId(""); setInvoice(""); setDueDate(""); setPaid(""); setMethod("CASH"); setNote("");
    setRestored(null);
    writeDraft(null);
  }

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return products.filter((p) => p.name.toLowerCase().includes(q) || p.code.toLowerCase() === q || p.barcode === query.trim()).slice(0, 8);
  }, [query, products]);

  const total = round2(lines.reduce((s, l) => s + round2(num(l.quantity) * num(l.unitCost)), 0));

  function add(p: Product, qty = 1) {
    setLines((ls) => {
      const i = ls.findIndex((l) => l.product.id === p.id);
      if (i >= 0) return ls.map((l, k) => (k === i ? { ...l, quantity: String(num(l.quantity) + qty) } : l));
      return [...ls, { product: p, quantity: String(qty), unitCost: String(p.purchasePrice ?? 0), expiryDate: "", salePrice: String(p.salePrice ?? "") }];
    });
    setQuery("");
    searchRef.current?.focus();
  }

  function onSearchKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key !== "Enter") return;
    e.preventDefault();
    const q = query.trim();
    const exact = products.find((p) => p.barcode === q || p.code.toLowerCase() === q.toLowerCase());
    if (exact) add(exact);
    else if (matches.length === 1) add(matches[0]);
    else if (q && matches.length === 0) setCreatingProduct(q); // an unknown barcode: make the product right here
  }

  async function save() {
    setError(null);
    if (lines.length === 0) { setError("Ən azı bir məhsul əlavə edin"); return; }
    const onCredit = method === "DEBT";
    if (onCredit && !companyId) { setError("Borca almaq üçün təchizatçı seçin"); return; }
    const paidN = onCredit ? 0 : round2(num(paid));
    if (paidN > total) { setError("Ödənilən məbləğ alışdan çox ola bilməz"); return; }
    if (!companyId && paidN > 0 && paidN !== total) { setError("Borcla və ya qismən ödənişli alış üçün təchizatçı seçin"); return; }
    const supplier = suppliers.find((s) => s.id === companyId)?.name;
    const changed = lines.filter((l) => l.salePrice !== "" && round2(num(l.salePrice)) !== round2(l.product.salePrice));
    const ok = await confirm(
      <>
        <span className="block text-base font-semibold text-navy-800">{supplier ?? "Təchizatçısız"}{invoice ? ` · qaimə ${invoice}` : ""}</span>
        <span className="mt-1 block">{lines.length} məhsul · cəmi <b>{formatMoney(total)}</b></span>
        <span className="mt-1 block">{onCredit ? "Hamısı borca" : `İndi ödənilir: ${formatMoney(paidN)} (${METHOD_TEXT[method] ?? method})`}</span>
        {companyId && total - paidN > 0 && <span className="mt-1 block text-rose-700">{supplier} borcunuza yazılacaq: <b>{formatMoney(round2(total - paidN))}</b></span>}
        {changed.length > 0 && <span className="mt-1 block text-amber-700">{changed.length} məhsulun satış qiyməti dəyişəcək</span>}
        <span className="mt-2 block text-slate-500">Mallar anbara yüklənəcək. Təchizatçı düzgündür?</span>
      </>, { title: "Qaiməni yekunlaşdır", confirmLabel: "Bəli, anbara yüklə" });
    if (!ok) return;
    setBusy(true);
    try {
      const saved = await api<Purchase>("/api/purchases", {
        method: "POST",
        json: {
          companyId: companyId || null,
          items: lines.map((l) => ({
            productId: l.product.id, quantity: num(l.quantity), unitCost: round2(num(l.unitCost)), expiryDate: l.expiryDate || null,
            salePrice: l.salePrice !== "" && round2(num(l.salePrice)) !== round2(l.product.salePrice) ? round2(num(l.salePrice)) : null,
          })),
          paid: paidN, paymentMethod: onCredit ? "CASH" : method, supplierInvoice: invoice || null, dueDate: dueDate || null, note: note || null,
          clientRequestId: requestId.current,
        },
      });
      writeDraft(null);
      onSaved(saved);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title="Yeni mal qəbulu" onClose={onClose} size="xl">
      {restored && (
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <span>Yarımçıq qalmış qaimə bərpa edildi ({formatDateTime(restored)}).</span>
          <button type="button" className="font-semibold underline" onClick={clearDraft}>Təmizlə, yenidən başla</button>
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="Təchizatçı (şirkət)" hint="Seçilsə, ödənilməyən hissə “Bizim tədarükçüyə borcumuz”a yazılır">
          <Select value={companyId} onChange={(e) => setCompanyId(e.target.value)}>
            <option value="">— Təchizatçısız —</option>
            {suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </Select>
        </Field>
        <Field label="Qaimə nömrəsi"><Input value={invoice} maxLength={60} onChange={(e) => setInvoice(e.target.value)} placeholder="istəyə bağlı" /></Field>
        <Field label="Son ödəmə tarixi"><Input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} disabled={!companyId} /></Field>
      </div>

      <div className="relative mt-4 flex gap-2">
        <Input ref={searchRef} autoFocus value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={onSearchKey} className="flex-1"
          placeholder="Məhsul adı, kodu və ya barkodu (skaner ilə oxudun və Enter)" />
        <Button type="button" variant="outline" className="shrink-0" onClick={() => setCreatingProduct(query.trim())}>+ Yeni məhsul</Button>
        {query.trim() && matches.length === 0 && (
          <div className="absolute z-10 mt-1 w-full rounded-xl bg-white p-3 text-sm shadow-lg ring-1 ring-slate-200">
            “{query.trim()}” tapılmadı.{" "}
            <button type="button" className="font-semibold text-gold-600 hover:underline" onClick={() => setCreatingProduct(query.trim())}>+ Yeni məhsul kimi əlavə et</button>
          </div>
        )}
        {matches.length > 0 && (
          <ul className="absolute z-10 mt-1 max-h-64 w-full overflow-y-auto rounded-xl bg-white shadow-lg ring-1 ring-slate-200">
            {matches.map((p) => (
              <li key={p.id}>
                <button type="button" onClick={() => add(p)} className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-slate-50">
                  <span><b className="text-navy-800">{p.name}</b> <span className="text-xs text-slate-400">{p.code}</span></span>
                  <span className="text-xs text-slate-500">qalıq {Number(p.stockQty)} · alış {formatMoney(p.purchasePrice)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="mt-3">
        {lines.length === 0 ? <EmptyState title="Məhsul əlavə edin" /> : (
          <Table>
            <thead><tr><Th>Məhsul</Th><Th className="text-right">Miqdar</Th><Th className="text-right">Alış qiyməti</Th><Th className="text-right">Satış qiyməti</Th><Th>Son istifadə tarixi</Th><Th className="text-right">Cəmi</Th><Th /></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {lines.map((l, i) => (
                <tr key={l.product.id}>
                  <Td><b className="text-navy-800">{l.product.name}</b><div className="text-xs text-slate-400">indiki alış {formatMoney(l.product.purchasePrice)} · qalıq {Number(l.product.stockQty)}</div></Td>
                  <Td className="text-right"><Input className="w-24 text-right" inputMode="decimal" value={l.quantity}
                    onChange={(e) => setLines(lines.map((x, k) => (k === i ? { ...x, quantity: e.target.value } : x)))} /></Td>
                  <Td className="text-right"><Input className="w-28 text-right" inputMode="decimal" value={l.unitCost}
                    onChange={(e) => setLines(lines.map((x, k) => (k === i ? { ...x, unitCost: e.target.value } : x)))} /></Td>
                  <Td className="text-right">
                    <Input className="w-28 text-right" inputMode="decimal" value={l.salePrice} aria-label="Satış qiyməti"
                      onChange={(e) => setLines(lines.map((x, k) => (k === i ? { ...x, salePrice: e.target.value } : x)))} />
                    <div className="mt-0.5 text-xs">
                      {num(l.unitCost) > 0 && num(l.salePrice) > 0 && (
                        <span className={num(l.salePrice) <= num(l.unitCost) ? "font-semibold text-rose-600" : "text-slate-500"}>
                          {num(l.salePrice) <= num(l.unitCost) ? "zərər! " : ""}{Math.round(((num(l.salePrice) - num(l.unitCost)) / num(l.unitCost)) * 100)}%
                        </span>
                      )}
                      {l.salePrice !== "" && round2(num(l.salePrice)) !== round2(l.product.salePrice) && <span className="ml-1 text-amber-700">(əvvəl {formatMoney(l.product.salePrice)})</span>}
                    </div>
                  </Td>
                  <Td><Input type="date" className="w-40" value={l.expiryDate} aria-label="Son istifadə tarixi" title="Boş qala bilər"
                    onChange={(e) => setLines(lines.map((x, k) => (k === i ? { ...x, expiryDate: e.target.value } : x)))} /></Td>
                  <Td className="text-right font-semibold tabular-nums">{formatMoney(round2(num(l.quantity) * num(l.unitCost)))}</Td>
                  <Td><button type="button" className="text-xs text-rose-600 hover:underline" onClick={() => setLines(lines.filter((_, k) => k !== i))}>Sil</button></Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </div>

      <div className="mt-4 grid gap-3 rounded-2xl bg-slate-50 p-4 sm:grid-cols-3">
        <div>
          <p className="text-xs text-slate-500">Cəmi</p>
          <p className="font-serif text-2xl font-semibold text-navy-800">{formatMoney(total)}</p>
          {companyId && <p className="text-xs text-rose-600">Borca yazılacaq: {formatMoney(method === "DEBT" ? total : Math.max(0, round2(total - num(paid))))}</p>}
        </div>
        <Field label="İndi ödənilən" hint={method === "DEBT" ? "Borca alınır: indi heç nə ödənilmir" : undefined}>
          <div className="flex gap-2">
            <Input inputMode="decimal" value={method === "DEBT" ? "0" : paid} disabled={method === "DEBT"} onChange={(e) => setPaid(e.target.value)} placeholder="0" />
            <Button type="button" variant="outline" disabled={method === "DEBT"} onClick={() => setPaid(String(total))}>Hamısı</Button>
          </div>
        </Field>
        <Field label="Ödəniş üsulu" hint={method === "DEBT" && !companyId ? "Borc üçün təchizatçı seçin" : undefined}>
          <Select value={method} onChange={(e) => setMethod(e.target.value)}>
            <option value="CASH">Nağd</option>
            <option value="CARD">Kart</option>
            {companyId && <option value="TRANSFER">Bank köçürməsi</option>}
            <option value="DEBT">Borc (nisyə) — sonra ödənilir</option>
          </Select>
        </Field>
      </div>
      <div className="mt-3"><Field label="Qeyd"><Input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} /></Field></div>
      {error && <div className="mt-3"><Alert>{error}</Alert></div>}
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>Ləğv et</Button>
        <Button variant="gold" disabled={busy || lines.length === 0} onClick={save}>{busy ? "Saxlanılır…" : "Qaiməni yekunlaşdır"}</Button>
      </div>
      {lines.length > 0 && <p className="mt-2 text-right text-xs text-slate-500">Qaimə yekunlaşdırılana qədər anbara heç nə düşmür; yarımçıq qaimə bu brauzerdə yadda qalır.</p>}
      {confirmDialog}
      {creatingProduct !== null && (
        <ProductModal product={null} forReceipt categories={categories} suppliers={suppliers}
          initial={/^[0-9A-Za-z-]{4,64}$/.test(creatingProduct) && /\d/.test(creatingProduct)
            ? { barcode: creatingProduct, supplierId: companyId || undefined }
            : { name: creatingProduct, supplierId: companyId || undefined }}
          onCategory={(c) => setCategories((x) => [...x, c])}
          onClose={() => { setCreatingProduct(null); searchRef.current?.focus(); }}
          onSaved={(_m, p, qty) => { setProducts((ps) => [...ps, p]); setCreatingProduct(null); add(p, qty > 0 ? qty : 1); }} />
      )}
    </Modal>
  );
}

const METHOD_TEXT: Record<string, string> = { CASH: "nağd", CARD: "kartla", TRANSFER: "bank köçürməsi", DEBT: "borc" };

