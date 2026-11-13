"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney } from "@/lib/format";
import type { ProductCategory, StockCount, StockCountDetail } from "@/lib/types";

/** Anbar sayımı: list of counts with their +/− result; start a new count. */
export default function StockCountsPage() {
  const { isOwner, can } = useAuth();
  const router = useRouter();
  const [items, setItems] = useState<StockCount[] | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const manage = isOwner || can("INVENTORY");

  useEffect(() => {
    if (!manage) return;
    api<StockCount[]>("/api/stock-counts").then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [manage]);

  if (!manage) return <Card><EmptyState title="Anbar sayımı üçün icazəniz yoxdur" /></Card>;
  const open = items?.find((c) => c.status === "IN_PROGRESS");

  return (
    <>
      <PageHeader title="Anbar sayımı" subtitle="Məhsulları sayın, sayılan miqdarı daxil edin, +/− fərq hesabatını alın"
        actions={<div className="flex gap-2">
          <Link href="/anbar" className="inline-flex items-center rounded-xl px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100">← Anbar</Link>
          {open
            ? <Button variant="gold" onClick={() => router.push(`/anbar/sayim/${open.id}`)}>Davam edən sayım: {open.number}</Button>
            : <Button variant="gold" onClick={() => setStarting(true)}>+ Yeni sayım</Button>}
        </div>} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <Card>
        {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Hələ sayım yoxdur" text="“+ Yeni sayım” ilə başlayın." /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr>
              <Th>Sayım</Th><Th>Tarix</Th><Th>Kim</Th><Th className="text-right">Sayılıb</Th>
              <Th className="text-right">Artıq (+)</Th><Th className="text-right">Kəsir (−)</Th><Th className="text-right">Xalis</Th>
            </tr></thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((c) => (
                <tr key={c.id} className="cursor-pointer hover:bg-slate-50" onClick={() => router.push(`/anbar/sayim/${c.id}`)}>
                  <Td>
                    <span className="font-medium text-navy-800">{c.number}</span>{" "}
                    {c.status === "IN_PROGRESS" ? <Badge tone="amber">davam edir</Badge> : <Badge tone="emerald">tamamlanıb</Badge>}
                    <div className="text-xs text-slate-400">{c.categoryName ?? "Bütün məhsullar"}{c.note ? ` · ${c.note}` : ""}</div>
                  </Td>
                  <Td className="whitespace-nowrap text-xs">{formatDateTime(c.completedAt ?? c.createdAt)}</Td>
                  <Td className="text-xs">{c.completedByName ?? c.createdByName}</Td>
                  <Td className="text-right tabular-nums">{c.totals.countedItems} / {c.totals.totalItems}</Td>
                  <Td className="text-right tabular-nums text-emerald-700">{c.totals.surplusItems} · {formatMoney(c.totals.surplusValue)}</Td>
                  <Td className="text-right tabular-nums text-rose-600">{c.totals.shortageItems} · {formatMoney(c.totals.shortageValue)}</Td>
                  <Td className={`text-right font-semibold tabular-nums ${c.totals.netValue < 0 ? "text-rose-600" : c.totals.netValue > 0 ? "text-emerald-700" : "text-navy-800"}`}>
                    {c.totals.netValue > 0 ? "+" : ""}{formatMoney(c.totals.netValue)}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {starting && <StartModal onClose={() => setStarting(false)} onStarted={(d) => router.push(`/anbar/sayim/${d.count.id}`)} />}
    </>
  );
}

function StartModal({ onClose, onStarted }: { onClose: () => void; onStarted: (d: StockCountDetail) => void }) {
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [form, setForm] = useState({ categoryId: "", note: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api<ProductCategory[]>("/api/product-categories").then(setCategories).catch(() => setCategories([])); }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      onStarted(await api<StockCountDetail>("/api/stock-counts", { method: "POST", json: { categoryId: form.categoryId || null, note: form.note || null } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Sayım başlamadı");
      setBusy(false);
    }
  }

  return (
    <Modal open title="Yeni sayım" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="count-form" variant="gold" disabled={busy}>Başla</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="count-form" onSubmit={submit} className="grid gap-4">
        <Field label="Nə sayılır?" hint="Seçilən məhsullar indiki sistem qalığı ilə siyahıya düşür">
          <Select value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
            <option value="">Bütün aktiv məhsullar</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
        </Field>
        <Field label="Qeyd"><Input maxLength={500} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} placeholder="məs. Ay sonu sayımı" /></Field>
      </form>
    </Modal>
  );
}
