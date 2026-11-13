"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, PageHeader, Select, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, api, download } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney } from "@/lib/format";
import type { Product, StockCountDetail, StockCountLine, StockCountTotals } from "@/lib/types";

const q = (n: number) => Number(n).toLocaleString("az-AZ", { maximumFractionDigits: 3 });
const signed = (n: number) => (n > 0 ? "+" : "") + q(n);
const signedMoney = (n: number) => (n > 0 ? "+" : "") + formatMoney(n);

/** Same rules as the server: only counted lines enter the totals; value at the line's purchase price. */
function totalsOf(lines: StockCountLine[]): StockCountTotals {
  const t: StockCountTotals = { totalItems: lines.length, countedItems: 0, matchedItems: 0, surplusItems: 0, shortageItems: 0, surplusQty: 0, shortageQty: 0, surplusValue: 0, shortageValue: 0, netValue: 0 };
  for (const l of lines) {
    if (l.countedQty == null || l.difference == null) continue;
    t.countedItems++;
    const v = l.differenceValue ?? 0;
    if (l.difference > 0) { t.surplusItems++; t.surplusQty += l.difference; t.surplusValue += v; }
    else if (l.difference < 0) { t.shortageItems++; t.shortageQty -= l.difference; t.shortageValue -= v; }
    else t.matchedItems++;
  }
  t.surplusValue = Math.round(t.surplusValue * 100) / 100;
  t.shortageValue = Math.round(t.shortageValue * 100) / 100;
  t.netValue = Math.round((t.surplusValue - t.shortageValue) * 100) / 100;
  return t;
}

