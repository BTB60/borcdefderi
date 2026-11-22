"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Badge, Button, Card, EmptyState, Field, Input, Spinner, useConfirm } from "@/components/ui";
import { API_URL, ApiError, api, getToken } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { AdStory } from "@/lib/types";

type Props = { onError: (m: string | null) => void; onNotice?: (m: string | null) => void };

const EMPTY = { title: "", subtitle: "", buttonLabel: "", linkUrl: "", whatsapp: "", startsOn: "", endsOn: "", sortOrder: "0" };

const err = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback);

/** The story picture is behind auth: fetch it with the token and show it as an object URL. */
function useStoryImage(story: AdStory | null) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    if (!story?.hasImage) { setSrc(null); return; }
    let alive = true;
    let url: string | null = null;
    const token = getToken();
    fetch(`${API_URL}/api/ads/${story.id}/image?v=${story.imageVersion}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then(async (r) => (r.ok ? URL.createObjectURL(await r.blob()) : null))
      .then((u) => { url = u; if (alive) setSrc(u); })
      .catch(() => undefined);
    return () => { alive = false; if (url) URL.revokeObjectURL(url); };
  }, [story?.id, story?.imageVersion, story?.hasImage]);
  return src;
}

function Circle({ story }: { story: AdStory }) {
  const src = useStoryImage(story);
  return (
    <div className={`h-14 w-14 shrink-0 rounded-full p-[3px] ${story.live ? "bg-violet-500" : "bg-slate-200"}`}>
      <div className="flex h-full w-full items-center justify-center overflow-hidden rounded-full border-2 border-white bg-violet-100 text-xs text-violet-700">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        {src ? <img src={src} alt="" className="h-full w-full object-cover" /> : "şəkil"}
      </div>
    </div>
  );
}

async function uploadImage(id: string, file: File) {
  const fd = new FormData();
  fd.append("file", file);
  await api(`/api/admin/ads/${id}/image`, { method: "POST", body: fd });
}

/**
 * Reklam hekayələri (SUPER_ADMIN): picture stories shown as circles on top of every store's phone home screen.
 * A story appears only while it is on, inside its dates and has a picture.
 */
export function AdStoriesCard({ onError, onNotice }: Props) {
  const [items, setItems] = useState<AdStory[] | null>(null);
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState<AdStory | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [reload, setReload] = useState(0);
  const [confirm, confirmDialog] = useConfirm();
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api<AdStory[]>("/api/admin/ads").then(setItems).catch((e) => onError(err(e, "Reklamlar yüklənmədi")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  const set = (k: keyof typeof EMPTY) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));

  function reset() {
    setForm(EMPTY);
    setEditing(null);
    setFile(null);
    if (fileInput.current) fileInput.current.value = "";
  }

  function edit(s: AdStory) {
    setEditing(s);
    setFile(null);
    if (fileInput.current) fileInput.current.value = "";
    setForm({
      title: s.title, subtitle: s.subtitle ?? "", buttonLabel: s.buttonLabel ?? "", linkUrl: s.linkUrl ?? "",
      whatsapp: s.whatsapp ?? "", startsOn: s.startsOn ?? "", endsOn: s.endsOn ?? "", sortOrder: String(s.sortOrder),
    });
  }

  const payload = (active: boolean) => ({
    title: form.title, subtitle: form.subtitle || null, buttonLabel: form.buttonLabel || null,
    linkUrl: form.linkUrl || null, whatsapp: form.whatsapp || null, startsOn: form.startsOn || null,
    endsOn: form.endsOn || null, sortOrder: Number(form.sortOrder) || 0, active,
  });

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!editing && !file) {
      onError("Hekayə üçün şəkil seçin (telefon ekranı kimi şaquli şəkil yaxşıdır)");
      return;
    }
    setBusy(true);
    onError(null);
    try {
      const saved = editing
        ? await api<AdStory>(`/api/admin/ads/${editing.id}`, { method: "PUT", json: payload(editing.active) })
        : await api<AdStory>("/api/admin/ads", { method: "POST", json: payload(true) });
      if (file) await uploadImage(saved.id, file);
      onNotice?.(editing ? "Reklam yeniləndi" : "Reklam əlavə olundu, telefonlarda görünəcək");
      reset();
      setReload((r) => r + 1);
    } catch (e2) {
      onError(err(e2, "Yadda saxlanılmadı"));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(s: AdStory) {
    try {
      await api(`/api/admin/ads/${s.id}`, {
        method: "PUT",
        json: { title: s.title, subtitle: s.subtitle, buttonLabel: s.buttonLabel, linkUrl: s.linkUrl, whatsapp: s.whatsapp,
          startsOn: s.startsOn, endsOn: s.endsOn, sortOrder: s.sortOrder, active: !s.active },
      });
      setReload((r) => r + 1);
    } catch (e) {
      onError(err(e, "Dəyişmədi"));
    }
  }

  async function remove(s: AdStory) {
    if (!(await confirm(`“${s.title}” reklamı silinsin? Baxış statistikası da silinəcək.`, { title: "Reklamı sil", confirmLabel: "Sil", danger: true }))) return;
    try {
      await api(`/api/admin/ads/${s.id}`, { method: "DELETE" });
      if (editing?.id === s.id) reset();
      setReload((r) => r + 1);
    } catch (e) {
      onError(err(e, "Silinmədi"));
    }
  }

  const ctr = (s: AdStory) => (s.views > 0 ? `${Math.round((s.clicks / s.views) * 1000) / 10}%` : "—");

  return (
    <Card>
      <h2 className="font-serif text-lg font-semibold text-navy-800">Reklam hekayələri</h2>
      <p className="mb-4 text-sm text-slate-500">
        Telefon tətbiqində Panelin yuxarısında dairə kimi görünür. Toxunanda şəkil tam ekran açılır, düymə linkə və ya WhatsApp-a aparır.
        Yalnız aktiv, tarixi uyğun və şəkli olan reklam görünür.
      </p>

      <form onSubmit={save} className="mb-5 space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Başlıq"><Input required maxLength={80} value={form.title} onChange={set("title")} placeholder="Vizit kartları -20%" /></Field>
          <Field label="Alt mətn"><Input maxLength={160} value={form.subtitle} onChange={set("subtitle")} placeholder="Premium Reklam · bu həftə" /></Field>
          <Field label="Link (istəyə görə)"><Input maxLength={500} value={form.linkUrl} onChange={set("linkUrl")} placeholder="https://premiumreklam.az" /></Field>
          <Field label="WhatsApp (istəyə görə)"><Input maxLength={30} inputMode="tel" value={form.whatsapp} onChange={set("whatsapp")} placeholder="+994501234567" /></Field>
          <Field label="Düymə mətni" hint="Boş qalsa: “Ətraflı” və ya “WhatsApp-da yaz”"><Input maxLength={30} value={form.buttonLabel} onChange={set("buttonLabel")} /></Field>
          <Field label="Sıra" hint="Kiçik rəqəm əvvəl göstərilir"><Input type="number" value={form.sortOrder} onChange={set("sortOrder")} /></Field>
          <Field label="Nə vaxtdan"><Input type="date" value={form.startsOn} onChange={set("startsOn")} /></Field>
          <Field label="Nə vaxta qədər"><Input type="date" value={form.endsOn} onChange={set("endsOn")} /></Field>
        </div>
        <Field label={editing ? "Yeni şəkil (dəyişmək istəsəniz)" : "Şəkil"} hint="PNG və ya JPG, 5 MB-a qədər. Şaquli (9:16) şəkil telefonda ən yaxşı görünür">
          <input ref={fileInput} type="file" accept="image/png,image/jpeg" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="block text-sm" />
        </Field>
        <div className="flex flex-wrap justify-end gap-2">
          {editing && <Button type="button" variant="ghost" onClick={reset}>İmtina</Button>}
          <Button type="submit" disabled={busy}>{busy ? "Saxlanılır…" : editing ? "Yadda saxla" : "Reklam əlavə et"}</Button>
        </div>
      </form>

      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Reklam yoxdur" /> : (
        <ul className="divide-y divide-slate-100">
          {items.map((s) => (
            <li key={s.id} className="flex flex-col gap-3 py-3 sm:flex-row sm:items-center">
              <Circle story={s} />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium text-navy-800">{s.title}</span>
                  {s.live ? <Badge tone="emerald">Görünür</Badge> : !s.active ? <Badge tone="slate">Söndürülüb</Badge>
                    : !s.hasImage ? <Badge tone="amber">Şəkil yoxdur</Badge> : <Badge tone="slate">Tarix xaricində</Badge>}
                </div>
                {s.subtitle && <p className="text-sm text-slate-600">{s.subtitle}</p>}
                <p className="mt-1 text-xs text-slate-400">
                  {s.startsOn ? `${formatDate(s.startsOn)}-dən ` : ""}{s.endsOn ? `${formatDate(s.endsOn)}-ə qədər · ` : s.startsOn ? "· " : ""}
                  baxış {s.views} · düyməyə toxunma {s.clicks} · {ctr(s)}
                  {s.whatsapp ? ` · WhatsApp ${s.whatsapp}` : ""}{s.linkUrl ? ` · ${s.linkUrl}` : ""}
                </p>
              </div>
              <div className="flex shrink-0 gap-1">
                <Button variant="outline" onClick={() => edit(s)}>Redaktə</Button>
                <Button variant="outline" onClick={() => toggle(s)}>{s.active ? "Söndür" : "Göstər"}</Button>
                <Button variant="outline" onClick={() => remove(s)}>Sil</Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {confirmDialog}
    </Card>
  );
}
