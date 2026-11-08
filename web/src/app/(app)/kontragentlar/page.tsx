"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatMoney } from "@/lib/format";
import type { Party } from "@/lib/types";

type Tab = "customers" | "companies";
type FormState = { name: string; phone: string; note: string };
const EMPTY: FormState = { name: "", phone: "", note: "" };

export default function PartiesPage() {
  const { can, isOwner } = useAuth();
  const canCustomers = isOwner || can("CUSTOMER_DEBTS");
  const canCompanies = isOwner || can("COMPANY_RECEIVABLES") || can("COMPANY_PAYABLES");
  const [tab, setTab] = useState<Tab>(canCustomers ? "customers" : "companies");
  const [q, setQ] = useState("");
  const [applied, setApplied] = useState("");
  const [items, setItems] = useState<Party[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<Party | "new" | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    setItems(null);
    const qs = applied ? `?q=${encodeURIComponent(applied)}` : "";
    api<Party[]>(`/api/${tab}${qs}`)
      .then(setItems)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Siyahı yüklənmədi"));
  }, [tab, applied, reload]);

  const label = tab === "customers" ? "Müştəri" : "Şirkət";

  return (
    <>
      <PageHeader
        title="Müştəri və şirkətlər"
        subtitle="Borc və alacaq qeydlərində istifadə olunan tərəflər"
        actions={<Button variant="gold" onClick={() => setEditing("new")}>+ {label} əlavə et</Button>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}

      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="inline-flex rounded-xl bg-white p-1 shadow-sm ring-1 ring-slate-200" role="tablist">
          {canCustomers && (
            <button role="tab" aria-selected={tab === "customers"} onClick={() => setTab("customers")} className={`rounded-lg px-4 py-2 text-sm font-medium transition ${tab === "customers" ? "bg-navy-800 text-white shadow" : "text-slate-600 hover:text-navy-800"}`}>Müştərilər</button>
          )}
          {canCompanies && (
            <button role="tab" aria-selected={tab === "companies"} onClick={() => setTab("companies")} className={`rounded-lg px-4 py-2 text-sm font-medium transition ${tab === "companies" ? "bg-navy-800 text-white shadow" : "text-slate-600 hover:text-navy-800"}`}>Şirkətlər</button>
          )}
        </div>
        <form onSubmit={(e: FormEvent) => { e.preventDefault(); setApplied(q.trim()); }} className="flex flex-1 gap-2">
          <Input placeholder="Ad və ya telefon üzrə axtar" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Axtarış" />
          <Button type="submit" variant="outline">Axtar</Button>
        </form>
      </div>

      <Card>
        {!items ? (
          <Spinner />
        ) : items.length === 0 ? (
          <EmptyState title={`${label} tapılmadı`} text="Yeni qeyd əlavə edin." />
        ) : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Ad</Th><Th>Telefon</Th>{tab === "customers" && <Th className="text-right">Açıq borc</Th>}<Th>Qeyd</Th><Th className="text-right">Əməliyyat</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((p) => (
                <tr key={p.id} className="transition-colors hover:bg-gold-500/5">
                  <Td className="font-medium text-navy-800">{p.name}</Td>
                  <Td>{p.phone ?? "—"}</Td>
                  {tab === "customers" && <Td className="text-right tabular-nums">{formatMoney(p.openBalance)}</Td>}
                  <Td className="max-w-[18rem] truncate text-slate-500">{p.note ?? "—"}</Td>
                  <Td className="text-right"><Button variant="ghost" onClick={() => setEditing(p)}>Redaktə</Button></Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      {editing && (
        <PartyModal
          tab={tab}
          party={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(msg) => {
            setEditing(null);
            setNotice(msg);
            setReload((r) => r + 1);
          }}
        />
      )}
    </>
  );
}

function PartyModal({ tab, party, onClose, onSaved }: { tab: Tab; party: Party | null; onClose: () => void; onSaved: (msg: string) => void }) {
  const [form, setForm] = useState<FormState>(party ? { name: party.name, phone: party.phone ?? "", note: party.note ?? "" } : EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const label = tab === "customers" ? "müştəri" : "şirkət";

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setErrors({});
    const body = { name: form.name, phone: form.phone || null, note: form.note || null };
    try {
      if (party) await api(`/api/${tab}/${party.id}`, { method: "PUT", json: body });
      else await api(`/api/${tab}`, { method: "POST", json: body });
      onSaved(party ? "Dəyişikliklər yadda saxlanıldı" : `Yeni ${label} əlavə edildi`);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrors(err.fields ?? {});
      } else setError("Yadda saxlanılmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={party ? `Redaktə — ${party.name}` : `Yeni ${label}`}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="party-form" disabled={busy}>Saxla</Button></>}
    >
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="party-form" onSubmit={submit} className="space-y-4">
        <Field label="Ad" error={errors.name}>
          <Input required maxLength={150} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </Field>
        <Field label="Telefon" error={errors.phone}>
          <Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} placeholder="+994 50 000 00 00" />
        </Field>
        <Field label="Qeyd">
          <Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </Field>
      </form>
    </Modal>
  );
}
