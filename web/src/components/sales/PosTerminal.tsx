"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Alert, Button, Modal, cx } from "@/components/ui";
import { API_URL, ApiError, api, getToken, openProtected } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { desktop, isNetworkError, receiptHtml } from "@/lib/desktop";
import { formatMoney } from "@/lib/format";
import { waLink } from "@/lib/whatsapp";
import type { Party, Product, Sale } from "@/lib/types";

const PRODUCTS_CACHE = "pos_cache_products";
const CUSTOMERS_CACHE = "pos_cache_customers";

/** Remembers the last good list so the desktop till keeps working without internet. */
function cached<T>(key: string, fresh?: T): T | null {
  try {
    if (fresh !== undefined) {
      window.localStorage.setItem(key, JSON.stringify(fresh));
      return fresh;
    }
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return fresh ?? null;
  }
}

type Line = { product: Product; quantity: string; price: string; discount: string };
type PayMode = "cash" | "card" | "mixed" | "credit";

const r2 = (n: number) => Math.round(n * 100) / 100;
const num = (s: string) => (s.trim() === "" ? 0 : Number(s.replace(",", ".")) || 0);
const qtyText = (n: number) => (Number.isInteger(n) ? String(n) : n.toFixed(3).replace(/0+$/, ""));

/** Banknotes for "müştəri verdi": exact amount plus the next notes that cover the total. */
function quickCash(total: number): number[] {
  if (total <= 0) return [];
  const notes = [1, 5, 10, 20, 50, 100, 200, 500];
  const out = new Set<number>([r2(total)]);
  for (const n of notes) {
    const v = Math.ceil(total / n) * n;
    if (v > total) out.add(v);
    if (out.size >= 5) break;
  }
  return [...out].sort((a, b) => a - b);
}

/* ---------- product picture (protected endpoint, cached object URLs) ---------- */
const imageCache = new Map<string, Promise<string | null>>();
function loadImage(id: string, version: number): Promise<string | null> {
  const key = `${id}:${version}`;
  let p = imageCache.get(key);
  if (!p) {
    const headers = new Headers();
    const token = getToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
    p = fetch(`${API_URL}/api/products/${id}/image`, { headers })
      .then(async (r) => (r.ok ? URL.createObjectURL(await r.blob()) : null))
      .catch(() => null);
    imageCache.set(key, p);
  }
  return p;
}
function Thumb({ product, className }: { product: Product; className?: string }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let alive = true;
    if (product.hasImage) loadImage(product.id, product.version).then((u) => alive && setSrc(u));
    return () => { alive = false; };
  }, [product.id, product.version, product.hasImage]);
  // words that start with a letter: "Su 1.5L" -> "S", "Kofe Jacobs" -> "KJ"
  const initials = product.name.trim().split(/\s+/).map((w) => w.match(/^\p{L}/u)?.[0] ?? "").filter(Boolean).slice(0, 2).join("").toLocaleUpperCase("az");
  return src ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt="" className={cx("object-cover", className)} />
  ) : (
    <div className={cx("flex items-center justify-center bg-gradient-to-br from-navy-50 to-slate-100 font-serif font-semibold text-navy-600", className)}>{initials || "•"}</div>
  );
}

/** Sends the 80 mm receipt PDF straight to the printer dialog (hidden frame); falls back to opening it. */
async function printReceipt(saleId: string) {
  try {
    const headers = new Headers();
    const token = getToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const res = await fetch(`${API_URL}/api/sales/${saleId}/receipt`, { headers });
    if (!res.ok) throw new Error(String(res.status));
    const url = URL.createObjectURL(await res.blob());
    const frame = document.createElement("iframe");
    frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden";
    frame.src = url;
    frame.onload = () => setTimeout(() => {
      try { frame.contentWindow?.focus(); frame.contentWindow?.print(); } catch { void openProtected(`/api/sales/${saleId}/receipt`); }
      // the print dialog keeps focus in the frame: give it back so Space starts the next sale
      window.focus();
      document.querySelector<HTMLElement>("[data-pos-next]")?.focus();
    }, 300);
    document.body.appendChild(frame);
    setTimeout(() => { frame.remove(); URL.revokeObjectURL(url); }, 120_000);
  } catch {
    void openProtected(`/api/sales/${saleId}/receipt`).catch(() => undefined);
  }
}

const AUTO_PRINT_KEY = "pos_auto_print";

function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded border border-slate-300 bg-white px-1.5 py-0.5 font-sans text-[10px] font-semibold text-slate-500 shadow-sm">{children}</kbd>;
}

/**
 * Point-of-sale terminal: product catalogue (search, barcode, categories) on the left,
 * the receipt with customer, discount and payment (cash given → change, card, mixed, credit) on the right.
 */
