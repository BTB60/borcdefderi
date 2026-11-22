"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Avatar } from "@/components/Avatar";
import { Alert, Button, Card, Field, Input, PageHeader, Spinner, useConfirm } from "@/components/ui";
import { ApiError, api, download, postForFile } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { SetPinModal } from "@/components/sales/SetPin";
import { TelegramCard } from "@/components/TelegramCard";
import type { AuthResponse, Profile, ReturnApprovalPolicy } from "@/lib/types";

const MAX_AVATAR_BYTES = 3 * 1024 * 1024;

type Msg = { kind: "success" | "error"; text: string } | null;

function errorText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback;
}

export default function SettingsPage() {
  const { refresh, isSuperAdmin, isOwner, can } = useAuth();
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

  const roleLabel = profile.platformRole === "SUPPORT_ADMIN" ? "Köməkçi admin" : isSuperAdmin ? "Platforma administratoru" : profile.role === "STORE_OWNER" ? "Mağaza sahibi" : "İşçi";

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
          {profile.role === "STORE_OWNER" && <RequisitesCard />}
          {profile.storeId && <PinCard />}
          {profile.role === "STORE_OWNER" && <BonusCard />}
          {!isSuperAdmin && (isOwner || can("RETURN_APPROVE")) && <ReturnApprovalCard isOwner={isOwner} />}
          {profile.role === "STORE_OWNER" && <TelegramCard />}
          {profile.role === "STORE_OWNER" && <BackupCard />}
          <PasswordCard isAdmin={profile.platformRole === "SUPER_ADMIN" || profile.platformRole === "SUPPORT_ADMIN"} />
          <SecurityCard profile={profile} onChange={updated} />
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

/** Kassa PIN-i: cashier switch and screen lock on the till. */
function PinCard() {
  const [open, setOpen] = useState(false);
  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Kassa PIN-i</h2>
      <p className="mb-4 text-sm text-slate-500">Ortaq kassada PIN ilə “Kassir dəyiş” edin və kilidlənmiş ekranı açın</p>
      <Button variant="outline" onClick={() => setOpen(true)}>PIN təyin et / dəyiş</Button>
      {open && <SetPinModal onClose={() => setOpen(false)} />}
    </Card>
  );
}

type Requisites = { taxId: string | null; bankName: string | null; bankAccount: string | null; bankCode: string | null; director: string | null };

/** Rekvizitlər printed on the qaimə-faktura: VÖEN, bank details, director. */
function RequisitesCard() {
  const [form, setForm] = useState<Record<keyof Requisites, string> | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);
  useEffect(() => {
    api<Requisites>("/api/profile/requisites")
      .then((r) => setForm({ taxId: r.taxId ?? "", bankName: r.bankName ?? "", bankAccount: r.bankAccount ?? "", bankCode: r.bankCode ?? "", director: r.director ?? "" }))
      .catch(() => setForm({ taxId: "", bankName: "", bankAccount: "", bankCode: "", director: "" }));
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!form) return;
    setBusy(true);
    setMsg(null);
    try {
      await api("/api/profile/requisites", { method: "PUT", json: form });
      setMsg({ kind: "success", text: "Rekvizitlər yadda saxlanıldı" });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Yadda saxlanılmadı") });
    } finally {
      setBusy(false);
    }
  }

  if (!form) return null;
  const f = (k: keyof Requisites, label: string, max: number, placeholder?: string) => (
    <Field label={label}><Input maxLength={max} value={form[k]} placeholder={placeholder} onChange={(e) => setForm({ ...form, [k]: e.target.value })} /></Field>
  );
  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Rekvizitlər</h2>
      <p className="mb-5 text-sm text-slate-500">Topdan satışın qaimə-fakturasında satıcı kimi çıxır</p>
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <form onSubmit={save} className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          {f("taxId", "VÖEN", 20, "1234567891")}
          {f("director", "Direktor / məsul şəxs", 100)}
          {f("bankName", "Bank", 100, "Kapital Bank")}
          {f("bankCode", "Bank kodu", 20)}
        </div>
        {f("bankAccount", "Hesab nömrəsi (IBAN)", 40, "AZ00 ....")}
        <Button type="submit" variant="primary" disabled={busy}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button>
      </form>
    </Card>
  );
}

