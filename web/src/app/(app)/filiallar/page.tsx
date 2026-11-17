"use client";

import { useEffect, useMemo, useState } from "react";
import { Alert, Badge, Button, Card, Field, Input, PageHeader, Select, Spinner, Table, Td, Th } from "@/components/ui";
import { switchBranch, type Branch } from "@/components/BranchSwitcher";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatMoney, todayBaku } from "@/lib/format";
import type { Product } from "@/lib/types";

type Day = { id: string | null; name: string; current: boolean; salesCount: number; salesTotal: number; paidCash: number; paidCard: number; credit: number; grossProfit: number; expenses: number; stockValue: number };
type Overview = { date: string; branches: Day[]; total: Day };

/** Filiallar: every branch's day side by side, switch, open a new branch, send goods between branches. */
export default function BranchesPage() {
  const [branches, setBranches] = useState<Branch[] | null>(null);
  const [date, setDate] = useState(todayBaku());
  const [overview, setOverview] = useState<Overview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadBranches = () => api<Branch[]>("/api/branches").then(setBranches).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  useEffect(() => { void loadBranches(); }, []);
  useEffect(() => { api<Overview>(`/api/branches/overview?date=${date}`).then(setOverview).catch(() => setOverview(null)); }, [date, branches]);

  if (error) return <Alert>{error}</Alert>;
  if (!branches) return <Spinner />;
  const current = branches.find((b) => b.current);

  return (
    <>
      <PageHeader title="Filiallar" subtitle="Bütün mağazalarınız bir hesabda: keçid, ümumi hesabat, mal göndərmə" />
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}

      <Card>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-serif text-lg font-semibold text-navy-800">Günün nəticəsi</h2>
          <Input type="date" value={date} max={todayBaku()} onChange={(e) => setDate(e.target.value)} className="w-auto" aria-label="Gün" />
        </div>
        {!overview ? <Spinner /> : (
          <div className="overflow-x-auto">
            <Table>
              <thead><tr><Th>Filial</Th><Th className="text-right">Çek</Th><Th className="text-right">Satış</Th><Th className="text-right">Nağd</Th><Th className="text-right">Kart</Th><Th className="text-right">Nisyə</Th><Th className="text-right">Mənfəət</Th><Th className="text-right">Xərc</Th><Th className="text-right">Anbar (alış)</Th></tr></thead>
              <tbody>
                {[...overview.branches, overview.total].map((r) => (
                  <tr key={r.id ?? "total"} className={r.id ? "" : "bg-slate-50 font-semibold"}>
                    <Td>{r.name}{r.current && <span className="ml-2"><Badge tone="navy">burada</Badge></span>}</Td>
                    <Td className="text-right tabular-nums">{r.salesCount}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(r.salesTotal)}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(r.paidCash)}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(r.paidCard)}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(r.credit)}</Td>
                    <Td className="text-right tabular-nums text-emerald-700">{formatMoney(r.grossProfit)}</Td>
                    <Td className="text-right tabular-nums text-rose-600">{formatMoney(r.expenses)}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(r.stockValue)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </div>
        )}
      </Card>

      <div className="mt-6 grid gap-6 xl:grid-cols-2">
        <Card>
          <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">Mağazalarım</h2>
          <ul className="divide-y divide-slate-100">
            {branches.map((b) => (
              <li key={b.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <p className="font-medium text-navy-800">{b.main ? "🏠" : "📍"} {b.name}</p>
                  <p className="text-xs text-slate-500">
                    {[b.address, b.phone].filter(Boolean).join(" · ") || "—"} · {b.subscriptionActive ? `abunə ${formatDate(b.subscriptionEnds)}-dək` : <span className="text-rose-600">abunə yoxdur</span>}
                  </p>
                </div>
                {b.current ? <Badge tone="navy">Hazırda buradasınız</Badge> : <Button variant="outline" onClick={() => void switchBranch(b.id)}>Bu filiala keç</Button>}
              </li>
            ))}
          </ul>
          <NewBranch onDone={(b) => { setNotice(`“${b.name}” filialı açıldı. Keçid edib malları və abunəni qurun.`); void loadBranches(); }} />
        </Card>
        {current && branches.length > 1 && (
          <TransferCard from={current} targets={branches.filter((b) => !b.current)} onDone={(m) => { setNotice(m); setDate(todayBaku()); void loadBranches(); }} />
        )}
      </div>
    </>
  );
}

