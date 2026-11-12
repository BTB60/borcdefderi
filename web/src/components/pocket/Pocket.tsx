"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Button, Field, Input, Modal } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatMoney } from "@/lib/format";

export type PocketEntry = { id: string; date: string; kind: "IN" | "OUT"; amount: number; note: string | null; createdAt: string };
export type PocketDay = { date: string; openingBalance: number; added: number; spent: number; closingBalance: number; entries: PocketEntry[] };
export type PocketDaySummary = { date: string; added: number; spent: number; closingBalance: number };

const SUGGEST = { IN: ["Səhər", "Maaş", "Bankdan"], OUT: ["Yemək", "Taksi", "Yanacaq", "Market", "Telefon"] } as const;

/** "Cibə pul qoy" / "Cibdən xərclə" form. */
export function PocketEntryModal({ kind, date, onClose, onSaved }: { kind: "IN" | "OUT"; date?: string; onClose: () => void; onSaved: () => void }) {
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isIn = kind === "IN";

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    const value = Number(amount.replace(",", "."));
    if (!Number.isFinite(value) || value <= 0) {
      setError("Məbləği düzgün yazın");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await api("/api/pocket", { method: "POST", json: { kind, amount: value.toFixed(2), note: note.trim() || undefined, date } });
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Yadda saxlanmadı");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open
      title={isIn ? "Cibə pul qoy" : "Cibdən xərclə"}
      onClose={onClose}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>Ləğv et</Button>
          <Button variant={isIn ? "primary" : "danger"} className={isIn ? "bg-emerald-600 hover:bg-emerald-700" : undefined} disabled={saving} onClick={() => submit()}>
            {saving ? "Saxlanılır…" : "Yadda saxla"}
          </Button>
        </>
      }
    >
      <form onSubmit={submit} className="space-y-4">
        {error && <Alert>{error}</Alert>}
        <Field label="Məbləğ (₼)">
          <Input autoFocus inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.,]/g, ""))} placeholder="0.00" />
        </Field>
        <Field label={isIn ? "Qeyd (istəyə görə)" : "Nəyə xərclədiniz?"}>
          <Input maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <div className="flex flex-wrap gap-2">
          {SUGGEST[kind].map((s) => (
            <button key={s} type="button" onClick={() => setNote(s)} className="rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600 hover:border-gold-500">
              {s}
            </button>
          ))}
        </div>
      </form>
    </Modal>
  );
}

/** Panel card: what is in the pocket now, with "+ Pul qoy" and "− Xərclə". Hidden if the API is not available. */
export function PocketCard() {
  const [day, setDay] = useState<PocketDay | null>(null);
  const [failed, setFailed] = useState(false);
  const [kind, setKind] = useState<"IN" | "OUT" | null>(null);

  const load = useCallback(() => {
    api<PocketDay>("/api/pocket").then(setDay).catch(() => setFailed(true));
  }, []);
  useEffect(load, [load]);

  if (failed) return null;

  return (
    <section className="mb-6 overflow-hidden rounded-3xl bg-gradient-to-br from-[#1E3A5F] to-navy-900 p-5 text-white shadow-lg" aria-label="Cib pulu">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <Link href="/cib-pulu" className="group">
          <p className="text-sm font-medium text-white/80">Cib pulu · cibimdə qalan</p>
          <p className="mt-1 font-serif text-3xl font-semibold tabular-nums text-gold-400 group-hover:underline">{day ? formatMoney(day.closingBalance) : "…"}</p>
          {day && (
            <p className="mt-1 text-xs text-white/70">
              Bu gün: <span className="text-emerald-300">+{formatMoney(day.added)}</span> qoyuldu · <span className="text-rose-300">−{formatMoney(day.spent)}</span> xərcləndi
            </p>
          )}
        </Link>
        <div className="flex gap-2">
          <button type="button" onClick={() => setKind("IN")} className="rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold transition hover:bg-emerald-700">+ Pul qoy</button>
          <button type="button" onClick={() => setKind("OUT")} className="rounded-xl bg-rose-600 px-4 py-2 text-sm font-semibold transition hover:bg-rose-700">− Xərclə</button>
        </div>
      </div>
      {kind && (
        <PocketEntryModal
          kind={kind}
          onClose={() => setKind(null)}
          onSaved={() => {
            setKind(null);
            load();
          }}
        />
      )}
    </section>
  );
}
