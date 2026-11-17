"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, Field, Input, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";

type Helper = { id: string; email: string; fullName: string; phone: string | null; enabled: boolean; createdAt: string; lastLoginAt: string | null };

/**
 * Köməkçi adminlər (platform admin only): a helper login that can see stores, approve / reject payment requests,
 * remind stores and keep notes — nothing about packages, money, settings, users or deleting data.
 */
export function SupportAdminsCard() {
  const [list, setList] = useState<Helper[]>([]);
  const [form, setForm] = useState({ email: "", fullName: "", phone: "" });
  const [created, setCreated] = useState<{ email: string; temporaryPassword: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api<Helper[]>("/api/admin/support-admins").then(setList).catch(() => setList([]));
  useEffect(() => { load(); }, []);

  async function add(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<{ email: string; temporaryPassword: string }>("/api/admin/support-admins", { method: "POST", json: { ...form, phone: form.phone || null } });
      setCreated(r);
      setForm({ email: "", fullName: "", phone: "" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Yaradılmadı");
    } finally {
      setBusy(false);
    }
  }

  async function toggle(h: Helper) {
    await api(`/api/admin/support-admins/${h.id}/enabled`, { method: "POST", json: { enabled: !h.enabled } }).catch(() => undefined);
    load();
  }

  return (
    <Card className="mb-6">
      <h2 className="font-serif text-lg font-semibold text-navy-800">Köməkçi adminlər</h2>
      <p className="mb-4 text-sm text-slate-500">Köməkçi mağazalara baxa, ödəniş sorğularını təsdiqləyə / rədd edə, xatırlatma göndərə və qeyd yaza bilər. Paketlər, gəlir, ayarlar, istifadəçilər və məlumat silmə ona bağlıdır.</p>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      {created && (
        <div className="mb-4"><Alert kind="success">
          <b>{created.email}</b> üçün birdəfəlik şifrə: <code className="rounded bg-white px-2 py-0.5 font-mono">{created.temporaryPassword}</code>
          <span className="mt-1 block text-xs">Şifrəni köməkçiyə özünüz çatdırın — bir daha göstərilməyəcək. İlk girişdə yeni şifrə qoyacaq.</span>
        </Alert></div>
      )}
      <form onSubmit={add} className="mb-4 grid gap-3 sm:grid-cols-[1fr_1fr_180px_auto] sm:items-end">
        <Field label="E-poçt"><Input type="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
        <Field label="Ad, soyad"><Input required maxLength={150} value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} /></Field>
        <Field label="Telefon"><Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
        <Button type="submit" disabled={busy}>Əlavə et</Button>
      </form>
      {list.length > 0 && (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Köməkçi</Th><Th>Son giriş</Th><Th>Status</Th><Th className="text-right">Əməliyyat</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {list.map((h) => (
              <tr key={h.id}>
                <Td><div className="font-medium text-navy-800">{h.fullName}</div><div className="text-xs text-slate-400">{h.email}{h.phone ? ` · ${h.phone}` : ""}</div></Td>
                <Td className="text-sm text-slate-500">{h.lastLoginAt ? formatDateTime(h.lastLoginAt) : "—"}</Td>
                <Td><Badge tone={h.enabled ? "emerald" : "slate"}>{h.enabled ? "Aktiv" : "Söndürülüb"}</Badge></Td>
                <Td className="text-right"><Button variant="outline" onClick={() => toggle(h)}>{h.enabled ? "Söndür" : "Aktiv et"}</Button></Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}
