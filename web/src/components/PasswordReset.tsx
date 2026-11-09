"use client";

import { useState, type FormEvent } from "react";
import { Alert, Button, Card, Field, Input, Modal } from "./ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { AuthResponse } from "@/lib/types";
import { waLink } from "@/lib/whatsapp";

export interface TemporaryPassword {
  temporaryPassword: string;
  email: string;
  fullName: string;
  phone: string | null;
}

/**
 * Shows a one-time temporary password ONCE (admin / store owner) with copy and WhatsApp buttons.
 * The user must set a new password right after logging in with it.
 */
export function TemporaryPasswordModal({ result, onClose }: { result: TemporaryPassword; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const text = `Salam, ${result.fullName}! HesabPro üçün müvəqqəti şifrəniz: ${result.temporaryPassword}\nGiriş: ${result.email}\nDaxil olduqdan sonra yeni şifrə təyin etməyiniz tələb olunacaq.`;
  const wa = waLink(result.phone, text);

  async function copy() {
    try {
      await navigator.clipboard.writeText(result.temporaryPassword);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <Modal open title="Müvəqqəti şifrə yaradıldı" onClose={onClose} footer={<Button variant="primary" onClick={onClose}>Bağla</Button>}>
      <p className="text-sm text-slate-600">
        <strong className="text-navy-800">{result.fullName}</strong> ({result.email}) üçün yeni müvəqqəti şifrə:
      </p>
      <div className="my-4 flex items-center justify-between gap-3 rounded-2xl bg-navy-800 px-5 py-4">
        <span className="select-all font-mono text-2xl font-semibold tracking-wider text-gold-400">{result.temporaryPassword}</span>
        <button onClick={copy} className="rounded-lg bg-white/10 px-3 py-1.5 text-xs font-medium text-white hover:bg-white/20">
          {copied ? "Kopyalandı ✓" : "Kopyala"}
        </button>
      </div>
      {wa ? (
        <a href={wa} target="_blank" rel="noopener noreferrer" className="mb-3 block rounded-xl bg-emerald-600 px-4 py-2.5 text-center text-sm font-medium text-white hover:bg-emerald-500">
          WhatsApp ilə göndər ({result.phone})
        </a>
      ) : (
        <p className="mb-3 text-xs text-slate-500">İstifadəçinin telefon nömrəsi yoxdur — şifrəni özünüz çatdırın.</p>
      )}
      <Alert kind="info">
        Bu şifrə yalnız indi göstərilir. İstifadəçinin bütün köhnə girişləri bağlandı; bu şifrə ilə daxil olanda yeni şifrə təyin etməli olacaq.
      </Alert>
    </Modal>
  );
}

/** Full-screen step after logging in with a temporary password. */
export function ForcePasswordChange() {
  const { me, signIn, signOut } = useAuth();
  const [form, setForm] = useState({ current: "", next: "", repeat: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const min = me?.platformRole === "SUPER_ADMIN" ? 12 : 8;
  const mismatch = form.repeat.length > 0 && form.next !== form.repeat;

  async function save(e: FormEvent) {
    e.preventDefault();
    if (form.next.length < min) {
      setError(`Yeni şifrə ən azı ${min} simvol olmalıdır`);
      return;
    }
    if (mismatch) return;
    setBusy(true);
    setError(null);
    try {
      signIn(await api<AuthResponse>("/api/profile/password", { method: "PUT", json: { currentPassword: form.current, newPassword: form.next } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Şifrə dəyişdirilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas p-4">
      <Card className="w-full max-w-md">
        <h1 className="font-serif text-2xl font-semibold text-navy-800">Yeni şifrə təyin edin</h1>
        <p className="mt-1 text-sm text-slate-500">
          Siz müvəqqəti şifrə ilə daxil oldunuz. Davam etmək üçün özünüzə yeni şifrə seçin.
        </p>
        <form onSubmit={save} className="mt-6 space-y-4">
          {error && <Alert>{error}</Alert>}
          <Field label="Müvəqqəti (cari) şifrə">
            <Input type="password" required value={form.current} onChange={(e) => setForm({ ...form, current: e.target.value })} autoComplete="current-password" />
          </Field>
          <Field label="Yeni şifrə" hint={`Ən azı ${min} simvol`}>
            <Input type="password" required minLength={min} maxLength={72} value={form.next} onChange={(e) => setForm({ ...form, next: e.target.value })} autoComplete="new-password" />
          </Field>
          <Field label="Yeni şifrə (təkrar)" error={mismatch ? "Şifrələr eyni deyil" : undefined}>
            <Input type="password" required value={form.repeat} onChange={(e) => setForm({ ...form, repeat: e.target.value })} autoComplete="new-password" />
          </Field>
          <Button type="submit" variant="gold" className="w-full py-3" disabled={busy || mismatch}>{busy ? "Saxlanılır…" : "Yeni şifrəni saxla"}</Button>
          <button type="button" onClick={signOut} className="w-full text-center text-sm text-slate-500 hover:text-navy-800">Çıxış</button>
        </form>
      </Card>
    </div>
  );
}