function NewBranch({ onDone }: { onDone: (b: Branch) => void }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [phone, setPhone] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!open) return <div className="mt-4"><Button variant="gold" onClick={() => setOpen(true)}>+ Yeni filial</Button></div>;
  return (
    <form className="mt-4 space-y-3 rounded-2xl bg-slate-50 p-4" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      setError(null);
      try {
        const b = await api<Branch>("/api/branches", { method: "POST", json: { name, address, phone } });
        setOpen(false); setName(""); setAddress(""); setPhone("");
        onDone(b);
      } catch (err) {
        setError(err instanceof ApiError ? err.message : "Filial açılmadı");
      } finally {
        setBusy(false);
      }
    }}>
      {error && <Alert>{error}</Alert>}
      <Field label="Filialın adı"><Input required minLength={2} maxLength={150} value={name} onChange={(e) => setName(e.target.value)} placeholder="məs. Yasamal filialı" /></Field>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Ünvan"><Input value={address} onChange={(e) => setAddress(e.target.value)} /></Field>
        <Field label="Telefon"><Input value={phone} onChange={(e) => setPhone(e.target.value)} /></Field>
      </div>
      <p className="text-xs text-slate-500">Hər filialın öz malları, anbarı, kassası, müştəriləri və abunəsi olur. Malları buradan “Mal göndər” ilə köçürə bilərsiniz.</p>
      <div className="flex gap-2"><Button type="submit" variant="gold" disabled={busy}>Filial aç</Button><Button variant="ghost" onClick={() => setOpen(false)}>Ləğv et</Button></div>
    </form>
  );
}

function TransferCard({ from, targets, onDone }: { from: Branch; targets: Branch[]; onDone: (m: string) => void }) {
  const [to, setTo] = useState(targets[0]?.id ?? "");
  const [products, setProducts] = useState<Product[]>([]);
  const [q, setQ] = useState("");
  const [lines, setLines] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api<Product[]>("/api/products?active=true").then(setProducts).catch(() => undefined); }, []);
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    return products.filter((p) => p.stockQty > 0 && (!s || p.name.toLowerCase().includes(s) || p.code.toLowerCase().includes(s) || (p.barcode ?? "").includes(s))).slice(0, 30);
  }, [products, q]);
  const chosen = Object.entries(lines).filter(([, v]) => Number(v.replace(",", ".")) > 0);

  async function send() {
    setBusy(true);
    setError(null);
    try {
      const r = await api<{ toStore: string; lines: number; created: string[] }>("/api/branches/transfer", {
        method: "POST", json: { toStoreId: to, note, items: chosen.map(([productId, v]) => ({ productId, quantity: v.replace(",", ".") })) },
      });
      setLines({});
      setNote("");
      setProducts(await api<Product[]>("/api/products?active=true"));
      onDone(`${r.lines} mal “${r.toStore}” filialına göndərildi${r.created.length ? ` (orada yeni yaradıldı: ${r.created.join(", ")})` : ""}.`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Göndərilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Mal göndər</h2>
      <p className="mb-3 text-sm text-slate-500">“{from.name}” anbarından başqa filiala. Orada həmin kodlu mal yoxdursa, eyni ad və qiymətlə yaradılır.</p>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Hara"><Select value={to} onChange={(e) => setTo(e.target.value)}>{targets.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</Select></Field>
        <Field label="Axtar"><Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="ad, kod, barkod" /></Field>
      </div>
      <ul className="mt-3 max-h-80 divide-y divide-slate-100 overflow-y-auto">
        {shown.map((p) => (
          <li key={p.id} className="flex items-center justify-between gap-3 py-2 text-sm">
            <span className="min-w-0 truncate">{p.name} <span className="text-xs text-slate-400">· {Number(p.stockQty)} {p.unit}</span></span>
            <Input inputMode="decimal" placeholder="0" value={lines[p.id] ?? ""} aria-label={`${p.name} miqdarı`}
              onChange={(e) => setLines({ ...lines, [p.id]: e.target.value.replace(/[^\d.,]/g, "") })} className="w-24 text-right" />
          </li>
        ))}
      </ul>
      <div className="mt-3"><Field label="Qeyd (istəyə bağlı)"><Input value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} /></Field></div>
      <div className="mt-3 flex items-center justify-between gap-3">
        <span className="text-sm text-slate-500">{chosen.length} mal seçilib</span>
        <Button variant="gold" disabled={busy || !to || chosen.length === 0} onClick={() => void send()}>Göndər</Button>
      </div>
    </Card>
  );
}
