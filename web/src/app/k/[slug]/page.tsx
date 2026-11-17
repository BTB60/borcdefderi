"use client";

/* eslint-disable @next/next/no-img-element -- product pictures come from the API, not from the Next image pipeline */
import { useParams } from "next/navigation";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { API_URL, ApiError, api } from "@/lib/api";
import { formatMoney } from "@/lib/format";

type Product = { id: string; name: string; category: string | null; unit: string; price: number; oldPrice: number | null; inStock: boolean; hasImage: boolean };
type Catalog = { storeName: string; phone: string | null; address: string | null; note: string | null; products: Product[] };

/** Onlayn kataloq: the store's goods for customers — no login; a basket and an order with name and phone. */
export default function CatalogPage() {
  const { slug } = useParams<{ slug: string }>();
  const [data, setData] = useState<Catalog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [cat, setCat] = useState("");
  const [cart, setCart] = useState<Record<string, number>>({});
  const [checkout, setCheckout] = useState(false);
  const [form, setForm] = useState({ name: "", phone: "", address: "", note: "" });
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<{ number: string; total: number } | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    api<Catalog>(`/api/public/catalog/${encodeURIComponent(slug)}`).then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Kataloq açılmadı"));
    try {
      const saved = localStorage.getItem(`cart:${slug}`);
      if (saved) setCart(JSON.parse(saved));
    } catch { /* ignore */ }
  }, [slug]);

  function setQty(id: string, n: number) {
    setCart((c) => {
      const next = { ...c };
      if (n <= 0) delete next[id]; else next[id] = Math.min(n, 999);
      try { localStorage.setItem(`cart:${slug}`, JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  }

  const products = useMemo(() => data?.products ?? [], [data]);
  const cats = [...new Set(products.map((p) => p.category).filter(Boolean) as string[])];
  const shown = useMemo(() => products.filter((p) => (!cat || p.category === cat) && (!q || p.name.toLowerCase().includes(q.toLowerCase()))), [products, cat, q]);
  const lines = products.filter((p) => cart[p.id]).map((p) => ({ p, n: cart[p.id] }));
  const total = lines.reduce((a, l) => a + l.p.price * l.n, 0);
  const count = lines.reduce((a, l) => a + l.n, 0);

  async function send(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setFormError(null);
    try {
      const r = await api<{ number: string; total: number }>(`/api/public/catalog/${encodeURIComponent(slug)}/orders`, {
        method: "POST",
        json: { ...form, address: form.address || null, note: form.note || null, items: lines.map((l) => ({ productId: l.p.id, quantity: l.n })) },
      });
      setDone(r);
      setCart({});
      try { localStorage.removeItem(`cart:${slug}`); } catch { /* ignore */ }
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "Sifariş göndərilmədi");
    } finally {
      setBusy(false);
    }
  }

  if (error) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-canvas p-6">
        <div className="max-w-sm rounded-2xl bg-white p-8 text-center shadow-card">
          <p className="font-serif text-xl font-semibold text-navy-800">Kataloq açılmadı</p>
          <p className="mt-2 text-sm text-slate-500">{error}</p>
        </div>
      </main>
    );
  }
  if (!data) return <main className="flex min-h-screen items-center justify-center bg-canvas text-sm text-slate-500">Yüklənir…</main>;

  return (
    <main className="min-h-screen bg-canvas pb-28">
      <header className="bg-gradient-to-br from-navy-800 to-navy-600 px-4 pb-6 pt-8 text-white">
        <div className="mx-auto max-w-5xl">
          <h1 className="font-serif text-3xl font-semibold">{data.storeName}</h1>
          <p className="mt-1 text-sm text-white/70">{[data.address, data.phone].filter(Boolean).join(" · ")}</p>
          {data.note && <p className="mt-3 inline-block rounded-full bg-gold-500/20 px-3 py-1 text-sm text-gold-300">{data.note}</p>}
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Mal axtar…" aria-label="Axtarış"
            className="mt-5 w-full rounded-xl border-0 bg-white/95 px-4 py-3 text-sm text-navy-800 outline-none ring-2 ring-transparent focus:ring-gold-400" />
        </div>
      </header>

      <div className="mx-auto max-w-5xl px-4">
        {cats.length > 0 && (
          <div className="-mx-4 mt-4 flex gap-2 overflow-x-auto px-4 pb-1">
            {["", ...cats].map((c) => (
              <button key={c || "all"} onClick={() => setCat(c)}
                className={`whitespace-nowrap rounded-full px-4 py-1.5 text-sm font-medium ${cat === c ? "bg-navy-800 text-white" : "bg-white text-slate-600 ring-1 ring-slate-200"}`}>
                {c || "Hamısı"}
              </button>
            ))}
          </div>
        )}

        {done ? (
          <div className="mt-8 rounded-2xl bg-white p-8 text-center shadow-card">
            <p className="text-4xl">✅</p>
            <p className="mt-3 font-serif text-2xl font-semibold text-navy-800">Sifarişiniz qəbul edildi</p>
            <p className="mt-2 text-slate-600">Nömrə: <b>{done.number}</b> · Məbləğ: <b>{formatMoney(done.total)}</b></p>
            <p className="mt-2 text-sm text-slate-500">Mağaza tezliklə sizinlə əlaqə saxlayacaq.</p>
            <button onClick={() => { setDone(null); setCheckout(false); }} className="mt-5 rounded-xl bg-navy-800 px-5 py-2.5 text-sm font-semibold text-white">Kataloqa qayıt</button>
          </div>
        ) : (
          <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {shown.map((p) => (
              <div key={p.id} className={`flex flex-col overflow-hidden rounded-2xl bg-white shadow-card ring-1 ring-slate-200/70 ${!p.inStock ? "opacity-60" : ""}`}>
                <div className="flex aspect-square items-center justify-center bg-slate-50">
                  {p.hasImage
                    ? <img src={`${API_URL}/api/public/catalog/${encodeURIComponent(slug)}/images/${p.id}`} alt={p.name} loading="lazy" className="h-full w-full object-cover" />
                    : <span className="font-serif text-4xl text-slate-300">{p.name.slice(0, 1)}</span>}
                </div>
                <div className="flex flex-1 flex-col p-3">
                  <p className="line-clamp-2 text-sm font-medium text-navy-800">{p.name}</p>
                  <div className="mt-auto pt-2">
                    {p.oldPrice && p.oldPrice > p.price && <span className="mr-1 text-xs text-slate-400 line-through">{formatMoney(p.oldPrice)}</span>}
                    <span className="font-semibold text-navy-800">{formatMoney(p.price)}</span>
                    <span className="text-xs text-slate-400"> / {p.unit}</span>
                  </div>
                  {!p.inStock ? <p className="mt-2 text-center text-xs text-slate-500">Hazırda yoxdur</p> : cart[p.id] ? (
                    <div className="mt-2 flex items-center justify-between rounded-xl bg-navy-800 text-white">
                      <button className="px-3 py-1.5 text-lg" onClick={() => setQty(p.id, cart[p.id] - 1)} aria-label="Azalt">−</button>
                      <span className="text-sm font-semibold">{cart[p.id]}</span>
                      <button className="px-3 py-1.5 text-lg" onClick={() => setQty(p.id, cart[p.id] + 1)} aria-label="Artır">+</button>
                    </div>
                  ) : (
                    <button onClick={() => setQty(p.id, 1)} className="mt-2 rounded-xl bg-gold-500 py-1.5 text-sm font-semibold text-navy-900 hover:bg-gold-400">Səbətə at</button>
                  )}
                </div>
              </div>
            ))}
            {shown.length === 0 && <p className="col-span-full py-10 text-center text-sm text-slate-500">Mal tapılmadı</p>}
          </div>
        )}
      </div>

      {count > 0 && !done && (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white/95 p-3 backdrop-blur">
          <div className="mx-auto flex max-w-5xl items-center gap-3">
            <div className="flex-1"><p className="text-xs text-slate-500">{count} mal</p><p className="font-semibold text-navy-800">{formatMoney(total)}</p></div>
            <button onClick={() => setCheckout(true)} className="rounded-xl bg-navy-800 px-6 py-3 text-sm font-semibold text-white">Sifariş et</button>
          </div>
        </div>
      )}

      {checkout && !done && (
        <div className="fixed inset-0 z-30 flex items-end justify-center bg-navy-900/40 sm:items-center" onClick={() => setCheckout(false)}>
          <form onSubmit={send} onClick={(e) => e.stopPropagation()} className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-t-3xl bg-white p-5 sm:rounded-3xl">
            <p className="font-serif text-xl font-semibold text-navy-800">Sifariş</p>
            <ul className="my-3 divide-y divide-slate-100 text-sm">
              {lines.map((l) => (
                <li key={l.p.id} className="flex justify-between py-1.5"><span>{l.p.name} × {l.n}</span><span className="tabular-nums">{formatMoney(l.p.price * l.n)}</span></li>
              ))}
              <li className="flex justify-between py-2 font-semibold"><span>Cəmi</span><span>{formatMoney(total)}</span></li>
            </ul>
            {formError && <p className="mb-3 rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-700">{formError}</p>}
            {[["name", "Adınız", true, 100], ["phone", "Telefon", true, 30], ["address", "Ünvan (çatdırılma üçün)", false, 300], ["note", "Qeyd", false, 500]].map(([k, l, req, max]) => (
              <label key={k as string} className="mb-3 block text-sm">
                <span className="text-slate-600">{l as string}</span>
                <input required={req as boolean} maxLength={max as number} value={form[k as keyof typeof form]} inputMode={k === "phone" ? "tel" : undefined}
                  onChange={(e) => setForm({ ...form, [k as string]: e.target.value })}
                  className="mt-1 w-full rounded-xl border border-slate-300 px-3 py-2.5 outline-none focus:border-gold-500" />
              </label>
            ))}
            <button type="submit" disabled={busy} className="mt-2 w-full rounded-xl bg-navy-800 py-3 text-sm font-semibold text-white disabled:opacity-50">{busy ? "Göndərilir…" : "Sifarişi göndər"}</button>
            <p className="mt-3 text-center text-xs text-slate-400">HesabPro ilə</p>
          </form>
        </div>
      )}
    </main>
  );
}
