"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { AuthResponse } from "@/lib/types";
import { Alert, Button, Field, Input } from "@/components/ui";

export default function RegisterPage() {
  const router = useRouter();
  const { signIn } = useAuth();
  const [form, setForm] = useState({ fullName: "", email: "", password: "", phone: "", storeName: "", storePhone: "", storeAddress: "" });
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value });

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFields({});
    setBusy(true);
    try {
      const res = await api<AuthResponse>("/api/auth/register", { method: "POST", json: form });
      signIn(res);
      router.replace("/abunelik");
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setFields(err.fields ?? {});
      } else {
        setError("Serverlə əlaqə qurula bilmədi");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas p-6">
      <div className="page-enter w-full max-w-2xl rounded-3xl border border-slate-200/70 bg-white p-8 shadow-card sm:p-10">
        <div className="mb-8 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-navy-800 font-serif text-lg font-bold text-gold-500">B</div>
          <div>
            <h1 className="font-serif text-2xl font-semibold text-navy-800">Mağaza qeydiyyatı</h1>
            <p className="text-sm text-slate-500">Mağazanız üçün ayrıca, təhlükəsiz iş sahəsi yaradılır</p>
          </div>
        </div>

        <form onSubmit={onSubmit} className="grid gap-5 sm:grid-cols-2" noValidate>
          {error && <div className="sm:col-span-2"><Alert>{error}</Alert></div>}
          <div className="space-y-5 sm:col-span-1">
            <h2 className="text-xs font-semibold uppercase tracking-wider text-gold-600">Sahibi</h2>
            <Field label="Ad, soyad" error={fields.fullName}>
              <Input required value={form.fullName} onChange={set("fullName")} />
            </Field>
            <Field label="E-poçt" error={fields.email}>
              <Input type="email" required value={form.email} onChange={set("email")} autoComplete="email" />
            </Field>
            <Field label="Şifrə" hint="Ən azı 8 simvol" error={fields.password}>
              <Input type="password" required value={form.password} onChange={set("password")} autoComplete="new-password" />
            </Field>
            <Field label="Telefon" error={fields.phone}>
              <Input value={form.phone} onChange={set("phone")} placeholder="+994 50 000 00 00" />
            </Field>
          </div>
          <div className="space-y-5 sm:col-span-1">
            <h2 className="text-xs font-semibold uppercase tracking-wider text-gold-600">Mağaza</h2>
            <Field label="Mağazanın adı" error={fields.storeName}>
              <Input required value={form.storeName} onChange={set("storeName")} />
            </Field>
            <Field label="Mağazanın telefonu" error={fields.storePhone}>
              <Input value={form.storePhone} onChange={set("storePhone")} />
            </Field>
            <Field label="Ünvan">
              <Input value={form.storeAddress} onChange={set("storeAddress")} />
            </Field>
          </div>
          <div className="sm:col-span-2">
            <Button type="submit" className="w-full py-3" disabled={busy}>
              {busy ? "Yaradılır…" : "Qeydiyyatdan keç və abunəlik seç"}
            </Button>
          </div>
        </form>

        <p className="mt-6 text-center text-sm text-slate-500">
          Artıq hesabınız var?{" "}
          <Link href="/login" className="font-medium text-navy-800 underline decoration-gold-500 underline-offset-4">
            Daxil olun
          </Link>
        </p>
      </div>
    </div>
  );
}
