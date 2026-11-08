"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Avatar } from "@/components/Avatar";
import { Alert, Button, Card, Field, Input, PageHeader, Spinner, useConfirm } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { AuthResponse, Profile } from "@/lib/types";

const MAX_AVATAR_BYTES = 3 * 1024 * 1024;

type Msg = { kind: "success" | "error"; text: string } | null;

function errorText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback;
}

export default function SettingsPage() {
  const { refresh, isSuperAdmin } = useAuth();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Profile>("/api/profile").then(setProfile).catch((e) => setError(errorText(e, "Profil yüklənmədi")));
  }, []);

  async function updated(p: Profile) {
    setProfile(p);
    await refresh(); // sidebar name / picture / store name
  }

  if (!profile) return error ? <Alert>{error}</Alert> : <Spinner />;

  const roleLabel = isSuperAdmin ? "Platforma administratoru" : profile.role === "STORE_OWNER" ? "Mağaza sahibi" : "İşçi";

  return (
    <>
      <PageHeader title="Parametrlər" subtitle="Profil şəkli, şəxsi məlumatlar və təhlükəsizlik" />
      <div className="grid gap-6 xl:grid-cols-3">
        <div className="space-y-6 xl:col-span-1">
          <AvatarCard profile={profile} roleLabel={roleLabel} onChange={updated} />
        </div>
        <div className="space-y-6 xl:col-span-2">
          <PersonalCard profile={profile} onChange={updated} />
          {profile.role === "STORE_OWNER" && <StoreCard profile={profile} onChange={updated} />}
          <PasswordCard isAdmin={profile.platformRole === "SUPER_ADMIN"} />
        </div>
      </div>
    </>
  );
}

function AvatarCard({ profile, roleLabel, onChange }: { profile: Profile; roleLabel: string; onChange: (p: Profile) => Promise<void> }) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);
  const [confirm, confirmDialog] = useConfirm();

  async function upload(file: File) {
    setMsg(null);
    if (!["image/png", "image/jpeg"].includes(file.type)) {
      setMsg({ kind: "error", text: "Yalnız PNG və ya JPG şəkil seçin" });
      return;
    }
    if (file.size > MAX_AVATAR_BYTES) {
      setMsg({ kind: "error", text: "Şəklin ölçüsü 3 MB-dan çox ola bilməz" });
      return;
    }
    const form = new FormData();
    form.append("file", file);
    setBusy(true);
    try {
      await onChange(await api<Profile>("/api/profile/avatar", { method: "POST", body: form }));
      setMsg({ kind: "success", text: "Profil şəkli yeniləndi" });
    } catch (e) {
      setMsg({ kind: "error", text: errorText(e, "Şəkil yüklənmədi") });
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  async function remove() {
    if (!(await confirm("Profil şəkli silinsin?", { title: "Şəkli sil", confirmLabel: "Bəli, sil", danger: true }))) return;
    setBusy(true);
    setMsg(null);
    try {
      await onChange(await api<Profile>("/api/profile/avatar", { method: "DELETE" }));
      setMsg({ kind: "success", text: "Profil şəkli silindi" });
    } catch (e) {
      setMsg({ kind: "error", text: errorText(e, "Şəkil silinmədi") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <div className="flex flex-col items-center text-center">
        <Avatar name={profile.fullName} version={profile.hasAvatar ? profile.avatarVersion : null} size={120} className="text-navy-900" />
        <p className="mt-4 font-serif text-xl font-semibold text-navy-800">{profile.fullName}</p>
        <p className="text-sm text-slate-500">{roleLabel}</p>
        {profile.storeName && <p className="text-xs text-slate-400">{profile.storeName}</p>}
      </div>
      {msg && <div className="mt-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <input
        ref={input}
        type="file"
        accept="image/png,image/jpeg"
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) upload(f);
        }}
      />
      <div className="mt-5 grid gap-2">
        <Button variant="primary" disabled={busy} onClick={() => input.current?.click()}>
          {busy ? "Yüklənir…" : profile.hasAvatar ? "Şəkli dəyiş" : "Profil şəkli əlavə et"}
        </Button>
        {profile.hasAvatar && <Button variant="ghost" disabled={busy} onClick={remove}>Şəkli sil</Button>}
      </div>
      <p className="mt-3 text-center text-xs text-slate-400">PNG və ya JPG, maksimum 3 MB. Şəkil kvadrat kəsilir.</p>
      {confirmDialog}
    </Card>
  );
}

function PersonalCard({ profile, onChange }: { profile: Profile; onChange: (p: Profile) => Promise<void> }) {
  const [form, setForm] = useState({ fullName: profile.fullName, phone: profile.phone ?? "", email: profile.email, currentPassword: "" });
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);
  const emailChanged = form.email.trim().toLowerCase() !== profile.email;

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    setFields({});
    try {
      const p = await api<Profile>("/api/profile", {
        method: "PUT",
        json: {
          fullName: form.fullName,
          phone: form.phone || null,
          ...(emailChanged ? { email: form.email.trim(), currentPassword: form.currentPassword } : {}),
        },
      });
      await onChange(p);
      setForm({ fullName: p.fullName, phone: p.phone ?? "", email: p.email, currentPassword: "" });
      setMsg({ kind: "success", text: "Şəxsi məlumatlar yadda saxlanıldı" });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Yadda saxlanılmadı") });
      if (err instanceof ApiError) setFields(err.fields ?? {});
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Şəxsi məlumatlar</h2>
      <p className="mb-5 text-sm text-slate-500">Ad, telefon və giriş üçün e-poçt</p>
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <form onSubmit={save} className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Ad, soyad" error={fields.fullName}>
            <Input required maxLength={150} value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} autoComplete="name" />
          </Field>
          <Field label="Telefon" error={fields.phone}>
            <Input maxLength={30} inputMode="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} placeholder="+994 50 123 45 67" autoComplete="tel" />
          </Field>
        </div>
        <Field label="E-poçt (giriş adı)" error={fields.email} hint={emailChanged ? "E-poçtu dəyişmək üçün cari şifrənizi daxil edin" : undefined}>
          <Input maxLength={190} value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} autoComplete="username" />
        </Field>
        {emailChanged && (
          <Field label="Cari şifrə">
            <Input type="password" required value={form.currentPassword} onChange={(e) => setForm({ ...form, currentPassword: e.target.value })} autoComplete="current-password" />
          </Field>
        )}
        <Button type="submit" variant="primary" disabled={busy}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button>
      </form>
    </Card>
  );
}

