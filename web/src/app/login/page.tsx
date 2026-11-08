"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { AuthResponse } from "@/lib/types";
import { Alert, Button, Field, Input } from "@/components/ui";

export default function LoginPage() {
  const router = useRouter();
  const { signIn } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = await api<AuthResponse>("/api/auth/login", { method: "POST", json: { email, password } });
      signIn(res);
      router.replace(res.me.platformRole === "SUPER_ADMIN" ? "/admin" : "/dashboard");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Serverlə əlaqə qurula bilmədi");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-2">
      <aside className="relative hidden overflow-hidden bg-navy-800 p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="absolute -right-24 -top-24 h-96 w-96 rounded-full bg-gold-500/10 blur-3xl" />
        <div className="absolute -bottom-32 -left-16 h-96 w-96 rounded-full bg-gold-500/10 blur-3xl" />
        <div className="relative flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gold-500 font-serif text-xl font-bold text-navy-900">B</div>
          <span className="font-serif text-xl font-semibold tracking-wide">BORCDEFTERI</span>
        </div>
        <div className="relative max-w-md">
          <h2 className="font-serif text-4xl font-semibold leading-tight">Borclarınızı nəzarətdə saxlayın.</h2>
          <p className="mt-4 text-white/70">
            Müştəri borcları, şirkət alacaqları və ödənişlər — hamısı bir yerdə, mağazanızın yalnız özünə aid təhlükəsiz iş sahəsində.
          </p>
          <ul className="mt-8 space-y-3 text-sm text-white/80">
            <li className="flex items-center gap-3"><span className="h-1.5 w-1.5 rounded-full bg-gold-500" />Nağd və kart ödənişlərinin tam tarixçəsi</li>
            <li className="flex items-center gap-3"><span className="h-1.5 w-1.5 rounded-full bg-gold-500" />İşçilər üçün ayrı icazələr</li>
            <li className="flex items-center gap-3"><span className="h-1.5 w-1.5 rounded-full bg-gold-500" />PDF və Excel hesabatları</li>
          </ul>
        </div>
        <p className="relative text-xs text-white/40">© {new Date().getFullYear()} BORCDEFTERI</p>
      </aside>

      <main className="flex items-center justify-center p-6 sm:p-12">
        <div className="page-enter w-full max-w-md">
          <div className="mb-8 flex items-center gap-3 lg:hidden">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-navy-800 font-serif text-lg font-bold text-gold-500">B</div>
            <span className="font-serif text-lg font-semibold text-navy-800">BORCDEFTERI</span>
          </div>
          <h1 className="font-serif text-3xl font-semibold text-navy-800">Xoş gəlmisiniz</h1>
          <p className="mt-2 text-sm text-slate-500">Hesabınıza daxil olun</p>

          <form onSubmit={onSubmit} className="mt-8 space-y-5" noValidate>
            {error && <Alert>{error}</Alert>}
            <Field label="E-poçt və ya istifadəçi adı">
              <Input type="text" autoComplete="username" autoCapitalize="none" spellCheck={false} required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ad@mağaza.az" />
            </Field>
            <Field label="Şifrə">
              <Input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
            </Field>
            <div className="-mt-2 text-right">
              <Link href="/sifre-unutdum" className="text-sm font-medium text-navy-800 underline decoration-gold-500 underline-offset-4 hover:text-gold-600">
                Şifrəni unutmusunuz?
              </Link>
            </div>
            <Button type="submit" variant="primary" className="w-full py-3" disabled={busy}>
              {busy ? "Yoxlanılır…" : "Daxil ol"}
            </Button>
          </form>

          <p className="mt-8 text-center text-sm text-slate-500">
            Mağazanız yoxdur?{" "}
            <Link href="/register" className="font-medium text-navy-800 underline decoration-gold-500 underline-offset-4 hover:text-gold-600">
              Qeydiyyatdan keçin
            </Link>
          </p>
        </div>
      </main>
    </div>
  );
}
