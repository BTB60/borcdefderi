"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { Alert, Button, Field, Input } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import type { Contact } from "@/lib/types";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<{ emailEnabled: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [contact, setContact] = useState<Contact | null>(null);

  useEffect(() => {
    api<Contact>("/api/public/contact").then(setContact).catch(() => setContact(null));
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setSent(await api<{ emailEnabled: boolean }>("/api/auth/forgot-password", { method: "POST", json: { email: email.trim() } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Serverlə əlaqə qurula bilmədi");
    } finally {
      setBusy(false);
    }
  }

  const waText = `Salam! HesabPro hesabımın şifrəsini unutmuşam. E-poçtum: ${email.trim() || "…"}`;

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas p-6">
      <div className="page-enter w-full max-w-md">
        <div className="mb-8 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-navy-800 font-serif text-lg font-bold text-gold-500">H</div>
          <span className="font-serif text-lg font-semibold text-navy-800">HesabPro</span>
        </div>
        <h1 className="font-serif text-3xl font-semibold text-navy-800">Şifrəni unutmusunuz?</h1>
        <p className="mt-2 text-sm text-slate-500">Hesabınızın e-poçtunu yazın — şifrəni bərpa etməyə kömək edək.</p>

        {sent ? (
          <div className="mt-8 space-y-4">
            {sent.emailEnabled ? (
              <Alert kind="success">
                Bu e-poçt qeydiyyatdadırsa, şifrəni bərpa etmək üçün link göndərildi. Linki 30 dəqiqə ərzində açın (spam qovluğunu da yoxlayın).
              </Alert>
            ) : (
              <Alert kind="success">
                Sorğunuz administratora göndərildi. Administrator kimliyinizi təsdiqləyib sizə müvəqqəti şifrə verəcək.
              </Alert>
            )}
            <ContactBox contact={contact} waText={waText} title={sent.emailEnabled ? "E-poçt gəlmədi?" : "Daha tez həll etmək üçün administratora yazın"} />
          </div>
        ) : (
          <form onSubmit={submit} className="mt-8 space-y-5">
            {error && <Alert>{error}</Alert>}
            <Field label="E-poçt">
              <Input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ad@mağaza.az" />
            </Field>
            <Button type="submit" variant="primary" className="w-full py-3" disabled={busy || !email.trim()}>
              {busy ? "Göndərilir…" : "Şifrəni bərpa et"}
            </Button>
            <p className="text-xs text-slate-500">
              İşçisinizsə, mağaza sahibi də “İşçilər” bölməsindən sizə yeni müvəqqəti şifrə verə bilər.
            </p>
          </form>
        )}

        <p className="mt-8 text-center text-sm text-slate-500">
          <Link href="/login" className="font-medium text-navy-800 underline decoration-gold-500 underline-offset-4 hover:text-gold-600">Girişə qayıt</Link>
        </p>
      </div>
    </main>
  );
}

function ContactBox({ contact, waText, title }: { contact: Contact | null; waText: string; title: string }) {
  if (!contact?.phone) return null;
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-4 text-sm">
      <p className="font-medium text-navy-800">{title}</p>
      <p className="mt-1 text-slate-500">Administrator: <a href={`tel:${contact.phone.replace(/[^+\d]/g, "")}`} className="font-medium text-navy-800">{contact.phone}</a></p>
      {contact.whatsapp && (
        <a
          href={`https://wa.me/${contact.whatsapp}?text=${encodeURIComponent(waText)}`}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-3 block rounded-xl bg-emerald-600 px-4 py-2.5 text-center font-medium text-white hover:bg-emerald-500"
        >
          WhatsApp ilə yaz
        </a>
      )}
    </div>
  );
}
