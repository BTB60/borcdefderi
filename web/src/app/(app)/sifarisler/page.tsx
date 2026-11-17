"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney } from "@/lib/format";
import { waLink } from "@/lib/whatsapp";

type Order = {
  id: string; number: string; customerName: string; customerPhone: string; address: string | null; note: string | null;
  total: number; status: "NEW" | "CONFIRMED" | "DONE" | "CANCELLED"; saleId: string | null; createdAt: string;
  items: { productId: string | null; name: string; quantity: number; unitPrice: number; lineTotal: number }[];
};
type Settings = { enabled: boolean; slug: string | null; note: string | null };

const STATUS: Record<Order["status"], { l: string; t: "amber" | "sky" | "emerald" | "slate" }> = {
  NEW: { l: "Yeni", t: "amber" }, CONFIRMED: { l: "Təsdiqləndi", t: "sky" }, DONE: { l: "Satıldı", t: "emerald" }, CANCELLED: { l: "Ləğv edildi", t: "slate" },
};

/** Onlayn sifarişlər: orders from the public catalog, and switching the catalog on with its link. */
export default function OnlineOrdersPage() {
  const { isOwner, me } = useAuth();
  const [orders, setOrders] = useState<Order[] | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [filter, setFilter] = useState<"open" | "all">("open");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const load = useCallback(() => {
    api<Order[]>("/api/online-orders").then(setOrders).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, []);
  useEffect(() => {
    load();
    api<Settings>("/api/catalog/settings").then((s) => { setSettings(s); setNote(s.note ?? ""); }).catch(() => undefined);
  }, [load]);

  const link = settings?.slug && typeof window !== "undefined" ? `${window.location.origin}/k/${settings.slug}` : null;

  async function toggle(enabled: boolean) {
    try {
      setSettings(await api<Settings>("/api/catalog/settings", { method: "PUT", json: { enabled, note: note || null } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Saxlanmadı");
    }
  }

  async function status(o: Order, st: Order["status"]) {
    await api(`/api/online-orders/${o.id}/status`, { method: "POST", json: { status: st } }).catch((e) => setError(e instanceof ApiError ? e.message : "Dəyişmədi"));
    load();
  }

  const shown = (orders ?? []).filter((o) => filter === "all" || o.status === "NEW" || o.status === "CONFIRMED");

  return (
    <>
      <PageHeader title="Onlayn sifarişlər" subtitle="Müştərilər kataloq linkindən sifariş verir, siz burada qəbul edib kassada satırsınız" />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      {isOwner && settings && (
        <Card className="mb-5">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0 flex-1">
              <h2 className="font-serif text-lg font-semibold text-navy-800">Onlayn kataloq {settings.enabled ? <Badge tone="emerald">Açıqdır</Badge> : <Badge tone="slate">Bağlıdır</Badge>}</h2>
              <p className="mt-1 text-sm text-slate-500">Aktiv məhsullar şəkil və satış qiyməti ilə görünür (alış qiyməti və qalıq sayı görünmür). Linki WhatsApp statusunda, Instagram-da paylaşın.</p>
              {settings.enabled && link && (
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <code className="rounded-lg bg-slate-100 px-3 py-1.5 text-sm text-navy-800">{link}</code>
                  <Button variant="outline" onClick={async () => { try { await navigator.clipboard.writeText(link); setCopied(true); setTimeout(() => setCopied(false), 2000); } catch { /* ignore */ } }}>{copied ? "Kopyalandı ✓" : "Kopyala"}</Button>
                  <a href={`https://wa.me/?text=${encodeURIComponent(`${me?.storeName ?? "Mağazamız"} — mallarımıza baxın və sifariş verin: ${link}`)}`} target="_blank" rel="noreferrer"
                    className="rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700">WhatsApp-da paylaş</a>
                  <Link href={`/k/${settings.slug}`} target="_blank" className="text-sm font-medium text-gold-600 hover:underline">Aç →</Link>
                </div>
              )}
              <div className="mt-3 max-w-md">
                <Field label="Kataloqda qeyd (istəyə bağlı)"><Input maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Məs.: Bakı daxili çatdırılma pulsuzdur" /></Field>
              </div>
            </div>
            <div className="flex gap-2">
              {settings.enabled
                ? <><Button variant="outline" onClick={() => toggle(true)}>Qeydi saxla</Button><Button variant="danger" onClick={() => toggle(false)}>Kataloqu bağla</Button></>
                : <Button variant="gold" onClick={() => toggle(true)}>Kataloqu aç</Button>}
            </div>
          </div>
        </Card>
      )}

      <div className="mb-4 flex gap-1.5">
        {([["open", "Açıq sifarişlər"], ["all", "Hamısı"]] as const).map(([k, l]) => (
          <button key={k} onClick={() => setFilter(k)} aria-pressed={filter === k}
            className={`rounded-full px-3 py-1.5 text-xs font-medium ${filter === k ? "bg-navy-800 text-white" : "bg-slate-100 text-slate-600"}`}>{l}</button>
        ))}
      </div>
      {!orders ? <Spinner /> : shown.length === 0 ? <Card><EmptyState title="Sifariş yoxdur" text={settings?.enabled ? "Kataloq linkini müştərilərlə paylaşın." : "Əvvəlcə onlayn kataloqu açın."} /></Card> : (
        <div className="grid gap-4 lg:grid-cols-2">
          {shown.map((o) => {
            const wa = waLink(o.customerPhone, `Salam, ${o.customerName}! ${o.number} nömrəli sifarişiniz qəbul edildi.`);
            return (
              <Card key={o.id}>
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="font-semibold text-navy-800">{o.number} · {o.customerName}</p>
                    <p className="text-sm text-slate-500">{o.customerPhone} · {formatDateTime(o.createdAt)}</p>
                    {o.address && <p className="text-sm text-slate-600">📍 {o.address}</p>}
                    {o.note && <p className="text-sm italic text-slate-600">“{o.note}”</p>}
                  </div>
                  <Badge tone={STATUS[o.status].t}>{STATUS[o.status].l}</Badge>
                </div>
                <ul className="my-3 divide-y divide-slate-100 text-sm">
                  {o.items.map((i, k) => <li key={k} className="flex justify-between py-1"><span>{i.name} × {Number(i.quantity)}</span><span className="tabular-nums">{formatMoney(i.lineTotal)}</span></li>)}
                  <li className="flex justify-between py-1.5 font-semibold"><span>Cəmi</span><span>{formatMoney(o.total)}</span></li>
                </ul>
                {(o.status === "NEW" || o.status === "CONFIRMED") && (
                  <div className="flex flex-wrap gap-2">
                    <Link href={`/satis?order=${o.id}`} className="rounded-xl bg-navy-800 px-4 py-2 text-sm font-semibold text-white hover:bg-navy-700">Kassaya köçür</Link>
                    {o.status === "NEW" && <Button variant="outline" onClick={() => status(o, "CONFIRMED")}>Təsdiqlə</Button>}
                    {wa && <a href={wa} target="_blank" rel="noreferrer" className="rounded-xl bg-emerald-50 px-4 py-2 text-sm font-medium text-emerald-700 hover:bg-emerald-100">WhatsApp</a>}
                    <Button variant="ghost" onClick={() => status(o, "CANCELLED")}>Ləğv et</Button>
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
