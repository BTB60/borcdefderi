"use client";

/* eslint-disable @next/next/no-img-element -- promo pictures come from the API, not from the Next image pipeline */
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Spinner, useConfirm } from "@/components/ui";
import { API_URL, ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatMoney } from "@/lib/format";
import { shrinkImage } from "@/components/LoyaltyCard";

type Promo = {
  id: string; title: string; subtitle: string | null; badge: string | null; oldPrice: number | null; newPrice: number | null;
  color: string | null; hasImage: boolean; imageVersion: number; active: boolean; startsOn: string | null; endsOn: string | null;
  sortOrder: number; views: number; live: boolean; createdAt: string;
};

const COLORS = ["#7c3aed", "#db2777", "#ea580c", "#0891b2", "#16a34a", "#0f1f3d", "#b45309", "#dc2626"];
const imageUrl = (p: Pick<Promo, "id" | "imageVersion">) => `${API_URL}/api/public/promos/${p.id}/image?v=${p.imageVersion}`;

/** Mağaza reklamları: the store's own promo posts shown to its card holders in the HesabPro Kart app. */
export default function PromosPage() {
  const { isOwner, can } = useAuth();
  const allowed = isOwner || can("SALES");
  const [items, setItems] = useState<Promo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<Promo | "new" | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  const load = useCallback(() => {
    api<Promo[]>("/api/promos").then(setItems).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, []);
  useEffect(() => { if (allowed) load(); }, [allowed, load]);

  if (!allowed) return <Card><EmptyState title="Bu bölmə üçün icazəniz yoxdur" /></Card>;

  async function remove(p: Promo) {
    if (!(await confirm(`“${p.title}” reklamı silinsin?`, { confirmLabel: "Sil", danger: true }))) return;
    try {
      await api(`/api/promos/${p.id}`, { method: "DELETE" });
      setNotice("Reklam silindi");
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Silinmədi");
    }
  }

  return (
    <>
      <PageHeader title="Reklamlar" subtitle="Endirim və yeni məhsul postlarınız — bonus kartı olan müştərilər onları “HesabPro Kart” tətbiqində kartın altında görür."
        actions={<Button variant="gold" onClick={() => setEditing("new")}>+ Yeni reklam</Button>} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {!items ? <Spinner /> : items.length === 0 ? (
        <Card><EmptyState title="Hələ reklam yoxdur" text="“+ Yeni reklam” ilə ilk endirim postunuzu yaradın — şəkil, köhnə və yeni qiymət, nişan." /></Card>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
          {items.map((p) => (
            <div key={p.id} className={p.live ? "" : "opacity-60"}>
              <PromoPreview p={p} />
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-500">
                {p.live ? <Badge tone="emerald">Görünür</Badge> : <Badge tone="slate">{p.active ? "Tarixdən kənar" : "Söndürülüb"}</Badge>}
                <span>{p.views} baxış</span>
                {(p.startsOn || p.endsOn) && <span>{p.startsOn ? formatDate(p.startsOn) : "…"} – {p.endsOn ? formatDate(p.endsOn) : "…"}</span>}
                <span className="ml-auto flex gap-1">
                  <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setEditing(p)}>Dəyiş</Button>
                  <Button variant="ghost" className="px-2.5 py-1.5 text-rose-600" onClick={() => remove(p)}>Sil</Button>
                </span>
              </div>
            </div>
          ))}
        </div>
      )}
      {editing && <PromoModal promo={editing === "new" ? null : editing} onClose={() => setEditing(null)}
        onSaved={(created) => { setEditing(null); setNotice(created ? "Reklam yaradıldı — müştərilər tətbiqdə görəcək" : "Reklam yeniləndi"); load(); }} />}
      {confirmDialog}
    </>
  );
}

type Draft = { title: string; subtitle: string; badge: string; oldPrice: string; newPrice: string; color: string; active: boolean; startsOn: string; endsOn: string };

