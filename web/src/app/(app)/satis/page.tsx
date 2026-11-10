"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, api, openProtected } from "@/lib/api";
import { formatDate, formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import type { Party, Product, Sale, SalePage } from "@/lib/types";

type CartLine = { product: Product; quantity: string; price: string; discount: string };
const r2 = (n: number) => Math.round(n * 100) / 100;
const num = (s: string) => (s.trim() === "" ? 0 : Number(s.replace(",", ".")));

/** Satış: new sale (search / barcode, cart, discount, cash / card / credit) and the sales list with receipt and returns. */
export default function SalesPage() {
  const [tab, setTab] = useState<"new" | "list">("new");
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <>
      <PageHeader title="Satış" subtitle="Satış anbar qalığını azaldır, ödənən pul kassaya düşür, ödənilməyən hissə müştərinin borcuna yazılır" />
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      <div className="mb-5 inline-flex rounded-xl bg-white p-1 shadow-sm ring-1 ring-slate-200" role="tablist">
        {([["new", "Yeni satış"], ["list", "Satışlar"]] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)} className={`rounded-lg px-4 py-2 text-sm font-medium ${tab === k ? "bg-navy-800 text-white shadow" : "text-slate-600"}`}>{l}</button>
        ))}
      </div>
      {tab === "new" ? <NewSale onDone={(s) => { setNotice(`Satış ${s.number} tamamlandı: ${formatMoney(s.total)}${s.creditAmount > 0 ? ` (borca ${formatMoney(s.creditAmount)})` : ""}`); openProtected(`/api/sales/${s.id}/receipt`).catch(() => undefined); }} /> : <SalesList />}
    </>
  );
}