/** Ehtiyat nüsxəsi: the whole store in one Excel file. */
function BackupCard() {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);
  async function get() {
    setBusy(true);
    setMsg(null);
    try {
      await download("/api/backup", "hesabpro-ehtiyat.xlsx");
      setMsg({ kind: "success", text: "Ehtiyat nüsxəsi yükləndi" });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Yüklənmədi") });
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Ehtiyat nüsxəsi</h2>
      <p className="mb-5 text-sm text-slate-500">Mağazanın bütün məlumatları — müştərilər, şirkətlər, borclar, ödənişlər, məhsullar, satışlar, kassa, xərclər və mal qəbulu — bir Excel faylında. Ayda bir dəfə yükləyib saxlamağı tövsiyə edirik.</p>
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <Button variant="primary" disabled={busy} onClick={get}>{busy ? "Hazırlanır…" : "Bütün məlumatları Excel-ə yüklə"}</Button>
    </Card>
  );
}

/** Müştəri bonusu: % of what customers pay at the till comes back to them as bonus (0 = off). */
function BonusCard() {
  const [value, setValue] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);

  useEffect(() => {
    api<{ bonusPercent: number }>("/api/settings/bonus").then((r) => { setValue(String(r.bonusPercent ?? 0)); setLoaded(true); }).catch(() => setLoaded(true));
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      const r = await api<{ bonusPercent: number }>("/api/settings/bonus", { method: "PUT", json: { bonusPercent: Number(value.replace(",", ".")) || 0 } });
      setValue(String(r.bonusPercent));
      setMsg({ kind: "success", text: Number(r.bonusPercent) > 0 ? `Müştərilər ödədiklərinin ${r.bonusPercent}%-ni bonus qazanacaq` : "Bonus söndürüldü" });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Yadda saxlanılmadı") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Müştəri bonusu</h2>
      <p className="mb-5 text-sm text-slate-500">Müştəri seçilmiş satışda ödənilən məbləğin bu faizi müştərinin bonusuna yazılır, növbəti alışda bonusla ödəyə bilər. 0 — bonus yoxdur. Müştəriyə daimi endirim faizi onun kartında təyin olunur.</p>
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <form onSubmit={save} className="flex flex-wrap items-end gap-3">
        <Field label="Bonus faizi (%)">
          <Input inputMode="decimal" className="w-32" value={value} disabled={!loaded} onChange={(e) => setValue(e.target.value)} placeholder="0" />
        </Field>
        <Button type="submit" variant="primary" disabled={busy || !loaded}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button>
      </form>
    </Card>
  );
}

/** İki addımlı giriş (e-mail code) and "sign out on every other device". */
function SecurityCard({ profile, onChange }: { profile: Profile; onChange: (p: Profile) => Promise<void> }) {
  const { signIn } = useAuth();
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);
  const on = !!profile.twoFactorEnabled;

  async function toggle(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      await onChange(await api<Profile>("/api/profile/two-factor", { method: "PUT", json: { enabled: !on, currentPassword: password } }));
      setPassword("");
      setMsg({ kind: "success", text: on ? "İki addımlı giriş söndürüldü" : "İki addımlı giriş açıldı: növbəti girişdə e-poçtunuza kod gələcək" });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Alınmadı") });
    } finally {
      setBusy(false);
    }
  }

  async function logoutAll() {
    setBusy(true);
    setMsg(null);
    try {
      signIn(await api<AuthResponse>("/api/profile/logout-all", { method: "POST" }));
      setMsg({ kind: "success", text: "Bütün digər cihazlardan çıxış edildi. Bu cihazda davam edirsiniz." });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Alınmadı") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Təhlükəsizlik</h2>
      <p className="mb-5 text-sm text-slate-500">İki addımlı girişdə şifrədən sonra e-poçtunuza gələn 6 rəqəmli kod da soruşulur.</p>
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      <form onSubmit={toggle} className="flex flex-wrap items-end gap-3">
        <Field label={`İki addımlı giriş: ${on ? "açıqdır" : "bağlıdır"} — təsdiq üçün şifrə`}>
          <Input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} className="w-64" />
        </Field>
        <Button type="submit" variant={on ? "outline" : "primary"} disabled={busy || !password}>{on ? "Söndür" : "Aç"}</Button>
      </form>
      <div className="mt-6 border-t border-slate-100 pt-4">
        <p className="mb-2 text-sm text-slate-600">Telefonunuz itibsə və ya başqa kompüterdə hesabınız açıq qalıbsa:</p>
        <Button variant="outline" disabled={busy} onClick={logoutAll}>Bütün digər cihazlardan çıxış</Button>
      </div>
    </Card>
  );
}