/** The banner as the app shows it: picture, colour wash, light sweep, pulsing badge, old price struck through. */
function PromoPreview({ p, localImage }: { p: Pick<Promo, "title" | "subtitle" | "badge" | "oldPrice" | "newPrice" | "color" | "hasImage" | "id" | "imageVersion">; localImage?: string | null }) {
  const color = p.color || "#7c3aed";
  const img = localImage ?? (p.hasImage ? imageUrl(p) : null);
  const off = p.oldPrice && p.newPrice && p.oldPrice > p.newPrice ? Math.round((1 - p.newPrice / p.oldPrice) * 100) : null;
  return (
    <div className="promo-card relative aspect-[16/9] overflow-hidden rounded-3xl text-white shadow-xl"
      style={{ background: `linear-gradient(135deg, ${color}, #0f1f3d)` }}>
      {img && <img src={img} alt="" className="absolute inset-0 h-full w-full object-cover" />}
      <div className="absolute inset-0" style={{ background: `linear-gradient(90deg, ${color}ee 0%, ${color}99 45%, transparent 100%)` }} />
      <div className="promo-sweep absolute inset-0" />
      {(p.badge || off) && (
        <div className="promo-badge absolute right-4 top-4 rounded-full bg-white px-3 py-1 text-sm font-black" style={{ color }}>
          {p.badge || `-${off}%`}
        </div>
      )}
      <div className="relative flex h-full max-w-[70%] flex-col justify-end p-5">
        <p className="text-xl font-extrabold leading-tight drop-shadow">{p.title || "Başlıq"}</p>
        {p.subtitle && <p className="mt-1 line-clamp-2 text-sm text-white/85">{p.subtitle}</p>}
        {(p.newPrice != null || p.oldPrice != null) && (
          <p className="mt-2 flex items-baseline gap-2">
            {p.newPrice != null && <span className="text-2xl font-black">{formatMoney(p.newPrice)}</span>}
            {p.oldPrice != null && <span className="text-sm text-white/70 line-through">{formatMoney(p.oldPrice)}</span>}
          </p>
        )}
      </div>
      <style>{`
        .promo-sweep { background: linear-gradient(110deg, transparent 30%, rgba(255,255,255,.28) 50%, transparent 70%); transform: translateX(-100%); animation: promoSweep 3.2s ease-in-out infinite; }
        .promo-badge { animation: promoPulse 1.6s ease-in-out infinite; box-shadow: 0 0 0 0 rgba(255,255,255,.6); }
        .promo-card { transition: transform .35s ease, box-shadow .35s ease; }
        .promo-card:hover { transform: translateY(-4px) scale(1.01); box-shadow: 0 24px 48px -12px rgba(15,31,61,.45); }
        @keyframes promoSweep { 0% { transform: translateX(-100%); } 60%, 100% { transform: translateX(100%); } }
        @keyframes promoPulse { 0%, 100% { transform: scale(1); box-shadow: 0 0 0 0 rgba(255,255,255,.55); } 50% { transform: scale(1.08); box-shadow: 0 0 0 10px rgba(255,255,255,0); } }
        @media (prefers-reduced-motion: reduce) { .promo-sweep, .promo-badge { animation: none; } }
      `}</style>
    </div>
  );
}

async function dataUrlToBlob(url: string): Promise<Blob> {
  return (await fetch(url)).blob();
}