function NewSale({ onDone }: { onDone: (s: Sale) => void }) {
  const [search, setSearch] = useState("");
  const [results, setResults] = useState<Product[]>([]);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [customers, setCustomers] = useState<Party[]>([]);
  const [customerId, setCustomerId] = useState("");
  const [discount, setDiscount] = useState("");
  const [cash, setCash] = useState("");
  const [card, setCard] = useState("");
  const [note, setNote] = useState("");
  const [requestId, setRequestId] = useState(() => crypto.randomUUID());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => { api<Party[]>("/api/customers").then(setCustomers).catch(() => setCustomers([])); }, []);
  useEffect(() => {
    if (!search.trim()) { setResults([]); return; }
    const t = setTimeout(() => {
      api<Product[]>(`/api/products?active=true&q=${encodeURIComponent(search.trim())}`).then((r) => setResults(r.slice(0, 8))).catch(() => setResults([]));
    }, 200);
    return () => clearTimeout(t);
  }, [search]);

  const add = (p: Product) => {
    setCart((c) => {
      const i = c.findIndex((l) => l.product.id === p.id);
      if (i >= 0) return c.map((l, j) => (j === i ? { ...l, quantity: String(num(l.quantity) + 1) } : l));
      return [...c, { product: p, quantity: "1", price: String(p.salePrice), discount: "" }];
    });
    setSearch("");
    setResults([]);
    searchRef.current?.focus();
  };

  // Enter in the search box: an exact barcode / code adds the product at once (scanner friendly)
  async function onSearchEnter(e: FormEvent) {
    e.preventDefault();
    if (!search.trim()) return;
    try {
      add(await api<Product>(`/api/products/by-code/${encodeURIComponent(search.trim())}`));
    } catch {
      if (results.length === 1) add(results[0]);
    }
  }

  const lines = cart.map((l) => ({ ...l, total: r2(Math.max(0, num(l.quantity) * num(l.price) - num(l.discount))) }));
  const subtotal = r2(lines.reduce((s, l) => s + l.total, 0));
  const total = r2(Math.max(0, subtotal - num(discount)));
  const paid = r2(num(cash) + num(card));
  const credit = r2(total - paid);
  const over = paid > total + 1e-9;
  const stockProblem = lines.find((l) => num(l.quantity) > l.product.stockQty);
  const customer = useMemo(() => customers.find((c) => c.id === customerId), [customers, customerId]);

  async function submit() {
    if (busy || !cart.length) return;
    setBusy(true);
    setError(null);
    try {
      const s = await api<Sale>("/api/sales", {
        method: "POST",
        json: {
          items: lines.map((l) => ({ productId: l.product.id, quantity: l.quantity, unitPrice: l.price, discount: l.discount || null })),
          customerId: customerId || null, discount: discount || null, paidCash: cash || "0", paidCard: card || "0",
          note: note || null, clientRequestId: requestId,
        },
      });
      setCart([]); setDiscount(""); setCash(""); setCard(""); setNote(""); setCustomerId(""); setRequestId(crypto.randomUUID());
      onDone(s);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Satış alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-5 xl:grid-cols-3">
      <Card className="xl:col-span-2">
        <form onSubmit={onSearchEnter} className="relative mb-4">
          <Input ref={searchRef} autoFocus placeholder="Məhsul adı, kod və ya barkod (skanerlə oxudun və Enter)" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Məhsul axtar" />
          {results.length > 0 && (
            <ul className="absolute z-10 mt-1 w-full overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lg">
              {results.map((p) => (
                <li key={p.id}>
                  <button type="button" onClick={() => add(p)} className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-slate-50">
                    <span><span className="font-medium text-navy-800">{p.name}</span> <span className="text-xs text-slate-400">{p.code}</span></span>
                    <span className="tabular-nums">{formatMoney(p.salePrice)} · <span className={p.stockQty <= 0 ? "text-rose-600" : "text-slate-500"}>qalıq {p.stockQty}</span></span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </form>
        {cart.length === 0 ? <EmptyState title="Səbət boşdur" text="Yuxarıdan məhsul axtarın və ya barkodu oxudun." /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Məhsul</Th><Th>Miqdar</Th><Th>Qiymət</Th><Th>Endirim</Th><Th className="text-right">Cəmi</Th><Th /></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {lines.map((l, i) => (
                <tr key={l.product.id}>
                  <Td><span className="font-medium text-navy-800">{l.product.name}</span>{num(l.quantity) > l.product.stockQty && <div className="text-xs text-rose-600">anbarda {l.product.stockQty}</div>}</Td>
                  <Td><Input type="number" min="0.001" step="0.001" className="w-24" value={l.quantity} onChange={(e) => setCart(cart.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} aria-label="Miqdar" /></Td>
                  <Td><Input type="number" min="0" step="0.01" className="w-28" value={l.price} onChange={(e) => setCart(cart.map((x, j) => (j === i ? { ...x, price: e.target.value } : x)))} aria-label="Qiymət" /></Td>
                  <Td><Input type="number" min="0" step="0.01" className="w-24" value={l.discount} onChange={(e) => setCart(cart.map((x, j) => (j === i ? { ...x, discount: e.target.value } : x)))} aria-label="Endirim" /></Td>
                  <Td className="text-right font-semibold tabular-nums">{formatMoney(l.total)}</Td>
                  <Td><button type="button" className="text-slate-400 hover:text-rose-600" onClick={() => setCart(cart.filter((_, j) => j !== i))} aria-label="Sil">✕</button></Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      <Card>
        <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Ödəniş</h2>
        {error && <div className="mb-3"><Alert>{error}</Alert></div>}
        <div className="space-y-3">
          <Field label="Müştəri" hint="Borcla / qismən satış üçün mütləqdir">
            <Select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
              <option value="">— müştərisiz (nağd satış)</option>
              {customers.map((c) => <option key={c.id} value={c.id}>{c.name}{c.code ? ` · ${c.code}` : ""}{c.openBalance > 0 ? ` · borc ${formatMoney(c.openBalance)}` : ""}</option>)}
            </Select>
          </Field>
          <div className="flex justify-between text-sm"><span>Cəmi</span><span className="tabular-nums">{formatMoney(subtotal)}</span></div>
          <Field label="Ümumi endirim (₼)"><Input type="number" min="0" step="0.01" value={discount} onChange={(e) => setDiscount(e.target.value)} /></Field>
          <div className="flex justify-between rounded-xl bg-navy-800 px-4 py-3 text-white"><span>Yekun</span><span className="font-serif text-2xl font-semibold tabular-nums text-gold-400">{formatMoney(total)}</span></div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Nağd (₼)"><Input type="number" min="0" step="0.01" value={cash} onChange={(e) => setCash(e.target.value)} /></Field>
            <Field label="Kart (₼)"><Input type="number" min="0" step="0.01" value={card} onChange={(e) => setCard(e.target.value)} /></Field>
          </div>
          <div className="flex flex-wrap gap-2 text-xs">
            <button type="button" className="rounded-full bg-slate-100 px-3 py-1.5" onClick={() => { setCash(total.toFixed(2)); setCard(""); }}>Hamısı nağd</button>
            <button type="button" className="rounded-full bg-slate-100 px-3 py-1.5" onClick={() => { setCard(total.toFixed(2)); setCash(""); }}>Hamısı kart</button>
            <button type="button" className="rounded-full bg-slate-100 px-3 py-1.5" onClick={() => { setCash(""); setCard(""); }}>Tam borca</button>
          </div>
          {credit > 0 && !over && <p className={`rounded-xl px-3 py-2 text-sm ${customer ? "bg-amber-50 text-amber-800" : "bg-rose-50 text-rose-700"}`}>{customer ? `${formatMoney(credit)} ${customer.name} adlı müştərinin borcuna yazılacaq` : `Ödənilməyən ${formatMoney(credit)} üçün müştəri seçin`}</p>}
          {over && <p className="rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-700">Ödənilən məbləğ yekundan çoxdur ({formatMoney(paid - total)} qaytarılmalı qalıq) — yekun qədər yazın.</p>}
          <Field label="Qeyd"><Input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
          <Button variant="gold" className="w-full py-3" disabled={busy || !cart.length || over || (credit > 0 && !customerId) || !!stockProblem} onClick={submit}>
            {busy ? "Saxlanılır…" : `Satışı tamamla — ${formatMoney(total)}`}
          </Button>
          {stockProblem && <p className="text-xs text-rose-600">“{stockProblem.product.name}” anbarda kifayət deyil.</p>}
        </div>
      </Card>
    </div>
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
        {active && <Button variant="danger" disabled={busy} onClick={cancel}>Satışı ləğv et</Button>}
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