/** One stock count: enter counted quantities while it is open; afterwards it is the count report (+/− per product). */
export default function StockCountPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { isOwner, can } = useAuth();
  const [data, setData] = useState<StockCountDetail | null>(null);
  const [search, setSearch] = useState("");
  const [show, setShow] = useState<"ALL" | "TODO" | "DIFF">("ALL");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, confirmDialog] = useConfirm();
  const inputs = useRef<Record<string, HTMLInputElement | null>>({});
  const manage = isOwner || can("INVENTORY");

  useEffect(() => {
    if (!manage) return;
    api<StockCountDetail>(`/api/stock-counts/${id}`).then(setData).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [id, manage]);

  const open = data?.count.status === "IN_PROGRESS";
  const totals = useMemo(() => (data ? (open ? totalsOf(data.lines) : data.count.totals) : null), [data, open]);
  const visible = useMemo(() => {
    if (!data) return [];
    const s = search.trim().toLowerCase();
    return data.lines.filter((l) => {
      if (show === "TODO" && l.countedQty != null) return false;
      if (show === "DIFF" && (l.difference == null || l.difference === 0)) return false;
      if (!open && show === "ALL" && l.countedQty == null) return false;
      return !s || l.name.toLowerCase().includes(s) || l.code.toLowerCase().includes(s) || l.barcode === search.trim();
    });
  }, [data, search, show, open]);

  if (!manage) return <Card><EmptyState title="Anbar sayımı üçün icazəniz yoxdur" /></Card>;
  if (error && !data) return <Alert>{error}</Alert>;
  if (!data || !totals) return <Spinner />;
  const c = data.count;

  function replaceLine(line: StockCountLine) {
    setData((d) => {
      if (!d) return d;
      const exists = d.lines.some((l) => l.productId === line.productId);
      const lines = exists ? d.lines.map((l) => (l.productId === line.productId ? line : l)) : [...d.lines, line].sort((a, b) => a.name.localeCompare(b.name, "az"));
      return { ...d, lines };
    });
  }

  async function save(line: StockCountLine, raw: string) {
    const value = raw.trim() === "" ? null : raw.trim().replace(",", ".");
    if (value === (line.countedQty == null ? null : String(line.countedQty))) return;
    if (value != null && (Number.isNaN(Number(value)) || Number(value) < 0)) { setError("Sayılan miqdar 0 və ya daha çox olmalıdır"); return; }
    setError(null);
    try {
      replaceLine(await api<StockCountLine>(`/api/stock-counts/${id}/lines/${line.productId}`, { method: "PUT", json: { countedQty: value } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Saxlanmadı");
    }
  }

  /** Enter in the search box: an exact barcode / code jumps to that product, adding it to the count if needed. */
  async function findExact(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== "Enter" || !search.trim() || !data) return;
    const code = search.trim();
    let line = data.lines.find((l) => l.barcode === code || l.code.toLowerCase() === code.toLowerCase());
    if (!line) {
      try {
        const p = await api<Product>(`/api/products/by-code/${encodeURIComponent(code)}`);
        line = data.lines.find((l) => l.productId === p.id)
          ?? await api<StockCountLine>(`/api/stock-counts/${id}/lines/${p.id}`, { method: "PUT", json: { countedQty: null } });
        replaceLine(line);
      } catch (err) {
        if (visible.length === 1) line = visible[0];
        else { setError(err instanceof ApiError ? err.message : "Məhsul tapılmadı"); return; }
      }
    }
    const target = line;
    setShow("ALL");
    setSearch(target.name);
    setTimeout(() => { inputs.current[target.productId]?.focus(); inputs.current[target.productId]?.select(); }, 50);
  }

  async function complete() {
    if (!totals) return;
    const ok = await confirm(<>
      Sayım tamamlansın? Sayılan {totals.countedItems} məhsulun fərqi anbar qalığına yazılacaq:
      <br />artıq <strong className="text-emerald-700">{signedMoney(totals.surplusValue)}</strong>, kəsir <strong className="text-rose-600">−{formatMoney(totals.shortageValue)}</strong>.
      {totals.totalItems > totals.countedItems && <><br />Sayılmayan {totals.totalItems - totals.countedItems} məhsula toxunulmayacaq.</>}
      <br />Tamamlanmış sayım dəyişdirilə bilməz.
    </>, { confirmLabel: "Tamamla" });
    if (!ok) return;
    setBusy(true);
    try {
      setData(await api<StockCountDetail>(`/api/stock-counts/${id}/complete`, { method: "POST" }));
      setShow("ALL");
      setNotice("Sayım tamamlandı, qalıqlar düzəldildi");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Tamamlanmadı");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!(await confirm(<>Sayım {c.number} silinsin? Anbar qalıqları dəyişməyəcək.</>, { danger: true, confirmLabel: "Sil" }))) return;
    try {
      await api(`/api/stock-counts/${id}`, { method: "DELETE" });
      router.push("/anbar/sayim");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Silinmədi");
    }
  }

  function exportAs(format: "xlsx" | "pdf") {
    download(`/api/stock-counts/${id}/export?format=${format}`, `sayim-${c.number}.${format}`)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Fayl hazırlanmadı"));
  }

  return (
    <>
      <PageHeader title={`Sayım ${c.number}`}
        subtitle={`${c.categoryName ?? "Bütün məhsullar"} · başladı ${formatDateTime(c.createdAt)}, ${c.createdByName}${c.completedAt ? ` · tamamladı ${formatDateTime(c.completedAt)}, ${c.completedByName}` : ""}${c.note ? ` · ${c.note}` : ""}`}
        actions={<div className="flex flex-wrap gap-2">
          <Link href="/anbar/sayim" className="inline-flex items-center rounded-xl px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100">← Sayımlar</Link>
          <Button variant="outline" onClick={() => exportAs("xlsx")}>Excel</Button>
          <Button variant="outline" onClick={() => exportAs("pdf")}>PDF</Button>
          {open && <Button variant="ghost" className="text-rose-600" onClick={remove}>Sil</Button>}
          {open && <Button variant="gold" disabled={busy || totals.countedItems === 0} onClick={complete}>Tamamla</Button>}
        </div>} />
      <div className="mb-4">{open ? <Badge tone="amber">Davam edir — qalıqlar “Tamamla” basılanda düzəlir</Badge> : <Badge tone="emerald">Tamamlanıb</Badge>}</div>
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <section className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Sayılıb" value={`${totals.countedItems} / ${totals.totalItems}`} tone="navy" hint={`${totals.matchedItems} məhsul uyğundur`} />
        <StatCard label="Artıq (+)" value={signedMoney(totals.surplusValue)} tone="emerald" hint={`${totals.surplusItems} məhsul · ${q(totals.surplusQty)} vahid`} />
        <StatCard label="Kəsir (−)" value={totals.shortageValue ? `−${formatMoney(totals.shortageValue)}` : formatMoney(0)} tone="rose" hint={`${totals.shortageItems} məhsul · ${q(totals.shortageQty)} vahid`} />
        <StatCard label="Xalis fərq" value={signedMoney(totals.netValue)} tone="gold" hint="alış qiyməti ilə" />
      </section>
      <Card>
        <div className="mb-4 grid gap-3 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <Input placeholder={open ? "Ad, kod və ya barkod (skaner + Enter)" : "Ad, kod və ya barkod"} value={search} autoFocus={open}
              onChange={(e) => setSearch(e.target.value)} onKeyDown={open ? findExact : undefined} aria-label="Axtarış" />
          </div>
          <Select value={show} onChange={(e) => setShow(e.target.value as typeof show)} aria-label="Göstər">
            <option value="ALL">{open ? "Hamısı" : "Sayılan məhsullar"}</option>
            {open && <option value="TODO">Sayılmayanlar</option>}
            <option value="DIFF">Yalnız fərqi olanlar</option>
          </Select>
        </div>
        {visible.length === 0 ? <EmptyState title="Məhsul yoxdur" /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr>
              <Th>Məhsul</Th><Th className="text-right">Sistem</Th><Th className="text-right">Sayılan</Th><Th className="text-right">Fərq</Th>
              <Th className="text-right">Fərq (₼)</Th><Th>Sayan</Th>
            </tr></thead>
            <tbody className="divide-y divide-slate-100">
              {visible.map((l) => (
                <tr key={l.productId}>
                  <Td>
                    <span className="font-medium text-navy-800">{l.name}</span>
                    <div className="text-xs text-slate-400">{l.code}{l.barcode ? ` · ${l.barcode}` : ""} · {formatMoney(l.unitCost)}/{l.unit}</div>
                  </Td>
                  <Td className="text-right tabular-nums">{q(l.systemQty)} {l.unit}</Td>
                  <Td className="text-right">
                    {open ? (
                      <Input key={`${l.productId}-${l.countedQty}`} ref={(el) => { inputs.current[l.productId] = el; }}
                        className="ml-auto w-28 text-right tabular-nums" type="number" min="0" step="0.001" inputMode="decimal"
                        defaultValue={l.countedQty ?? ""} placeholder="—" aria-label={`${l.name} sayılan miqdar`}
                        onBlur={(e) => save(l, e.target.value)}
                        onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }} />
                    ) : <span className="tabular-nums">{l.countedQty == null ? "—" : `${q(l.countedQty)} ${l.unit}`}</span>}
                  </Td>
                  <Td className={`text-right font-semibold tabular-nums ${tone(l.difference)}`}>{l.difference == null ? "—" : signed(l.difference)}</Td>
                  <Td className={`text-right tabular-nums ${tone(l.difference)}`}>{l.differenceValue == null ? "—" : signedMoney(l.differenceValue)}</Td>
                  <Td className="text-xs text-slate-500">{l.countedByName ? <>{l.countedByName}<div className="text-slate-400">{formatDateTime(l.countedAt)}</div></> : "—"}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {confirmDialog}
    </>
  );
}

function tone(d: number | null) {
  return d == null || d === 0 ? "text-slate-500" : d > 0 ? "text-emerald-700" : "text-rose-600";
}