export function PosTerminal({ onSold, fill = false }: { onSold?: () => void; /** fill the parent's height (Windows kassa) */ fill?: boolean }) {
  const [products, setProducts] = useState<Product[] | null>(null);
  const [customers, setCustomers] = useState<Party[]>([]);
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("");
  const [cart, setCart] = useState<Line[]>([]);
  const [editing, setEditing] = useState<string | null>(null);
  const [customer, setCustomer] = useState<Party | null>(null);
  const [customerQuery, setCustomerQuery] = useState("");
  const [customerOpen, setCustomerOpen] = useState(false);
  const [discount, setDiscount] = useState("");
  const [bonusInput, setBonusInput] = useState("");
  // Top 2 (pərakəndə) is the till's normal price; the cashier switches to Top 1 (topdan) for a wholesale sale
  const [level, setLevel] = useState<"RETAIL" | "WHOLESALE">("RETAIL");
  const priceOf = useCallback((p: Product, lv: "RETAIL" | "WHOLESALE") =>
    (lv === "WHOLESALE" && p.wholesalePrice != null ? p.wholesalePrice : p.salePrice), []);
  const [percent, setPercent] = useState(false);
  const [mode, setMode] = useState<PayMode>("cash");
  const [given, setGiven] = useState("");
  const [cash, setCash] = useState("");
  const [card, setCard] = useState("");
  const [note, setNote] = useState("");
  const [showNote, setShowNote] = useState(false);
  const [requestId, setRequestId] = useState(() => crypto.randomUUID());
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<{ sale: Sale; change: number; offline?: boolean } | null>(null);
  const [payOpen, setPayOpen] = useState(false);
  const [autoPrint, setAutoPrint] = useState(true);
  useEffect(() => {
    try { setAutoPrint(window.localStorage.getItem(AUTO_PRINT_KEY) !== "false"); } catch { /* storage blocked */ }
  }, []);
  const toggleAutoPrint = (v: boolean) => {
    setAutoPrint(v);
    try { window.localStorage.setItem(AUTO_PRINT_KEY, String(v)); } catch { /* storage blocked */ }
  };
  const searchRef = useRef<HTMLInputElement>(null);
  const customerRef = useRef<HTMLInputElement>(null);
  const discountRef = useRef<HTMLInputElement>(null);
  const [showKeys, setShowKeys] = useState(false);
  // Windows kassa extras (only when the app supports them)
  const [autoStart, setAutoStart] = useState<boolean | null>(null);
  const [drawerAuto, setDrawerAuto] = useState(false);
  useEffect(() => {
    const d = desktop();
    d?.getAutoStart?.().then(setAutoStart).catch(() => undefined);
    d?.getDrawerAuto?.().then(setDrawerAuto).catch(() => undefined);
  }, []);

  const { me, isOwner } = useAuth();
  const app = desktop();
  const [pending, setPending] = useState(0);
  const [printers, setPrinters] = useState<{ name: string; isDefault: boolean }[]>([]);
  const [printer, setPrinterState] = useState<string | null>(null);
  useEffect(() => {
    if (!app) return;
    app.pending().then((p) => setPending(p.count)).catch(() => undefined);
    app.getPrinters().then(setPrinters).catch(() => undefined);
    app.getPrinter().then(setPrinterState).catch(() => undefined);
    return app.onPendingChange(setPending);
  }, [app]);

  const loadProducts = useCallback(() => {
    api<Product[]>("/api/products?active=true")
      .then((l) => setProducts(cached(PRODUCTS_CACHE, l)))
      .catch(() => setProducts(cached<Product[]>(PRODUCTS_CACHE) ?? []));
  }, []);
  useEffect(() => {
    loadProducts();
    api<Party[]>("/api/customers")
      .then((l) => setCustomers(cached(CUSTOMERS_CACHE, l) ?? []))
      .catch(() => setCustomers(cached<Party[]>(CUSTOMERS_CACHE) ?? []));
  }, [loadProducts]);

  /** Desktop app: silent print on the chosen printer; browser: the PDF receipt with the print dialog. */
  const printSale = useCallback((sale: Sale, change = 0, offline = false) => {
    const d = desktop();
    if (d) {
      void d.printHtml(receiptHtml(sale, { name: me?.storeName ?? null }, { change, offline }));
      return;
    }
    if (!offline) void printReceipt(sale.id);
  }, [me?.storeName]);

  /** Sends the customer a link to this sale's receipt (opens without signing in) over WhatsApp. */
  async function shareReceipt(sale: Sale) {
    try {
      const { url } = await api<{ url: string }>(`/api/sales/${sale.id}/share-link`);
      const phone = customers.find((c) => c.id === sale.customerId)?.phone ?? null;
      const text = `${me?.storeName ?? "Mağaza"} — çekiniz ${sale.number}: ${formatMoney(sale.total)}
${url}
Təşəkkür edirik!`;
      window.open(waLink(phone, text) ?? `https://wa.me/?text=${encodeURIComponent(text)}`, "_blank", "noopener");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Çek linki alınmadı");
    }
  }

  const categories = useMemo(() => {
    const m = new Map<string, string>();
    (products ?? []).forEach((p) => p.categoryId && p.categoryName && m.set(p.categoryId, p.categoryName));
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1], "az"));
  }, [products]);

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (products ?? [])
      .filter((p) => !category || p.categoryId === category)
      .filter((p) => !q || p.name.toLowerCase().includes(q) || p.code.toLowerCase().includes(q) || (p.barcode ?? "").toLowerCase().includes(q))
      // in stock first, then by name; finished goods go to the end
      .sort((a, b) => Number(a.stockQty <= 0) - Number(b.stockQty <= 0) || a.name.localeCompare(b.name, "az"))
      .slice(0, 60);
  }, [products, search, category]);

  const inCart = (id: string) => cart.find((l) => l.product.id === id);

  const add = useCallback((p: Product, qty = 1) => {
    setCart((c) => {
      const i = c.findIndex((l) => l.product.id === p.id);
      if (i >= 0) return c.map((l, j) => (j === i ? { ...l, quantity: qtyText(num(l.quantity) + qty) } : l));
      return [...c, { product: p, quantity: qtyText(qty), price: String(priceOf(p, level)), discount: "" }];
    });
    setFlash(p.id);
    setTimeout(() => setFlash((f) => (f === p.id ? null : f)), 600);
    setError(null);
  }, [level, priceOf]);

  // Onlayn sifariş → kassa: /satis?order=<id> puts the order's goods into the receipt; the sale closes the order
  const orderRef = useRef<{ id: string; number: string } | null>(null);
  useEffect(() => {
    if (!products || orderRef.current) return;
    const id = new URLSearchParams(window.location.search).get("order");
    if (!id) return;
    type OnlineOrder = { id: string; number: string; customerName: string; customerPhone: string; items: { productId: string | null; quantity: number }[] };
    api<OnlineOrder[]>("/api/online-orders").then((list) => {
      const o = list.find((x) => x.id === id);
      if (!o) return;
      orderRef.current = { id: o.id, number: o.number };
      o.items.forEach((it) => {
        const p = products.find((pp) => pp.id === it.productId);
        if (p) add(p, Number(it.quantity));
      });
      setNote(`Onlayn sifariş ${o.number} · ${o.customerName} ${o.customerPhone}`);
      window.history.replaceState(null, "", "/satis");
    }).catch(() => undefined);
  }, [products, add]);

  /** Switching Top 1 / Top 2 re-prices everything already in the receipt. */
  function switchLevel(lv: "RETAIL" | "WHOLESALE") {
    setLevel(lv);
    setCart((c) => c.map((l) => ({ ...l, price: String(priceOf(l.product, lv)) })));
  }

  const setQty = (id: string, q: number) =>
    setCart((c) => (q <= 0 ? c.filter((l) => l.product.id !== id) : c.map((l) => (l.product.id === id ? { ...l, quantity: qtyText(q) } : l))));
  const patch = (id: string, v: Partial<Line>) => setCart((c) => c.map((l) => (l.product.id === id ? { ...l, ...v } : l)));

  // Enter: exact barcode / code (scanner) adds at once; otherwise the only match
  async function onSearchEnter(e: FormEvent) {
    e.preventDefault();
    const q = search.trim();
    if (!q) return;
    const exact = (products ?? []).find((p) => p.barcode === q || p.code.toLowerCase() === q.toLowerCase());
    if (exact) { add(exact); setSearch(""); return; }
    // scale label (tərəzi barkodu): 2X PPPPP WWWWW C — product code (PLU) and weight in grams
    const weighed = /^2\d(\d{5})(\d{5})\d$/.exec(q);
    if (weighed) {
      const plu = weighed[1];
      const p = (products ?? []).find((x) => x.code === plu || x.barcode === plu || x.code.replace(/\D/g, "").replace(/^0+/, "") === plu.replace(/^0+/, ""));
      const kg = Number(weighed[2]) / 1000;
      if (p && kg > 0) { add(p, kg); setSearch(""); return; }
    }
    try {
      add(await api<Product>(`/api/products/by-code/${encodeURIComponent(q)}`));
      setSearch("");
    } catch {
      if (visible.length === 1) { add(visible[0]); setSearch(""); }
      else setError(`“${q}” tapılmadı`);
    }
  }

  /* ---------- totals ---------- */
  const lines = cart.map((l) => ({ ...l, total: r2(Math.max(0, num(l.quantity) * num(l.price) - num(l.discount))) }));
  const subtotal = r2(lines.reduce((s, l) => s + l.total, 0));
  const discountAmount = r2(Math.min(subtotal, percent ? (subtotal * Math.min(100, num(discount))) / 100 : num(discount)));
  const bonusAvailable = customer?.bonusBalance ?? 0;
  const bonusAmount = r2(Math.max(0, Math.min(num(bonusInput), bonusAvailable, subtotal - discountAmount)));
  const total = r2(Math.max(0, subtotal - discountAmount - bonusAmount));
  const itemCount = lines.reduce((s, l) => s + num(l.quantity), 0);

  // Müştəri ekranı: a second window (second monitor) shows the customer what is being rung up
  useEffect(() => {
    if (typeof BroadcastChannel === "undefined") return;
    const ch = new BroadcastChannel("hesabpro-customer-display");
    ch.postMessage(done
      ? { kind: "done", store: me?.storeName ?? "", total: done.sale.total, change: done.change }
      : {
          kind: "cart", store: me?.storeName ?? "", subtotal, discount: r2(discountAmount + bonusAmount), total,
          lines: lines.map((l) => ({ name: l.product.name, quantity: num(l.quantity), price: num(l.price), total: l.total })),
        });
    ch.close();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cart, discountAmount, bonusAmount, total, done]);

  let paidCash = 0, paidCard = 0, change = 0;
  if (mode === "cash") {
    const g = given.trim() === "" ? total : num(given);
    paidCash = Math.min(g, total);
    change = r2(Math.max(0, g - total));
  } else if (mode === "card") {
    paidCard = total;
  } else if (mode === "mixed") {
    paidCash = num(cash);
    paidCard = num(card);
  } else {
    paidCash = num(cash); // optional advance
  }
  paidCash = r2(paidCash);
  paidCard = r2(paidCard);
  const credit = r2(total - paidCash - paidCard);
  const over = credit < -0.004;
  const stockProblem = lines.find((l) => num(l.quantity) > l.product.stockQty);
  const needsCustomer = credit > 0.004 && !customer;
  const canSell = !busy && cart.length > 0 && !over && !needsCustomer && !stockProblem && total >= 0;

  const reset = () => {
    setCart([]); setDiscount(""); setBonusInput(""); setLevel("RETAIL"); setPercent(false); setGiven(""); setCash(""); setCard(""); setNote(""); setShowNote(false);
    setCustomer(null); setCustomerQuery(""); setMode("cash"); setEditing(null); setError(null); setRequestId(crypto.randomUUID());
  };

  async function submit(allowOverLimit = false) {
    if (!canSell) return;
    setBusy(true);
    setError(null);
    const payload = {
          items: lines.map((l) => ({ productId: l.product.id, quantity: l.quantity, unitPrice: l.price, discount: l.discount || null })),
          customerId: customer?.id ?? null,
          discount: discountAmount > 0 ? discountAmount.toFixed(2) : null,
          bonusUsed: bonusAmount > 0 ? bonusAmount.toFixed(2) : null,
          priceLevel: level,
          paidCash: paidCash.toFixed(2),
          paidCard: paidCard.toFixed(2),
          note: note || null,
          clientRequestId: requestId,
          allowOverLimit: allowOverLimit || null,
    };
    try {
      let s: Sale;
      try {
        s = await api<Sale>("/api/sales", { method: "POST", json: payload });
      } catch (e) {
        const d = desktop();
        const token = getToken();
        if (!d || !token || !isNetworkError(e)) throw e;
        // no internet: the desktop app keeps the sale and sends it later (same request id = recorded once)
        await d.queueSale({ apiUrl: API_URL, token, payload, total });
        s = {
          id: requestId, number: "Oflayn", customerId: customer?.id ?? null, customerName: customer?.name ?? null, customerCode: null,
          status: "COMPLETED", subtotal, discount: r2(discountAmount + bonusAmount), total, paidCash, paidCard, creditAmount: credit,
          returnedAmount: 0, costTotal: 0, debtId: null, soldOn: "", note: note || null, createdByName: me?.fullName ?? "",
          createdAt: new Date().toISOString(), returns: [], createdBy: null, priceLevel: level,
          items: lines.map((l) => ({ id: l.product.id, productId: l.product.id, name: l.product.name, quantity: num(l.quantity),
            unitPrice: num(l.price), discount: num(l.discount), lineTotal: l.total, unitCost: 0, returnedQty: 0 })),
        };
        // the stock on this computer goes down too, so the next offline sale sees it
        const left = (cached<Product[]>(PRODUCTS_CACHE) ?? []).map((p) => {
          const l = lines.find((x) => x.product.id === p.id);
          return l ? { ...p, stockQty: p.stockQty - num(l.quantity) } : p;
        });
        cached(PRODUCTS_CACHE, left);
        setDone({ sale: s, change, offline: true });
        setPayOpen(false);
        if (autoPrint) printSale(s, change, true);
        reset();
        setProducts(left);
        return;
      }
      setDone({ sale: s, change });
      // the cash drawer opens by itself after a sale with cash (Windows app, when switched on)
      if (drawerAuto && s.paidCash > 0) void desktop()?.openDrawer?.();
      if (orderRef.current) {
        api(`/api/online-orders/${orderRef.current.id}/status`, { method: "POST", json: { status: "DONE", saleId: s.id } }).catch(() => undefined);
        orderRef.current = null;
      }
      api<Party[]>("/api/customers").then(setCustomers).catch(() => undefined); // fresh bonus balances
      setPayOpen(false);
      if (autoPrint) printSale(s, change);
      reset();
      loadProducts();
      onSold?.();
    } catch (e) {
      // over the customer's borc limiti: the owner may let it through, others see why it stopped
      if (e instanceof ApiError && e.code === "CREDIT_LIMIT" && isOwner && !allowOverLimit
        && window.confirm(`${e.message}.

Limiti keçərək yenə də nisyə satılsın?`)) {
        setBusy(false);
        return submit(true);
      }
      setError(e instanceof ApiError ? e.message : "Satış alınmadı");
    } finally {
      setBusy(false);
    }
  }

  // keyboard: Boşluq (space) = ödəniş / sat, F1 help, F2 search, F4 card, F6 discount, F7 customer, F8 clear,
  // Esc clears the search. F9 still works as before.
  const submitRef = useRef(submit);
  submitRef.current = submit;
  const resetRef = useRef(reset);
  resetRef.current = reset;
  const payRef = useRef({ payOpen, hasCart: cart.length > 0, done: !!done, search });
  payRef.current = { payOpen, hasCart: cart.length > 0, done: !!done, search };
  useEffect(() => {
    const openPay = (m?: PayMode) => {
      const st = payRef.current;
      if (!st.hasCart || st.done) return;
      if (m) { setMode(m); setCash(""); setCard(""); setGiven(""); }
      setError(null);
      setPayOpen(true);
    };
    const onKey = (e: KeyboardEvent) => {
      const st = payRef.current;
      if (st.done && (e.key === " " || e.key === "Enter") && !(e.target instanceof HTMLButtonElement)) {
        e.preventDefault();
        setDone(null);
        setTimeout(() => searchRef.current?.focus(), 0);
        return;
      }
      const t = e.target as HTMLElement | null;
      const typing = !!t && (t.tagName === "TEXTAREA" || t.tagName === "SELECT" || (t.tagName === "INPUT" && t !== searchRef.current));
      // Boşluq: open the payment (from the empty search box or anywhere outside a text field); in the payment
      // window Boşluq completes the sale (handled there)
      if (e.key === " " && !st.payOpen && !typing && !(t instanceof HTMLButtonElement)
          && (t !== searchRef.current || st.search.trim() === "")) {
        if (st.hasCart) { e.preventDefault(); openPay(); }
        return;
      }
      if (e.key === "F1") { e.preventDefault(); setShowKeys((v) => !v); }
      else if (e.key === "F2") { e.preventDefault(); searchRef.current?.focus(); searchRef.current?.select(); }
      else if (e.key === "F4") { e.preventDefault(); if (!st.payOpen) openPay("card"); }
      else if (e.key === "F6") { e.preventDefault(); discountRef.current?.focus(); discountRef.current?.select(); }
      else if (e.key === "F7") { e.preventDefault(); customerRef.current?.focus(); }
      else if (e.key === "F8") {
        e.preventDefault();
        if (st.hasCart && !st.payOpen && window.confirm("Çek təmizlənsin? Səbətdəki bütün mallar silinəcək.")) resetRef.current();
      }
      else if (e.key === "F9") {
        e.preventDefault();
        if (st.payOpen) void submitRef.current();
        else openPay();
      }
      else if (e.key === "Escape" && document.activeElement === searchRef.current) setSearch("");
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  /** Choosing a customer applies their standing discount (%) to the whole receipt. */
  function pickCustomer(c: Party | null) {
    setCustomer(c);
    setBonusInput("");
    if (c?.discountPercent && c.discountPercent > 0) {
      setPercent(true);
      setDiscount(String(c.discountPercent));
    }
  }

  const customerMatches = useMemo(() => {
    const q = customerQuery.trim().toLowerCase();
    return customers
      .filter((c) => !q || c.name.toLowerCase().includes(q) || (c.phone ?? "").replace(/\s/g, "").includes(q.replace(/\s/g, "")) || (c.code ?? "").toLowerCase().includes(q))
      .slice(0, 8);
  }, [customers, customerQuery]);

  return (
    <div className={fill ? "grid h-full min-h-0 gap-3 lg:grid-cols-[minmax(0,1fr)_440px] lg:grid-rows-[minmax(0,1fr)]" : "grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_400px]"}>
      {/* ---------------- catalogue ---------------- */}
      <section className={cx("min-w-0 rounded-2xl bg-white p-4 shadow-card ring-1 ring-slate-200/70 sm:p-5", fill && "flex min-h-0 flex-col")}>
        <form onSubmit={onSearchEnter} className="relative">
          <svg viewBox="0 0 24 24" className="pointer-events-none absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-slate-400" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M3 5v14M7 5v14M11 5v14M14 5v14M18 5v14M21 5v14" /></svg>
          <input
            ref={searchRef}
            autoFocus
            value={search}
            onChange={(e) => { setSearch(e.target.value); setError(null); }}
            placeholder="Məhsul adı, kod və ya barkod — skanerlə oxudun və Enter"
            aria-label="Məhsul axtar"
            className="w-full rounded-2xl border border-slate-200 bg-slate-50 py-3.5 pl-12 pr-20 text-base text-navy-800 outline-none transition focus:border-gold-500 focus:bg-white focus:shadow-glow"
          />
          <span className="absolute right-4 top-1/2 -translate-y-1/2"><Kbd>F2</Kbd></span>
        </form>
        <button type="button" onClick={() => setShowKeys((v) => !v)} className="mt-2 text-xs font-medium text-slate-400 hover:text-navy-800">
          ⌨ Qısayollar <Kbd>F1</Kbd>
        </button>
        {showKeys && (
          <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-1.5 rounded-xl bg-slate-50 p-3 text-xs text-slate-600 ring-1 ring-slate-200 sm:grid-cols-4">
            {([["Boşluq", "Ödəniş / sat"], ["F2", "Axtarış"], ["F4", "Kartla ödə"], ["F6", "Endirim"], ["F7", "Müştəri seç"], ["F8", "Çeki təmizlə"], ["Esc", "Axtarışı sil"], ["F1", "Bu siyahı"]] as const).map(([k, l]) => (
              <span key={k} className="flex items-center gap-2"><Kbd>{k}</Kbd>{l}</span>
            ))}
          </div>
        )}

        {categories.length > 0 && (
          <div className="-mx-1 mt-4 flex gap-2 overflow-x-auto px-1 pb-1">
            {[["", "Hamısı"] as [string, string], ...categories].map(([id, name]) => (
              <button key={id || "all"} type="button" onClick={() => setCategory(id)}
                className={cx("shrink-0 rounded-full px-4 py-1.5 text-sm font-medium transition", category === id ? "bg-navy-800 text-white shadow" : "bg-slate-100 text-slate-600 hover:bg-slate-200")}>
                {name}
              </button>
            ))}
          </div>
        )}

        <div className={fill ? "-mr-2 mt-4 min-h-0 flex-1 overflow-y-auto pr-2" : "mt-4"}>
          {!products ? (
            <div className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(150px,1fr))]">{Array.from({ length: 8 }).map((_, i) => <div key={i} className="h-44 animate-pulse rounded-2xl bg-slate-100" />)}</div>
          ) : visible.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-slate-300 py-14 text-center text-sm text-slate-500">Məhsul tapılmadı</div>
          ) : (
            <div className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(150px,1fr))]">
              {visible.map((p) => {
                const line = inCart(p.id);
                const out = p.stockQty <= 0;
                return (
                  <button key={p.id} type="button" disabled={out} onClick={() => add(p)}
                    className={cx(
                      "group relative flex flex-col overflow-hidden rounded-2xl border bg-white text-left transition-all duration-150",
                      out ? "cursor-not-allowed border-slate-200 opacity-50" : "border-slate-200 hover:-translate-y-0.5 hover:border-gold-500 hover:shadow-card active:scale-[0.98]",
                      flash === p.id && "ring-2 ring-gold-500",
                    )}>
                    <Thumb product={p} className="h-24 w-full text-2xl" />
                    {line && <span className="absolute right-2 top-2 flex h-7 min-w-7 items-center justify-center rounded-full bg-gold-500 px-2 text-xs font-bold text-navy-900 shadow">{qtyText(num(line.quantity))}</span>}
                    {p.oldPrice && p.oldPrice > p.salePrice && <span className="absolute left-2 top-2 rounded-full bg-rose-600 px-2 py-0.5 text-[10px] font-bold uppercase text-white">Endirim</span>}
                    <div className="flex flex-1 flex-col p-3">
                      <span className="line-clamp-2 text-sm font-medium leading-snug text-navy-800">{p.name}</span>
                      <div className="mt-auto flex items-end justify-between pt-2">
                        <span>
                          {p.oldPrice && p.oldPrice > p.salePrice && <span className="block text-[11px] text-slate-400 line-through">{formatMoney(p.oldPrice)}</span>}
                          <span className="font-serif text-base font-semibold tabular-nums text-navy-800">{formatMoney(priceOf(p, level))}</span>
                        </span>
                        <span className={cx("text-[11px] font-medium", out ? "text-rose-600" : p.lowStock ? "text-amber-600" : "text-slate-400")}>
                          {out ? "bitib" : `${qtyText(p.stockQty)} ${p.unit}`}
                        </span>
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </section>

      {/* ---------------- receipt ---------------- */}
      <aside className={cx("flex flex-col overflow-hidden rounded-2xl bg-white shadow-card ring-1 ring-slate-200/70",
        fill ? "min-h-0 lg:h-full" : "xl:sticky xl:top-4 xl:max-h-[calc(100vh-2rem)]")}>
        <header className="flex items-center justify-between bg-navy-800 px-5 py-3.5 text-white">
          <div>
            <p className="font-serif text-lg font-semibold">Çek</p>
            <p className="text-xs text-white/60">{cart.length === 0 ? "Səbət boşdur" : `${cart.length} məhsul · ${qtyText(itemCount)} ədəd`}</p>
          </div>
          <div className="flex items-center gap-2">
            <div className="inline-flex rounded-lg bg-white/10 p-0.5" role="group" aria-label="Qiymət növü">
              {([["WHOLESALE", "Top 1", "Topdan"], ["RETAIL", "Top 2", "Pərakəndə"]] as const).map(([lv, top, label]) => (
                <button key={lv} type="button" onClick={() => switchLevel(lv)} title={`${top} — ${label} qiyməti`}
                  className={cx("rounded-md px-2.5 py-1 text-xs font-semibold transition",
                    level === lv ? (lv === "WHOLESALE" ? "bg-gold-500 text-navy-900" : "bg-white text-navy-800") : "text-white/70 hover:text-white")}>
                  {top}<span className="hidden 2xl:inline"> · {label}</span>
                </button>
              ))}
            </div>
            {cart.length > 0 && <button type="button" onClick={reset} className="rounded-lg px-2.5 py-1 text-xs text-white/70 hover:bg-white/10 hover:text-white">Təmizlə</button>}
          </div>
        </header>
        {level === "WHOLESALE" && (
          <p className="bg-gold-500/15 px-5 py-1.5 text-xs font-semibold text-gold-600">Topdan satış — Top 1 qiymətləri ilə</p>
        )}

        {/* customer */}
        <div className="relative border-b border-slate-100 px-5 py-3">
          {customer ? (
            <div className="flex items-center justify-between gap-3 rounded-xl bg-navy-50 px-3 py-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-navy-800">{customer.name}</p>
                <p className="text-xs text-slate-500">{[customer.code, customer.phone, customer.openBalance > 0 && `borc ${formatMoney(customer.openBalance)}`,
                  customer.discountPercent ? `endirim ${customer.discountPercent}%` : null,
                  bonusAvailable > 0 ? `bonus ${formatMoney(bonusAvailable)}` : null].filter(Boolean).join(" · ")}</p>
              </div>
              <button type="button" onClick={() => pickCustomer(null)} className="text-slate-400 hover:text-rose-600" aria-label="Müştərini çıxar">✕</button>
            </div>
          ) : (
            <>
              <input
                ref={customerRef}
                value={customerQuery}
                onChange={(e) => { setCustomerQuery(e.target.value); setCustomerOpen(true); }}
                onFocus={() => setCustomerOpen(true)}
                onBlur={() => setTimeout(() => setCustomerOpen(false), 150)}
                placeholder="Müştəri (istəyə bağlı) — ad, telefon, kod"
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-gold-500"
              />
              {customerOpen && customerMatches.length > 0 && (
                <ul className="absolute left-5 right-5 z-20 mt-1 max-h-64 overflow-auto rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
                  {customerMatches.map((c) => (
                    <li key={c.id}>
                      <button type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => { pickCustomer(c); setCustomerQuery(""); setCustomerOpen(false); }}
                        className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-slate-50">
                        <span className="min-w-0 truncate"><span className="font-medium text-navy-800">{c.name}</span> <span className="text-xs text-slate-400">{c.code}</span></span>
                        {c.openBalance > 0 && <span className="shrink-0 text-xs text-rose-600">{formatMoney(c.openBalance)}</span>}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>

        {/* lines */}
        <div className="min-h-[96px] flex-1 overflow-y-auto px-3 py-2">
          {cart.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center py-10 text-center text-sm text-slate-400">
              <svg viewBox="0 0 24 24" className="mb-2 h-10 w-10 text-slate-300" fill="none" stroke="currentColor" strokeWidth={1.5} aria-hidden="true"><path d="M3 4h2l2.4 11h11.2L21 8H6.2M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm9 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" /></svg>
              Məhsula toxunun və ya barkodu oxudun
            </div>
          ) : (
            <ul className="divide-y divide-slate-100">
              {lines.map((l) => {
                const id = l.product.id;
                const short = num(l.quantity) > l.product.stockQty;
                return (
                  <li key={id} className="px-2 py-2.5">
                    <div className="flex items-start gap-3">
                      <button type="button" onClick={() => setEditing(editing === id ? null : id)} className="min-w-0 flex-1 text-left">
                        <p className="truncate text-sm font-medium text-navy-800">{l.product.name}</p>
                        <p className="text-xs text-slate-500">
                          {formatMoney(num(l.price))} × {l.quantity || 0}
                          {num(l.discount) > 0 && <span className="text-rose-600"> · −{formatMoney(num(l.discount))}</span>}
                          {short && <span className="text-rose-600"> · anbarda {qtyText(l.product.stockQty)}</span>}
                        </p>
                      </button>
                      <span className="pt-0.5 text-sm font-semibold tabular-nums text-navy-800">{formatMoney(l.total)}</span>
                    </div>
                    <div className="mt-1.5 flex items-center gap-1.5">
                      <button type="button" onClick={() => setQty(id, num(l.quantity) - 1)} className="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-lg leading-none text-slate-600 hover:border-gold-500" aria-label="Azalt">−</button>
                      <input value={l.quantity} onChange={(e) => patch(id, { quantity: e.target.value })} inputMode="decimal" aria-label="Miqdar"
                        className="h-8 w-14 rounded-lg border border-slate-200 text-center text-sm font-semibold tabular-nums outline-none focus:border-gold-500" />
                      <button type="button" onClick={() => setQty(id, num(l.quantity) + 1)} className="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-lg leading-none text-slate-600 hover:border-gold-500" aria-label="Artır">+</button>
                      <button type="button" onClick={() => setEditing(editing === id ? null : id)} className="ml-1 rounded-lg px-2 py-1 text-xs text-slate-500 hover:bg-slate-100">Qiymət / endirim</button>
                      <button type="button" onClick={() => setQty(id, 0)} className="ml-auto rounded-lg p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600" aria-label="Sil">
                        <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M5 7l1 13h12l1-13M9 7V4h6v3" /></svg>
                      </button>
                    </div>
                    {editing === id && (
                      <div className="mt-2 grid grid-cols-2 gap-2 rounded-xl bg-slate-50 p-2">
                        <label className="text-[11px] text-slate-500">Qiymət (₼)
                          <input value={l.price} onChange={(e) => patch(id, { price: e.target.value })} inputMode="decimal" className="mt-0.5 w-full rounded-lg border border-slate-200 px-2 py-1.5 text-sm outline-none focus:border-gold-500" />
                        </label>
                        <label className="text-[11px] text-slate-500">Sətir endirimi (₼)
                          <input value={l.discount} onChange={(e) => patch(id, { discount: e.target.value })} inputMode="decimal" className="mt-0.5 w-full rounded-lg border border-slate-200 px-2 py-1.5 text-sm outline-none focus:border-gold-500" />
                        </label>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        {/* totals + payment */}
        {/* totals and payment options scroll on short screens; the "Ödəniş" button below always stays in view */}
        <div className="flex min-h-0 flex-col border-t border-slate-200 bg-slate-50/60">
        <div className="min-h-0 space-y-3 overflow-y-auto px-5 pb-3 pt-4">
          <div className="flex items-center justify-between text-sm text-slate-600"><span>Ara cəmi</span><span className="tabular-nums">{formatMoney(subtotal)}</span></div>
          <div className="flex items-center justify-between gap-3 text-sm text-slate-600">
            <span>Endirim</span>
            <div className="flex items-center gap-1.5">
              <input ref={discountRef} value={discount} onChange={(e) => setDiscount(e.target.value)} inputMode="decimal" placeholder="0" aria-label="Ümumi endirim"
                className="h-8 w-20 rounded-lg border border-slate-200 bg-white px-2 text-right text-sm tabular-nums outline-none focus:border-gold-500" />
              <div className="inline-flex rounded-lg bg-white p-0.5 ring-1 ring-slate-200">
                {[false, true].map((pc) => (
                  <button key={String(pc)} type="button" onClick={() => setPercent(pc)} className={cx("rounded-md px-2 py-1 text-xs font-semibold", percent === pc ? "bg-navy-800 text-white" : "text-slate-500")}>{pc ? "%" : "₼"}</button>
                ))}
              </div>
              {discountAmount > 0 && <span className="w-20 text-right tabular-nums text-rose-600">−{formatMoney(discountAmount)}</span>}
            </div>
          </div>
          {bonusAvailable > 0 && (
            <div className="flex items-center justify-between gap-3 text-sm text-slate-600">
              <span>Bonus <span className="text-xs text-emerald-700">(balans {formatMoney(bonusAvailable)})</span></span>
              <div className="flex items-center gap-1.5">
                <input value={bonusInput} onChange={(e) => setBonusInput(e.target.value)} inputMode="decimal" placeholder="0" aria-label="Bonusdan istifadə"
                  className="h-8 w-20 rounded-lg border border-slate-200 bg-white px-2 text-right text-sm tabular-nums outline-none focus:border-gold-500" />
                <button type="button" onClick={() => setBonusInput(String(r2(Math.min(bonusAvailable, subtotal - discountAmount))))}
                  className="rounded-md bg-white px-2 py-1 text-xs font-semibold text-emerald-700 ring-1 ring-slate-200">Hamısı</button>
                {bonusAmount > 0 && <span className="w-20 text-right tabular-nums text-emerald-700">−{formatMoney(bonusAmount)}</span>}
              </div>
            </div>
          )}
          <div className="flex items-end justify-between rounded-2xl bg-navy-800 px-4 py-3 text-white">
            <span className="text-sm text-white/70">Yekun</span>
            <span className="font-serif text-3xl font-semibold tabular-nums text-gold-400">{formatMoney(total)}</span>
          </div>

          <div className="grid grid-cols-4 gap-1 rounded-xl bg-white p-1 ring-1 ring-slate-200" role="tablist" aria-label="Ödəniş üsulu">
            {([["cash", "Nağd"], ["card", "Kart"], ["mixed", "Qarışıq"], ["credit", "Borca"]] as const).map(([k, label]) => (
              <button key={k} type="button" role="tab" aria-selected={mode === k} onClick={() => { setMode(k); setCash(""); setCard(""); setGiven(""); }}
                className={cx("rounded-lg py-2 text-sm font-medium transition", mode === k ? "bg-gold-500 text-navy-900 shadow" : "text-slate-600 hover:bg-slate-100")}>
                {label}
              </button>
            ))}
          </div>

          {mode === "cash" && total > 0 && (
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <label className="text-sm text-slate-600" htmlFor="pos-given">Müştəri verdi</label>
                <input id="pos-given" value={given} onChange={(e) => setGiven(e.target.value)} inputMode="decimal" placeholder={total.toFixed(2)}
                  className="h-10 flex-1 rounded-xl border border-slate-200 bg-white px-3 text-right text-lg font-semibold tabular-nums outline-none focus:border-gold-500" />
              </div>
              <div className="flex flex-wrap gap-1.5">
                {quickCash(total).map((v) => (
                  <button key={v} type="button" onClick={() => setGiven(v.toFixed(2))}
                    className={cx("rounded-lg border px-2.5 py-1 text-xs font-semibold tabular-nums transition", num(given) === v ? "border-gold-500 bg-gold-500/10 text-navy-800" : "border-slate-200 bg-white text-slate-600 hover:border-gold-500")}>
                    {v === r2(total) ? "Dəqiq" : formatMoney(v)}
                  </button>
                ))}
              </div>
              {change > 0 && (
                <div className="flex items-center justify-between rounded-xl bg-emerald-50 px-4 py-2.5 text-emerald-800 ring-1 ring-emerald-200">
                  <span className="text-sm font-medium">Qaytarılacaq pul</span>
                  <span className="font-serif text-2xl font-semibold tabular-nums">{formatMoney(change)}</span>
                </div>
              )}
            </div>
          )}
          {mode === "card" && total > 0 && <p className="rounded-xl bg-sky-50 px-3 py-2 text-sm text-sky-800">Kartla {formatMoney(total)} ödənilir.</p>}
          {(mode === "mixed" || mode === "credit") && (
            <div className={cx("grid gap-2", mode === "mixed" ? "grid-cols-2" : "grid-cols-1")}>
              <label className="text-xs text-slate-500">{mode === "credit" ? "İlkin ödəniş, nağd (istəyə bağlı)" : "Nağd (₼)"}
                <input value={cash} onChange={(e) => setCash(e.target.value)} inputMode="decimal" placeholder="0"
                  className="mt-0.5 h-10 w-full rounded-xl border border-slate-200 bg-white px-3 text-right font-semibold tabular-nums outline-none focus:border-gold-500" />
              </label>
              {mode === "mixed" && (
                <label className="text-xs text-slate-500">Kart (₼)
                  <input value={card} onChange={(e) => setCard(e.target.value)} inputMode="decimal" placeholder="0"
                    className="mt-0.5 h-10 w-full rounded-xl border border-slate-200 bg-white px-3 text-right font-semibold tabular-nums outline-none focus:border-gold-500" />
                </label>
              )}
            </div>
          )}

          {credit > 0.004 && !over && (
            <p className={cx("rounded-xl px-3 py-2 text-sm", customer ? "bg-amber-50 text-amber-800" : "bg-rose-50 text-rose-700")}>
              {customer ? <>{formatMoney(credit)} <b>{customer.name}</b> adlı müştərinin borcuna yazılacaq</> : <>Ödənilməyən {formatMoney(credit)} borca yazılır — yuxarıdan müştəri seçin</>}
            </p>
          )}
          {over && <p className="rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-700">Ödəniş yekundan {formatMoney(-credit)} çoxdur.</p>}
          {stockProblem && <p className="rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-700">“{stockProblem.product.name}” anbarda kifayət deyil ({qtyText(stockProblem.product.stockQty)}).</p>}
          {error && <Alert>{error}</Alert>}

          {showNote ? (
            <input autoFocus value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder="Qeyd"
              className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-gold-500" />
          ) : (
            <button type="button" onClick={() => setShowNote(true)} className="text-xs font-medium text-slate-500 hover:text-navy-800">+ Qeyd əlavə et</button>
          )}

          <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-600">
            <input type="checkbox" className="h-4 w-4 accent-navy-800" checked={autoPrint} onChange={(e) => toggleAutoPrint(e.target.checked)} />
            Satışdan sonra çeki printerdən çıxar
          </label>
          {/* customer display: a popup in the browser, a full-screen window on the second monitor in the Windows app */}
          {(!app || app.openCustomerDisplay) && (
            <button type="button" onClick={() => (app?.openCustomerDisplay ? void app.openCustomerDisplay() : window.open("/musteri-ekrani", "hesabpro-display", "popup,width=1024,height=700"))}
              className="text-left text-xs font-medium text-slate-500 hover:text-navy-800">🖥 Müştəri ekranını aç (ikinci monitor)</button>
          )}
          {app?.openDrawer && (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-600">
                <input type="checkbox" className="h-4 w-4 accent-navy-800" checked={drawerAuto}
                  onChange={(e) => { setDrawerAuto(e.target.checked); void app.setDrawerAuto?.(e.target.checked); }} />
                Nağd satışdan sonra pul qutusunu aç
              </label>
              <button type="button" onClick={() => void app.openDrawer?.()} className="rounded-lg bg-white px-2.5 py-1 text-xs font-medium text-navy-800 ring-1 ring-slate-200 hover:ring-gold-500">💰 Qutunu aç</button>
            </div>
          )}
          {app?.setAutoStart && autoStart !== null && (
            <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-600">
              <input type="checkbox" className="h-4 w-4 accent-navy-800" checked={autoStart}
                onChange={(e) => { setAutoStart(e.target.checked); void app.setAutoStart?.(e.target.checked); }} />
              Kompüter açılanda kassa özü açılsın
            </label>
          )}
          {app && (
            <label className="flex items-center gap-2 text-xs text-slate-600">
              Printer
              <select value={printer ?? ""} onChange={(e) => { const v = e.target.value || null; setPrinterState(v); void app.setPrinter(v); }}
                className="min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs outline-none focus:border-gold-500">
                <option value="">Windows-un əsas printeri</option>
                {printers.map((p) => <option key={p.name} value={p.name}>{p.name}{p.isDefault ? " (əsas)" : ""}</option>)}
              </select>
            </label>
          )}
          {app && pending > 0 && (
            <button type="button" onClick={() => void app.syncNow()}
              className="w-full rounded-xl bg-amber-50 px-3 py-2 text-left text-xs font-medium text-amber-800 ring-1 ring-amber-200 hover:bg-amber-100">
              {pending} satış hələ serverə göndərilməyib — internet gələndə özü gedəcək. İndi göndər
            </button>
          )}
        </div>
        <div className="shrink-0 px-5 pb-4 pt-2">
          <button type="button" disabled={cart.length === 0 || busy} onClick={() => { setError(null); setPayOpen(true); }}
            className="flex w-full items-center justify-between rounded-2xl bg-gold-500 px-5 py-4 text-navy-900 shadow-sm transition hover:bg-gold-600 hover:text-white active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-50">
            <span className="text-base font-semibold">{busy ? "Saxlanılır…" : "Ödəniş"}</span>
            <span className="flex items-center gap-2"><span className="font-serif text-lg font-semibold tabular-nums">{formatMoney(total)}</span><Kbd>Boşluq</Kbd></span>
          </button>
        </div>
        </div>
      </aside>

      {payOpen && (
        <Modal open title="Ödəniş" onClose={() => { setPayOpen(false); searchRef.current?.focus(); }}
          footer={<>
            <Button variant="ghost" onClick={() => setPayOpen(false)}>Geri <Kbd>Esc</Kbd></Button>
            <Button variant="gold" disabled={!canSell} onClick={() => void submit()}>
              {busy ? "Saxlanılır…" : autoPrint ? "Sat və çeki çap et" : "Satışı tamamla"} <Kbd>Boşluq</Kbd>
            </Button>
          </>}>
          <div
            tabIndex={-1}
            className="outline-none"
            onKeyDown={(e) => {
              if (e.key === " " || e.key === "Enter") {
                const t = e.target as HTMLElement;
                if (t.tagName === "BUTTON" && e.key === "Enter") return;
                e.preventDefault();
                void submit();
              }
            }}>
            <div className="mb-4 flex items-end justify-between rounded-2xl bg-navy-800 px-5 py-4 text-white">
              <span className="text-sm text-white/70">Yekun{customer ? ` · ${customer.name}` : ""}</span>
              <span className="font-serif text-4xl font-semibold tabular-nums text-gold-400">{formatMoney(total)}</span>
            </div>
            <div className="mb-4 grid grid-cols-4 gap-1 rounded-xl bg-slate-100 p-1">
              {([["cash", "Nağd"], ["card", "Kart"], ["mixed", "Qarışıq"], ["credit", "Borca"]] as const).map(([k, label]) => (
                <button key={k} type="button" tabIndex={-1} onClick={() => { setMode(k); setCash(""); setCard(""); setGiven(""); }}
                  className={cx("rounded-lg py-2 text-sm font-medium", mode === k ? "bg-gold-500 text-navy-900 shadow" : "text-slate-600")}>{label}</button>
              ))}
            </div>
            {mode === "cash" && (
              <>
                <label htmlFor="pay-given" className="mb-1 block text-sm font-medium text-slate-700">Müştəri verdi (₼)</label>
                <input id="pay-given" autoFocus value={given} inputMode="decimal" placeholder={total.toFixed(2)}
                  onChange={(e) => setGiven(e.target.value.replace(/[^\d.,]/g, ""))}
                  className="w-full rounded-2xl border-2 border-gold-500 px-4 py-3 text-right font-serif text-3xl font-semibold tabular-nums text-navy-800 outline-none shadow-glow" />
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {quickCash(total).map((v) => (
                    <button key={v} type="button" tabIndex={-1} onClick={() => setGiven(v.toFixed(2))}
                      className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-semibold tabular-nums text-slate-700 hover:border-gold-500">
                      {v === r2(total) ? "Dəqiq" : formatMoney(v)}
                    </button>
                  ))}
                </div>
                <div className={cx("mt-4 flex items-center justify-between rounded-2xl px-5 py-3 ring-1", change > 0 ? "bg-emerald-50 text-emerald-800 ring-emerald-200" : "bg-slate-50 text-slate-500 ring-slate-200")}>
                  <span className="text-sm font-medium">Qaytarılacaq pul</span>
                  <span className="font-serif text-3xl font-semibold tabular-nums">{formatMoney(change)}</span>
                </div>
                {num(given) > 0 && num(given) < total && <p className="mt-2 text-sm text-amber-700">Çatmayan {formatMoney(r2(total - num(given)))} borca yazılacaq{customer ? "" : " — müştəri seçilməlidir"}.</p>}
              </>
            )}
            {mode === "card" && <p className="rounded-xl bg-sky-50 px-4 py-3 text-sky-800">Kartla <b>{formatMoney(total)}</b> ödənilir.</p>}
            {(mode === "mixed" || mode === "credit") && (
              <div className={cx("grid gap-3", mode === "mixed" ? "grid-cols-2" : "grid-cols-1")}>
                <label className="text-sm text-slate-600">{mode === "credit" ? "İlkin ödəniş, nağd" : "Nağd (₼)"}
                  <input autoFocus value={cash} inputMode="decimal" placeholder="0" onChange={(e) => setCash(e.target.value.replace(/[^\d.,]/g, ""))}
                    className="mt-1 w-full rounded-xl border border-slate-300 px-3 py-2.5 text-right text-xl font-semibold tabular-nums outline-none focus:border-gold-500" />
                </label>
                {mode === "mixed" && (
                  <label className="text-sm text-slate-600">Kart (₼)
                    <input value={card} inputMode="decimal" placeholder="0" onChange={(e) => setCard(e.target.value.replace(/[^\d.,]/g, ""))}
                      className="mt-1 w-full rounded-xl border border-slate-300 px-3 py-2.5 text-right text-xl font-semibold tabular-nums outline-none focus:border-gold-500" />
                  </label>
                )}
                {credit > 0.004 && <p className={cx("text-sm", customer ? "text-amber-700" : "text-rose-700")}>{formatMoney(credit)} borca{customer ? ` — ${customer.name}` : " — əvvəlcə çekdə müştəri seçin"}</p>}
              </div>
            )}
            {over && <p className="mt-3 text-sm text-rose-700">Ödəniş yekundan çoxdur.</p>}
            {stockProblem && <p className="mt-3 text-sm text-rose-700">“{stockProblem.product.name}” anbarda kifayət deyil.</p>}
            {error && <div className="mt-3"><Alert>{error}</Alert></div>}
            <p className="mt-4 text-center text-xs text-slate-400">Boşluq və ya Enter — satışı tamamla{autoPrint ? " və çeki çap et" : ""} · Esc — geri</p>
          </div>
        </Modal>
      )}

      {done && (
        <Modal open title="Satış tamamlandı" onClose={() => { setDone(null); searchRef.current?.focus(); }}
          footer={<>
            {!done.offline && <Button variant="outline" onClick={() => void shareReceipt(done.sale)}>WhatsApp-a çek</Button>}
            <Button variant="outline" onClick={() => printSale(done.sale, done.change, done.offline)}>{autoPrint ? "Çeki yenidən çap et" : "Çeki çap et"}</Button>
            <Button variant="gold" autoFocus data-pos-next onClick={() => { setDone(null); setTimeout(() => searchRef.current?.focus(), 0); }}>Yeni satış <Kbd>Boşluq</Kbd></Button>
          </>}>
          <div className="text-center">
            <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-emerald-700">
              <svg viewBox="0 0 24 24" className="h-7 w-7" fill="none" stroke="currentColor" strokeWidth={2.5} aria-hidden="true"><path d="m5 12 5 5 9-10" /></svg>
            </div>
            <p className="text-sm text-slate-500">{done.sale.number}{done.sale.customerName ? ` · ${done.sale.customerName}` : ""}</p>
            {done.offline && (
              <p className="mx-auto mt-2 max-w-xs rounded-xl bg-amber-50 px-3 py-2 text-xs text-amber-800 ring-1 ring-amber-200">
                İnternet yoxdur: satış bu kompüterdə saxlanıldı, internet gələndə özü göndəriləcək.
              </p>
            )}
            <p className="mt-1 font-serif text-3xl font-semibold tabular-nums text-navy-800">{formatMoney(done.sale.total)}</p>
            <div className="mx-auto mt-4 max-w-xs space-y-1 text-sm">
              {done.sale.paidCash > 0 && <div className="flex justify-between"><span className="text-slate-500">Nağd</span><span className="tabular-nums">{formatMoney(done.sale.paidCash)}</span></div>}
              {done.sale.paidCard > 0 && <div className="flex justify-between"><span className="text-slate-500">Kart</span><span className="tabular-nums">{formatMoney(done.sale.paidCard)}</span></div>}
              {done.sale.creditAmount > 0 && <div className="flex justify-between text-amber-700"><span>Borca</span><span className="tabular-nums">{formatMoney(done.sale.creditAmount)}</span></div>}
            </div>
            {done.change > 0 && (
              <div className="mx-auto mt-4 max-w-xs rounded-2xl bg-emerald-50 px-4 py-3 text-emerald-800 ring-1 ring-emerald-200">
                <p className="text-xs font-medium uppercase tracking-wider">Müştəriyə qaytarın</p>
                <p className="font-serif text-3xl font-semibold tabular-nums">{formatMoney(done.change)}</p>
              </div>
            )}
          </div>
        </Modal>
      )}
    </div>
  );
}
