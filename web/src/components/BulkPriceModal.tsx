"use client";

import { useState } from "react";
import { Alert, Button, Field, Input, Modal, Select } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import type { Party, ProductCategory } from "@/lib/types";

/** Toplu qiymət dəyişikliyi: one price of many products at once (by %, by ₼ or a fixed price), optionally rounded. */
export function BulkPriceModal({ categories, suppliers, onClose, onDone }: {
  categories: ProductCategory[]; suppliers: Party[]; onClose: () => void; onDone: (msg: string) => void;
}) {
  const [field, setField] = useState("SALE");
  const [mode, setMode] = useState("PERCENT");
  const [value, setValue] = useState("");
  const [roundTo, setRoundTo] = useState("0.01");
  const [scope, setScope] = useState<"all" | "category" | "supplier">("category");
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function apply() {
    const v = Number(value.replace(",", "."));
    if (!value.trim() || Number.isNaN(v)) { setError("Dəyəri yazın"); return; }
    if (scope !== "all" && !target) { setError(scope === "category" ? "Kateqoriya seçin" : "Təchizatçı seçin"); return; }
    setBusy(true);
    setError(null);
    try {
      const r = await api<{ changed: number; unchanged: number }>("/api/products/bulk-price", {
        method: "POST",
        json: { field, mode, value: v.toFixed(2), roundTo, categoryId: scope === "category" ? target : null, supplierId: scope === "supplier" ? target : null },
      });
      onDone(`${r.changed} məhsulun qiyməti dəyişdi${r.unchanged ? `, ${r.unchanged} dəyişmədi` : ""}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }

  const example = (() => {
    const v = Number(value.replace(",", "."));
    if (!value.trim() || Number.isNaN(v)) return null;
    const base = 10;
    const raw = mode === "PERCENT" ? base * (1 + v / 100) : mode === "AMOUNT" ? base + v : v;
    const step = Number(roundTo);
    return Math.max(0, Math.round(raw / step) * step).toFixed(2);
  })();

  return (
    <Modal open title="Qiymətləri toplu dəyiş" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button variant="gold" disabled={busy} onClick={apply}>{busy ? "Dəyişdirilir…" : "Tətbiq et"}</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Hansı qiymət">
          <Select value={field} onChange={(e) => setField(e.target.value)}>
            <option value="SALE">Top 2 — pərakəndə satış</option>
            <option value="WHOLESALE">Top 1 — topdan satış</option>
            <option value="PURCHASE">Alış qiyməti</option>
          </Select>
        </Field>
        <Field label="Necə">
          <Select value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="PERCENT">Faizlə (+10 və ya −5)</option>
            <option value="AMOUNT">Manatla (+0.50 və ya −1)</option>
            <option value="SET">Yeni qiymət təyin et</option>
          </Select>
        </Field>
        <Field label={mode === "PERCENT" ? "Faiz (%)" : "Məbləğ (₼)"}>
          <Input inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} placeholder={mode === "PERCENT" ? "10" : "0.50"} autoFocus />
        </Field>
        <Field label="Yuvarlaqlaşdır">
          <Select value={roundTo} onChange={(e) => setRoundTo(e.target.value)}>
            <option value="0.01">Qəpiyə qədər</option><option value="0.05">5 qəpik</option>
            <option value="0.1">10 qəpik</option><option value="1">Tam manat</option>
          </Select>
        </Field>
        <Field label="Hansı məhsullar">
          <Select value={scope} onChange={(e) => { setScope(e.target.value as typeof scope); setTarget(""); }}>
            <option value="category">Kateqoriya</option><option value="supplier">Təchizatçı</option><option value="all">Bütün aktiv məhsullar</option>
          </Select>
        </Field>
        {scope !== "all" && (
          <Field label={scope === "category" ? "Kateqoriya" : "Təchizatçı"}>
            <Select value={target} onChange={(e) => setTarget(e.target.value)}>
              <option value="">— seçin —</option>
              {(scope === "category" ? categories : suppliers).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </Select>
          </Field>
        )}
      </div>
      {example && <p className="mt-3 rounded-xl bg-slate-50 px-3 py-2 text-sm text-slate-600">Nümunə: 10.00 ₼ olan qiymət → <b className="text-navy-800">{example} ₼</b></p>}
    </Modal>
  );
}
