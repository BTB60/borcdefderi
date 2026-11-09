"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";
import { Alert, Button, Field, Input, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center"><Spinner /></div>}>
      <ResetForm />
    </Suspense>
  );
}

/** Opened from the "Şifrəni unutdum" e-mail: /sifre-berpa?token=… */
function ResetForm() {
  const token = useSearchParams().get("token") ?? "";
  const [form, setForm] = useState({ next: "", repeat: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const mismatch = form.repeat.length > 0 && form.next !== form.repeat;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (mismatch) return;
    if (form.next.length < 8) {
      setError("Yeni şifrə ən azı 8 simvol olmalıdır");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api("/api/auth/reset-password", { method: "POST", json: { token, newPassword: form.next } });
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Şifrə dəyişdirilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas p-6">
      <div className="page-enter w-full max-w-md">
        <div className="mb-8 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-navy-800 font-serif text-lg font-bold text-gold-500">H</div>
          <span className="font-serif text-lg font-semibold text-navy-800">HesabPro</span>
        </div>
        <h1 className="font-serif text-3xl font-semibold text-navy-800">Yeni şifrə</h1>

        {!token ? (
          <div className="mt-8"><Alert>Link natamamdır. E-poçtdakı linki tam açın və ya <Link className="underline" href="/sifre-unutdum">yenisini istəyin</Link>.</Alert></div>
        ) : done ? (
          <div className="mt-8 space-y-4">
            <Alert kind="success">Şifrəniz dəyişdirildi. Bütün cihazlarda köhnə girişlər bağlandı.</Alert>
            <Link href="/login" className="block rounded-xl bg-navy-800 px-4 py-3 text-center text-sm font-medium text-white hover:bg-navy-700">Daxil ol</Link>
          </div>
        ) : (
          <form onSubmit={submit} className="mt-8 space-y-5">
            {error && (
              <Alert>
                {error} <Link className="underline" href="/sifre-unutdum">Yeni link istə</Link>
              </Alert>
            )}
            <Field label="Yeni şifrə" hint="Ən azı 8 simvol">
              <Input type="password" required minLength={8} maxLength={72} value={form.next} onChange={(e) => setForm({ ...form, next: e.target.value })} autoComplete="new-password" />
            </Field>
            <Field label="Yeni şifrə (təkrar)" error={mismatch ? "Şifrələr eyni deyil" : undefined}>
              <Input type="password" required value={form.repeat} onChange={(e) => setForm({ ...form, repeat: e.target.value })} autoComplete="new-password" />
            </Field>
            <Button type="submit" variant="gold" className="w-full py-3" disabled={busy || mismatch}>{busy ? "Saxlanılır…" : "Şifrəni yenilə"}</Button>
          </form>
        )}
      </div>
    </main>
  );
}