/**
 * Owner and managers (RETURN_APPROVE): their own approval PIN and manager card. Every return or cancel of a
 * cashier / seller needs one of them (mandatory in every store).
 */
function ReturnApprovalCard({ isOwner }: { isOwner: boolean }) {
  const [policy, setPolicy] = useState<ReturnApprovalPolicy | null>(null);
  const [pinForm, setPinForm] = useState({ pin: "", repeat: "", current: "" });
  const [cardPassword, setCardPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg>(null);
  const [confirm, confirmDialog] = useConfirm();
  const mismatch = pinForm.repeat.length > 0 && pinForm.pin !== pinForm.repeat;

  function loaded(p: ReturnApprovalPolicy) {
    setPolicy(p);
  }

  useEffect(() => {
    api<ReturnApprovalPolicy>("/api/return-approval").then(loaded).catch((e) => setMsg({ kind: "error", text: errorText(e, "Yüklənmədi") }));
  }, []);

  async function run(action: () => Promise<ReturnApprovalPolicy>, ok: string, fail: string) {
    setBusy(true);
    setMsg(null);
    try {
      loaded(await action());
      setMsg({ kind: "success", text: ok });
      return true;
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, fail) });
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function savePin(e: FormEvent) {
    e.preventDefault();
    if (mismatch) return;
    const done = await run(() => api("/api/return-approval/pin", { method: "PUT", json: { pin: pinForm.pin, currentPassword: pinForm.current } }), "PIN yadda saxlanıldı", "PIN saxlanılmadı");
    if (done) setPinForm({ pin: "", repeat: "", current: "" });
  }

  async function removePin() {
    if (!(await confirm(<>PIN silinsin? Bundan sonra kassirin qaytarmalarını PIN ilə təsdiq edə bilməyəcəksiniz.</>, { danger: true, confirmLabel: "Sil" }))) return;
    await run(() => api("/api/return-approval/pin", { method: "DELETE" }), "PIN silindi", "Silinmədi");
  }

  async function issueCard(e: FormEvent) {
    e.preventDefault();
    if (policy?.hasCard && !(await confirm(<>Yeni kart verilsin? Köhnə kart dərhal işləməyəcək.</>, { confirmLabel: "Yeni kart" }))) return;
    setBusy(true);
    setMsg(null);
    try {
      await postForFile("/api/return-approval/card", { currentPassword: cardPassword }, "menecer-karti.pdf");
      setCardPassword("");
      loaded(await api<ReturnApprovalPolicy>("/api/return-approval"));
      setMsg({ kind: "success", text: "Kart yükləndi (menecer-karti.pdf). Çap edib qırıq xətt üzrə kəsin." });
    } catch (err) {
      setMsg({ kind: "error", text: errorText(err, "Kart verilmədi") });
    } finally {
      setBusy(false);
    }
  }

  async function revokeCard() {
    if (!(await confirm(<>Kart ləğv edilsin? Bundan sonra o kartla təsdiq etmək olmayacaq.</>, { danger: true, confirmLabel: "Ləğv et" }))) return;
    await run(() => api("/api/return-approval/card", { method: "DELETE" }), "Kart ləğv edildi", "Ləğv edilmədi");
  }

  if (!policy) return msg ? <Alert>{msg.text}</Alert> : null;

  const digitsOnly = (v: string) => v.replace(/\D/g, "").slice(0, 6);

  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Qaytarma təsdiqi</h2>
      <p className="mb-5 text-sm text-slate-500">
        Satıcı və kassir menecerin PIN-i və ya kartı olmadan heç bir qaytarma və satış ləğvi edə bilməz. Mağaza sahibi və
        “Qaytarmanı təsdiq” icazəsi olan işçilər özləri qaytara bilir.
      </p>
      {isOwner && !policy.hasPin && !policy.hasCard && (
        <div className="mb-4"><Alert>Hələ PIN və ya kart təyin etməmisiniz: satıcılarınız qaytarma edə bilməyəcək. Aşağıda PIN təyin edin və ya kart çap edin.</Alert></div>
      )}
      {msg && <div className="mb-4"><Alert kind={msg.kind}>{msg.text}</Alert></div>}

      <form onSubmit={savePin} className="space-y-4">
        <p className="text-sm text-slate-600">
          {policy.hasPin ? "Sizin təsdiq PIN-iniz təyin edilib. Dəyişmək üçün yenisini yazın." : "Kassirin qaytarmasını təsdiq etmək üçün özünüzə PIN təyin edin (4–6 rəqəm)."}
        </p>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Yeni PIN">
            <Input type="password" inputMode="numeric" autoComplete="off" required minLength={4} maxLength={6} value={pinForm.pin} onChange={(e) => setPinForm({ ...pinForm, pin: digitsOnly(e.target.value) })} />
          </Field>
          <Field label="PIN (təkrar)" error={mismatch ? "PIN-lər eyni deyil" : undefined}>
            <Input type="password" inputMode="numeric" autoComplete="off" required maxLength={6} value={pinForm.repeat} onChange={(e) => setPinForm({ ...pinForm, repeat: digitsOnly(e.target.value) })} />
          </Field>
          <Field label="Hesab şifrəsi">
            <Input type="password" required autoComplete="current-password" value={pinForm.current} onChange={(e) => setPinForm({ ...pinForm, current: e.target.value })} />
          </Field>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" variant="gold" disabled={busy || mismatch || pinForm.pin.length < 4 || !pinForm.current}>{policy.hasPin ? "PIN-i dəyiş" : "PIN təyin et"}</Button>
          {policy.hasPin && <Button type="button" variant="ghost" disabled={busy} onClick={removePin}>PIN-i sil</Button>}
        </div>
      </form>

      <form onSubmit={issueCard} className="mt-6 space-y-4 border-t border-slate-100 pt-6">
        <div>
          <h3 className="font-semibold text-navy-800">Menecer kartı</h3>
          <p className="text-sm text-slate-600">
            PIN yazmaq əvəzinə kassada kartın barkodunu skan edin (USB skaner və ya telefon kamerası).
            {policy.hasCard ? " Sizin kartınız aktivdir. İtirsə, yenisini verin: köhnəsi dərhal işləməyəcək." : ""}
          </p>
        </div>
        <div className="max-w-xs">
          <Field label="Hesab şifrəsi">
            <Input type="password" required autoComplete="current-password" value={cardPassword} onChange={(e) => setCardPassword(e.target.value)} />
          </Field>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" variant="primary" disabled={busy || !cardPassword}>{policy.hasCard ? "Yeni kart çap et" : "Kart çap et"}</Button>
          {policy.hasCard && <Button type="button" variant="ghost" disabled={busy} onClick={revokeCard}>Kartı ləğv et</Button>}
        </div>
      </form>
      {confirmDialog}
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
