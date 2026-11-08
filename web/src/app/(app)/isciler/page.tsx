"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { PERMISSION_LABEL } from "@/lib/format";
import type { Employee, Permission } from "@/lib/types";

const ALL_PERMISSIONS = Object.keys(PERMISSION_LABEL) as Permission[];
const EMPLOYEE_PERMISSIONS = ALL_PERMISSIONS;

function PermissionChecks({ value, onChange, options }: { value: Permission[]; onChange: (v: Permission[]) => void; options: Permission[] }) {
  return (
    <fieldset className="space-y-2">
      <legend className="mb-2 text-sm font-medium text-slate-700">İcazələr</legend>
      {options.map((p) => {
        const checked = value.includes(p);
        return (
          <label key={p} className={`flex cursor-pointer items-center gap-3 rounded-xl border px-4 py-3 text-sm transition ${checked ? "border-gold-500 bg-gold-500/5" : "border-slate-200 hover:border-slate-300"}`}>
            <input
              type="checkbox"
              className="h-4 w-4 accent-navy-800"
              checked={checked}
              onChange={() => onChange(checked ? value.filter((x) => x !== p) : [...value, p])}
            />
            <span className="text-navy-800">{PERMISSION_LABEL[p]}</span>
          </label>
        );
      })}
    </fieldset>
  );
}

export default function EmployeesPage() {
  const { isOwner } = useAuth();
  const [items, setItems] = useState<Employee[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Employee | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (!isOwner) return;
    api<Employee[]>("/api/employees")
      .then(setItems)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Siyahı yüklənmədi"));
  }, [isOwner, reload]);

  async function toggleActive(e: Employee) {
    try {
      await api(`/api/employees/${e.memberId}`, { method: "PUT", json: { permissions: e.permissions, active: !e.active } });
      setNotice(e.active ? "İşçi deaktiv edildi" : "İşçi aktivləşdirildi");
      setReload((r) => r + 1);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Dəyişiklik saxlanılmadı");
    }
  }

  if (!isOwner) return <Card><EmptyState title="Yalnız mağaza sahibi işçiləri idarə edə bilər" /></Card>;

  return (
    <>
      <PageHeader
        title="İşçilər"
        subtitle="Hər işçiyə yalnız lazım olan bölmələr üzrə icazə verin"
        actions={<Button variant="gold" onClick={() => setCreating(true)}>+ İşçi əlavə et</Button>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}

      <Card>
        {!items ? (
          <Spinner />
        ) : items.length === 0 ? (
          <EmptyState title="İşçi yoxdur" text="Kassir və ya mühasib hesabı yaradın." />
        ) : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Ad</Th><Th>E-poçt</Th><Th>İcazələr</Th><Th>Status</Th><Th className="text-right">Əməliyyat</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((e) => (
                <tr key={e.memberId} className="transition-colors hover:bg-gold-500/5">
                  <Td className="font-medium text-navy-800">{e.fullName}</Td>
                  <Td className="text-slate-500">{e.email}</Td>
                  <Td>
                    <div className="flex flex-wrap gap-1.5">
                      {e.permissions.length === 0 && <span className="text-xs text-slate-400">İcazə yoxdur</span>}
                      {e.permissions.map((p) => <Badge key={p} tone="navy">{PERMISSION_LABEL[p]}</Badge>)}
                    </div>
                  </Td>
                  <Td><Badge tone={e.active ? "emerald" : "rose"}>{e.active ? "Aktiv" : "Deaktiv"}</Badge></Td>
                  <Td className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" onClick={() => setEditing(e)}>İcazələr</Button>
                      <Button variant="outline" onClick={() => toggleActive(e)}>{e.active ? "Deaktiv et" : "Aktiv et"}</Button>
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      {creating && (
        <CreateEmployeeModal
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setNotice("İşçi hesabı yaradıldı");
            setReload((r) => r + 1);
          }}
        />
      )}
      {editing && (
        <EditPermissionsModal
          employee={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setNotice("İcazələr yeniləndi");
            setReload((r) => r + 1);
          }}
        />
      )}
    </>
  );
}

function CreateEmployeeModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ fullName: "", email: "", password: "", phone: "" });
  const [perms, setPerms] = useState<Permission[]>(["CUSTOMER_DEBTS"]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setErrors({});
    try {
      await api("/api/employees", { method: "POST", json: { ...form, phone: form.phone || null, permissions: perms } });
      onSaved();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrors(err.fields ?? {});
      } else setError("Hesab yaradılmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title="Yeni işçi hesabı"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="employee-form" disabled={busy || perms.length === 0}>Yarat</Button></>}
    >
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="employee-form" onSubmit={submit} className="space-y-4">
        <Field label="Ad, soyad" error={errors.fullName}><Input required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} /></Field>
        <Field label="E-poçt (giriş üçün)" error={errors.email}><Input type="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
        <Field label="Müvəqqəti şifrə" hint="Ən azı 8 simvol. İşçi ilk girişdən sonra dəyişdirə bilər." error={errors.password}>
          <Input type="password" required minLength={8} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} autoComplete="new-password" />
        </Field>
        <Field label="Telefon"><Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
        <PermissionChecks value={perms} onChange={setPerms} options={EMPLOYEE_PERMISSIONS} />
        {perms.length === 0 && <p className="text-xs text-rose-600">Ən azı bir icazə seçin</p>}
      </form>
    </Modal>
  );
}

function EditPermissionsModal({ employee, onClose, onSaved }: { employee: Employee; onClose: () => void; onSaved: () => void }) {
  const [perms, setPerms] = useState<Permission[]>(employee.permissions);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api(`/api/employees/${employee.memberId}`, { method: "PUT", json: { permissions: perms, active: employee.active } });
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Yadda saxlanılmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`İcazələr — ${employee.fullName}`}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="perm-form" disabled={busy}>Yadda saxla</Button></>}
    >
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="perm-form" onSubmit={submit}>
        <PermissionChecks value={perms} onChange={setPerms} options={EMPLOYEE_PERMISSIONS} />
      </form>
    </Modal>
  );
}
