"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, PageHeader, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { Party, Product } from "@/lib/types";
import { waLink } from "@/lib/whatsapp";

const q = (n: number) => Number(n).toLocaleString("az-AZ", { maximumFractionDigits: 3 });

/** How much to order: up to twice the minimum stock (at least 1). */
const suggested = (p: Product) => Math.max(1, Math.ceil(Math.max(p.minStock, 1) * 2 - Math.max(p.stockQty, 0)));

/**
 * Sifariş siyahısı: goods at or below their minimum stock, grouped by supplier, with a ready order message that is
 * sent to the supplier on WhatsApp with one tap.
 */
export default function ReorderPage() {
  const { me } = useAuth();
  const [products, setProducts] = useState<Product[] | null>(null);
  const [suppliers, setSuppliers] = useState<Party[]>([]);
  const [qty, setQty] = useState<Record<string, string>>({});
  const [skip, setSkip] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  useEffect(() => {
    api<Product[]>("/api/products?active=true").then(setProducts).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
    api<Party[]>("/api/companies").then(setSuppliers).catch(() => setSuppliers([]));
  }, []);

  const groups = useMemo(() => {
    const low = (products ?? []).filter((p) => p.lowStock || p.outOfStock);
    const by = new Map<string, Product[]>();
    for (const p of low) {
      const k = p.supplierId ?? "";
      by.set(k, [...(by.get(k) ?? []), p]);
    }
    return [...by.entries()].map(([id, items]) => ({
      id, items: items.sort((a, b) => a.stockQty - b.stockQty),
      supplier: suppliers.find((s) => s.id === id) ?? null, name: items[0]?.supplierName ?? null,
    })).sort((a, b) => (a.id === "" ? 1 : b.id === "" ? -1 : (a.name ?? "").localeCompare(b.name ?? "", "az")));
  }, [products, suppliers]);

  const amount = (p: Product) => qty[p.id] ?? String(suggested(p));

  function message(items: Product[]) {
    const lines = items.filter((p) => !skip[p.id] && Number(amount(p)) > 0).map((p, i) => `${i + 1}. ${p.name}${p.code ? ` (${p.code})` : ""} — ${amount(p)} ${p.unit}`);
    return `Salam! ${me?.storeName ?? "Mağazamız"} üçün sifariş:\n${lines.join("\n")}\n\nTəşəkkür edirik!`;
  }

  async function copy(id: string, text: string) {
    try { await navigator.clipboard.writeText(text); setCopied(id); setTimeout(() => setCopied(null), 2000); } catch { setError("Kopyalanmadı"); }
  }

  return (
    <>
      <PageHeader title="Sifariş siyahısı" subtitle="Minimum stokdan aşağı düşən mallar — təchizatçılara görə, hazır sifariş mesajı ilə"
        actions={<Link href="/anbar" className="inline-flex items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-navy-800 hover:border-gold-500">← Anbar</Link>} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {!products ? <Spinner /> : groups.length === 0 ? (
        <Card><EmptyState title="Sifariş ediləcək mal yoxdur" text="Bütün mallar minimum stokdan yuxarıdadır." /></Card>
      ) : (
        <div className="space-y-5">
          {groups.map((g) => {
            const text = message(g.items);
            const wa = g.supplier?.phone ? waLink(g.supplier.phone, text) : null;
            return (
              <Card key={g.id || "none"}>
                <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <p className="font-semibold text-navy-800">{g.name ?? "Təchizatçısız mallar"}</p>
                    <p className="text-xs text-slate-500">{g.items.length} mal{g.supplier?.phone ? ` · ${g.supplier.phone}` : g.id ? " · telefon yoxdur" : " · məhsula təchizatçı təyin edin"}</p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button variant="outline" className="px-3 py-1.5" onClick={() => copy(g.id, text)}>{copied === g.id ? "Kopyalandı ✓" : "Mətni kopyala"}</Button>
                    {wa && <a href={wa} target="_blank" rel="noreferrer" className="rounded-xl bg-emerald-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-emerald-700">WhatsApp-da göndər</a>}
                    <Link href="/anbar/qebul" className="rounded-xl bg-gold-500 px-3 py-1.5 text-sm font-semibold text-navy-900 hover:bg-gold-600">Mal gəldi → qəbul et</Link>
                  </div>
                </div>
                <div className="divide-y divide-slate-100 rounded-xl ring-1 ring-slate-200">
                  {g.items.map((p) => (
                    <label key={p.id} className={`flex flex-wrap items-center gap-3 px-3 py-2 text-sm ${skip[p.id] ? "opacity-50" : ""}`}>
                      <input type="checkbox" className="h-4 w-4 accent-navy-800" checked={!skip[p.id]} onChange={(e) => setSkip({ ...skip, [p.id]: !e.target.checked })} />
                      <span className="min-w-0 flex-1">
                        <span className="font-medium text-navy-800">{p.name}</span> <span className="text-xs text-slate-400">{p.code}</span>
                      </span>
                      {p.outOfStock ? <Badge tone="rose">Bitib</Badge> : <Badge tone="amber">Qalıq {q(p.stockQty)} / min {q(p.minStock)}</Badge>}
                      <span className="flex items-center gap-1.5 text-xs text-slate-500">
                        Sifariş
                        <Input className="h-8 w-20 text-right" inputMode="decimal" value={amount(p)} onChange={(e) => setQty({ ...qty, [p.id]: e.target.value })} />
                        {p.unit}
                      </span>
                    </label>
                  ))}
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