function StoreCard({ profile, onChange }: { profile: Profile; onChange: (p: Profile) => Promise<void> }) {
  const [form, setForm] = useState({ name: profile.storeName ?? "", phone: profile.storePhone ?? "", address: profile.storeAddress ?? "" });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      await onChange(await api<Profile>("/api/profile/store", { method: "PUT", json: { name: form.name, phone: form.phone || null, address: form.address || null } }));
      setMsg({ kind: "success", text: "Mağaza məlumatları yadda saxlanıldı" });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Yadda saxlanılmadı") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Mağaza məlumatları</h2>
      <p className="mb-5 text-sm text-slate-500">Çeklərdə və hesabatlarda görünür</p>
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <form onSubmit={save} className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Mağazanın adı">
            <Input required maxLength={150} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Mağazanın telefonu">
            <Input maxLength={30} inputMode="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          </Field>
        </div>
        <Field label="Ünvan">
          <Input maxLength={255} value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
        </Field>
        <Button type="submit" variant="primary" disabled={busy}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button>
      </form>
    </Card>
  );
}

function PasswordCard({ isAdmin }: { isAdmin: boolean }) {
  const { signIn } = useAuth();
  const min = isAdmin ? 12 : 8;
  const [form, setForm] = useState({ current: "", next: "", repeat: "" });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);
  const mismatch = form.repeat.length > 0 && form.next !== form.repeat;
  const tooShort = form.next.length > 0 && form.next.length < min;

  async function save(e: FormEvent) {
    e.preventDefault();
    if (mismatch || tooShort) return;
    setBusy(true);
    setMsg(null);
    try {
      const res = await api<AuthResponse>("/api/profile/password", { method: "PUT", json: { currentPassword: form.current, newPassword: form.next } });
      signIn(res); // this device continues with the new token; other devices are signed out
      setForm({ current: "", next: "", repeat: "" });
      setMsg({ kind: "success", text: "Şifrə dəyişdirildi. Digər cihazlarda hesabdan çıxış edildi." });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Şifrə dəyişdirilmədi") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Şifrəni dəyiş</h2>
      <p className="mb-5 text-sm text-slate-500">Şifrə dəyişəndən sonra digər cihazlarda yenidən daxil olmaq lazım olacaq.</p>
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <form onSubmit={save} className="space-y-4">
        <Field label="Cari şifrə">
          <Input type="password" required value={form.current} onChange={(e) => setForm({ ...form, current: e.target.value })} autoComplete="current-password" />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Yeni şifrə" hint={`Ən azı ${min} simvol`} error={tooShort ? `Ən azı ${min} simvol olmalıdır` : undefined}>
            <Input type="password" required minLength={min} maxLength={72} value={form.next} onChange={(e) => setForm({ ...form, next: e.target.value })} autoComplete="new-password" />
          </Field>
          <Field label="Yeni şifrə (təkrar)" error={mismatch ? "Şifrələr eyni deyil" : undefined}>
            <Input type="password" required value={form.repeat} onChange={(e) => setForm({ ...form, repeat: e.target.value })} autoComplete="new-password" />
          </Field>
        </div>
        <Button type="submit" variant="gold" disabled={busy || mismatch || tooShort || !form.current || !form.next}>
          {busy ? "Dəyişdirilir…" : "Şifrəni dəyiş"}
        </Button>
      </form>
    </Card>
  );
}