function PromoModal({ promo, onClose, onSaved }: { promo: Promo | null; onClose: () => void; onSaved: (created: boolean) => void }) {
  const [f, setF] = useState<Draft>({
    title: promo?.title ?? "", subtitle: promo?.subtitle ?? "", badge: promo?.badge ?? "",
    oldPrice: promo?.oldPrice != null ? String(promo.oldPrice) : "", newPrice: promo?.newPrice != null ? String(promo.newPrice) : "",
    color: promo?.color ?? COLORS[0], active: promo?.active ?? true, startsOn: promo?.startsOn ?? "", endsOn: promo?.endsOn ?? "",
  });
  const [image, setImage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const num = (s: string) => (s.trim() ? Number(s.replace(",", ".")) : null);

  async function pick(file: File | undefined) {
    if (!file) return;
    try {
      setImage(await shrinkImage(file, 1200, 900, false));
    } catch {
      setError("Şəkil oxunmadı");
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      const json = {
        title: f.title, subtitle: f.subtitle || null, badge: f.badge || null, oldPrice: num(f.oldPrice), newPrice: num(f.newPrice),
        color: f.color || null, active: f.active, startsOn: f.startsOn || null, endsOn: f.endsOn || null, sortOrder: promo?.sortOrder ?? 0,
      };
      const saved = await api<Promo>(promo ? `/api/promos/${promo.id}` : "/api/promos", { method: promo ? "PUT" : "POST", json });
      if (image) {
        const fd = new FormData();
        fd.append("file", await dataUrlToBlob(image), "reklam.jpg");
        await api(`/api/promos/${saved.id}/image`, { method: "POST", body: fd });
      }
      onSaved(!promo);
    } catch (err) {
      if (err instanceof ApiError) { setError(err.message); setFields(err.fields ?? {}); } else setError("Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  const preview = {
    id: promo?.id ?? "new", imageVersion: promo?.imageVersion ?? 0, hasImage: !!promo?.hasImage,
    title: f.title, subtitle: f.subtitle || null, badge: f.badge || null, oldPrice: num(f.oldPrice), newPrice: num(f.newPrice), color: f.color,
  };

  return (
    <Modal open size="xl" title={promo ? "Reklamı dəyiş" : "Yeni reklam"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="promo-form" variant="gold" disabled={busy}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="grid gap-6 md:grid-cols-[1fr_340px]">
        <form id="promo-form" onSubmit={submit} className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2"><Field label="Başlıq" error={fields.title}>
            <Input required maxLength={80} value={f.title} placeholder="Qış kolleksiyasına 30% endirim" onChange={(e) => setF({ ...f, title: e.target.value })} />
          </Field></div>
          <div className="sm:col-span-2"><Field label="Mətn" error={fields.subtitle}>
            <Input maxLength={200} value={f.subtitle} placeholder="Bütün paltolar və gödəkçələr, 31 dekabra qədər" onChange={(e) => setF({ ...f, subtitle: e.target.value })} />
          </Field></div>
          <Field label="Köhnə qiymət (₼)" error={fields.oldPrice}><Input inputMode="decimal" value={f.oldPrice} placeholder="100" onChange={(e) => setF({ ...f, oldPrice: e.target.value })} /></Field>
          <Field label="Yeni qiymət (₼)" error={fields.newPrice}><Input inputMode="decimal" value={f.newPrice} placeholder="70" onChange={(e) => setF({ ...f, newPrice: e.target.value })} /></Field>
          <Field label="Nişan" hint="Boş qalsa endirim faizi özü yazılır" error={fields.badge}>
            <Input maxLength={20} value={f.badge} placeholder="-30% / YENİ / HİT" onChange={(e) => setF({ ...f, badge: e.target.value })} />
          </Field>
          <Field label="Şəkil">
            <input type="file" accept="image/png,image/jpeg,image/webp" className="block w-full text-sm" onChange={(e) => pick(e.target.files?.[0])} />
          </Field>
          <Field label="Başlama tarixi" error={fields.startsOn}><Input type="date" value={f.startsOn} onChange={(e) => setF({ ...f, startsOn: e.target.value })} /></Field>
          <Field label="Bitmə tarixi" error={fields.endsOn}><Input type="date" value={f.endsOn} onChange={(e) => setF({ ...f, endsOn: e.target.value })} /></Field>
          <div className="sm:col-span-2">
            <p className="mb-1.5 text-sm font-medium text-slate-700">Rəng</p>
            <div className="flex flex-wrap gap-2">
              {COLORS.map((c) => (
                <button key={c} type="button" aria-label={c} onClick={() => setF({ ...f, color: c })}
                  className={`h-8 w-8 rounded-full ring-offset-2 transition ${f.color === c ? "ring-2 ring-navy-800" : ""}`} style={{ background: c }} />
              ))}
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm text-slate-700 sm:col-span-2">
            <input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />
            Aktivdir (müştərilər görür)
          </label>
        </form>
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Tətbiqdə belə görünəcək</p>
          <PromoPreview p={preview} localImage={image} />
        </div>
      </div>
    </Modal>
  );
}
