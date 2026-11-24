"use client";

/* eslint-disable @next/next/no-img-element -- the card is drawn as SVG and shown as a data-URL picture */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, EmptyState, Field, Input, PageHeader, Spinner, useConfirm } from "@/components/ui";
import { API_URL, ApiError, api, getToken } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatMoney } from "@/lib/format";
import {
  BACKGROUNDS, CARD_H, CARD_W, CATEGORIES, CHARACTER_LIBRARY, FONTS, ICONS, LAYOUTS, SAMPLE,
  generateLocal, rasterize, renderBack, renderFront, toDataUrl,
  type CardData, type CategoryId, type IconId, type Spec,
} from "@/components/studio/design";

type Saved = { id: string; name: string; category: string | null; spec: Spec; updatedAt: string };
type Status = { ai: boolean; usedToday: number; dailyLimit: number };
type AiCard = {
  name: string; background: Spec["background"]; color1: string; color2: string; accent: string; textColor: string;
  font: Spec["font"]; layout: Spec["layout"]; icon: IconId; tagline: string; characterSvg: string | null;
};
type LoyaltyCard = { id: string; number: string; fullName: string; balance: number; active: boolean };

/** 300 DPI with 3 mm bleed: (85.60 + 6) × (53.98 + 6) mm. */
const PRINT_W = Math.round(((85.6 + 6) / 25.4) * 300), PRINT_H = Math.round(((53.98 + 6) / 25.4) * 300);

function download(url: string, name: string) {
  const a = document.createElement("a");
  a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
}

/** Bonus Kart Design Studio: generate (AI or built-in), edit, save, print and use as the store's card. */
export default function CardStudioPage() {
  const { isOwner, can } = useAuth();
  const allowed = isOwner || can("SALES") || can("TILL_SETTINGS");
  const [category, setCategory] = useState<CategoryId>("funny");
  const [spec, setSpec] = useState<Spec>(() => generateLocal("funny"));
  const [version, setVersion] = useState(0);
  const [side, setSide] = useState<"front" | "back">("front");
  const [data, setData] = useState<CardData>(SAMPLE);
  const [qr, setQr] = useState<string | null>(null);
  const [status, setStatus] = useState<Status | null>(null);
  const [idea, setIdea] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saved, setSaved] = useState<Saved[] | null>(null);
  const [editing, setEditing] = useState<Saved | null>(null);
  const [cards, setCards] = useState<LoyaltyCard[]>([]);
  const [cmyk, setCmyk] = useState(false);
  const [confirm, confirmDialog] = useConfirm();
  const set = (p: Partial<Spec>) => setSpec((s) => ({ ...s, ...p, source: s.source === "ai" && !("characterSvg" in p) ? "ai" : s.source }));

  const loadSaved = useCallback(() => {
    api<Saved[]>("/api/card-studio/designs").then(setSaved).catch(() => setSaved([]));
  }, []);
  useEffect(() => {
    if (!allowed) return;
    loadSaved();
    api<Status>("/api/card-studio/status").then(setStatus).catch(() => setStatus({ ai: false, usedToday: 0, dailyLimit: 0 }));
    api<LoyaltyCard[]>("/api/loyalty-cards").then((l) => setCards(l.filter((c) => c.active))).catch(() => undefined);
  }, [allowed, loadSaved]);

  // the QR of the card shown (the card number the till scans)
  useEffect(() => {
    let off = false;
    fetch(`${API_URL}/api/public/qr.svg?text=${encodeURIComponent(data.qrText)}`)
      .then((r) => (r.ok ? r.text() : Promise.reject()))
      .then((svg) => { if (!off) setQr(toDataUrl(svg)); })
      .catch(() => { if (!off) setQr(null); });
    return () => { off = true; };
  }, [data.qrText]);

  const front = useMemo(() => renderFront(spec, data, { qr }), [spec, data, qr]);
  const back = useMemo(() => renderBack(spec, data, { qr }), [spec, data, qr]);

  if (!allowed) return <Card><EmptyState title="Bu bölmə üçün icazəniz yoxdur" /></Card>;

  async function generate() {
    setError(null); setNotice(null); setBusy("generate"); setEditing(null);
    const started = Date.now();
    let next: Spec | null = null;
    if (status?.ai) {
      try {
        const res = await api<{ card: AiCard; usedToday: number; dailyLimit: number }>("/api/card-studio/generate", {
          method: "POST", json: { category: CATEGORIES.find((c) => c.id === category)?.prompt, idea: idea.trim() || null },
        });
        const c = res.card, local = generateLocal(category);
        next = {
          ...local, name: c.name, background: c.background, color1: c.color1, color2: c.color2, accent: c.accent, textColor: c.textColor,
          font: c.font, layout: c.layout, icon: c.icon, tagline: c.tagline,
          character: c.characterSvg ? "ai" : local.character, characterSvg: c.characterSvg, characterScale: 0.95, source: "ai",
        };
        setStatus((s) => (s ? { ...s, usedToday: res.usedToday } : s));
      } catch (e) {
        if (e instanceof ApiError && (e.code === "AI_OFF" || e.code === "AI_LIMIT")) setNotice(e.message);
        else setError(e instanceof ApiError ? e.message : "AI dizayn hazırlaya bilmədi");
      }
    }
    if (!next) next = generateLocal(category, spec);
    // a short pause so the change is seen as a new design dropping in
    const wait = Math.max(0, 700 - (Date.now() - started));
    setTimeout(() => { setSpec(next!); setVersion((v) => v + 1); setSide("front"); setBusy(null); }, wait);
  }

  async function save(asNew: boolean) {
    setError(null); setBusy("save");
    try {
      const body = { name: spec.name.trim() || "Bonus kart", category: spec.category, spec };
      const res = editing && !asNew
        ? await api<Saved>(`/api/card-studio/designs/${editing.id}`, { method: "PUT", json: body })
        : await api<Saved>("/api/card-studio/designs", { method: "POST", json: body });
      setEditing(res); setNotice(`“${res.name}” yadda saxlanıldı`); loadSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yadda saxlanmadı");
    } finally {
      setBusy(null);
    }
  }

  async function remove(d: Saved) {
    if (!(await confirm(`“${d.name}” dizaynı silinsin?`, { confirmLabel: "Sil", danger: true }))) return;
    try {
      await api(`/api/card-studio/designs/${d.id}`, { method: "DELETE" });
      if (editing?.id === d.id) setEditing(null);
      loadSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Silinmədi");
    }
  }

  async function exportAs(kind: "png" | "jpg" | "svg" | "pdf") {
    setError(null); setBusy(`export-${kind}`);
    const base = (spec.name || "bonus-kart").replace(/[^\p{L}\p{N}]+/gu, "-").toLowerCase();
    try {
      const f = renderFront(spec, data, { qr, bleed: true }), b = renderBack(spec, data, { qr, bleed: true });
      if (kind === "svg") {
        download(toDataUrl(f), `${base}-on.svg`); download(toDataUrl(b), `${base}-arxa.svg`);
      } else if (kind === "pdf") {
        const [fp, bp] = await Promise.all([rasterize(f, PRINT_W, PRINT_H, "image/png"), rasterize(b, PRINT_W, PRINT_H, "image/png")]);
        const res = await fetch(`${API_URL}/api/card-studio/pdf`, {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${getToken() ?? ""}` },
          body: JSON.stringify({ front: fp, back: bp, cmyk, name: spec.name }),
        });
        if (!res.ok) throw new Error("PDF hazırlanmadı");
        const url = URL.createObjectURL(await res.blob());
        download(url, `${base}-cap.pdf`);
        setTimeout(() => URL.revokeObjectURL(url), 5000);
      } else {
        const type = kind === "png" ? "image/png" : "image/jpeg";
        const [fp, bp] = await Promise.all([rasterize(f, PRINT_W, PRINT_H, type), rasterize(b, PRINT_W, PRINT_H, type)]);
        download(fp, `${base}-on.${kind}`); download(bp, `${base}-arxa.${kind}`);
      }
      setNotice(kind === "pdf" ? "Çap PDF-i hazırdır: 2 səhifə (ön və arxa), 85,60 × 53,98 mm + 3 mm bleed, 300 DPI" : "Ön və arxa tərəf yükləndi (300 DPI, 3 mm bleed ilə)");
    } catch (e) {
      setError(e instanceof Error ? e.message : "İxrac alınmadı");
    } finally {
      setBusy(null);
    }
  }

  async function useAsStoreCard() {
    if (!(await confirm("Bu dizayn mağazanızın bonus kartı olsun? Müştərilər onu HesabPro Kart tətbiqində və çap olunan kartda görəcək.", { confirmLabel: "Bəli, istifadə et" }))) return;
    setError(null); setBusy("apply");
    try {
      // the picture without the texts: the app and the printed card write the real name, number and balance on it
      const picture = await rasterize(renderFront(spec, SAMPLE, { textless: true }), CARD_W, CARD_H, "image/jpeg", 0.86);
      const current = await api<{ card?: Record<string, unknown> }>("/api/settings/print-design");
      await api("/api/settings/print-design", {
        method: "PUT",
        json: { card: { ...(current.card ?? {}), color1: spec.color1, color2: spec.color2, textColor: spec.textColor, title: spec.title || "BONUS KART", backgroundImage: picture, shade: 12 } },
      });
      setNotice("Hazırdır — bu dizayn artıq mağazanızın bonus kartıdır");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Tətbiq olunmadı");
    } finally {
      setBusy(null);
    }
  }

  const chosenChar = spec.character;
  const pickCard = (id: string) => {
    const c = cards.find((x) => x.id === id);
    setData(c ? { name: c.fullName, number: c.number, balance: formatMoney(c.balance), validUntil: data.validUntil, qrText: c.number } : { ...SAMPLE, validUntil: data.validUntil });
  };

  return (
    <>
      <PageHeader title="Bonus Kart Studio" subtitle="Bir kliklə yeni bonus kart dizaynı — redaktə edin, yadda saxlayın, çap edin və ya mağazanızın kartı edin."
        actions={<span className={`rounded-full px-3 py-1 text-xs font-semibold ${status?.ai ? "bg-violet-100 text-violet-700" : "bg-slate-100 text-slate-600"}`}>
          {status == null ? "…" : status.ai ? `✨ AI dizayner · bu gün ${status.usedToday}/${status.dailyLimit}` : "Daxili generator (AI qoşulmayıb)"}
        </span>} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}

      <div className="grid gap-5 lg:grid-cols-[250px_minmax(0,1fr)_300px]">
        {/* ---------------- left: categories, generate, colours, characters, saved ---------------- */}
        <div className="space-y-4">
          <Card>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Kateqoriya</p>
            <div className="grid grid-cols-2 gap-1.5 lg:grid-cols-1">
              {CATEGORIES.map((c) => (
                <button key={c.id} type="button" onClick={() => setCategory(c.id)}
                  className={`flex items-center gap-2 rounded-xl px-3 py-2 text-left text-sm transition ${category === c.id ? "bg-violet-600 text-white shadow" : "hover:bg-slate-100"}`}>
                  <span className="text-lg">{c.emoji}</span><span className="leading-tight">{c.label}</span>
                </button>
              ))}
            </div>
            {status?.ai && (
              <Input className="mt-3" maxLength={200} value={idea} placeholder="İstəyiniz (məs. pizza, yay, pişik)…" onChange={(e) => setIdea(e.target.value)} />
            )}
            <button type="button" onClick={generate} disabled={busy === "generate"}
              className="studio-gen mt-3 w-full rounded-2xl px-4 py-3 text-sm font-bold text-white shadow-lg disabled:opacity-70">
              {busy === "generate" ? (status?.ai ? "AI dizayn hazırlayır…" : "Hazırlanır…") : "✨ Yeni dizayn yarat"}
            </button>
          </Card>

          <Card>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Rənglər</p>
            <div className="grid grid-cols-2 gap-2 text-xs">
              {([["color1", "Fon 1"], ["color2", "Fon 2"], ["accent", "Vurğu"], ["textColor", "Yazı"]] as const).map(([k, l]) => (
                <label key={k} className="flex items-center gap-2 rounded-lg border border-slate-200 px-2 py-1.5">
                  <input type="color" value={spec[k]} onChange={(e) => set({ [k]: e.target.value } as Partial<Spec>)} className="h-6 w-8 cursor-pointer border-0 bg-transparent p-0" />
                  {l}
                </label>
              ))}
            </div>
            <p className="mb-2 mt-4 text-xs font-semibold uppercase tracking-wide text-slate-500">Personaj</p>
            <div className="grid grid-cols-4 gap-1.5">
              {spec.characterSvg && (
                <button type="button" title="AI personajı" onClick={() => set({ character: "ai" })}
                  className={`rounded-xl border p-1 ${chosenChar === "ai" ? "border-violet-600 ring-2 ring-violet-200" : "border-slate-200"}`}>
                  <img alt="AI" src={toDataUrl(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 240">${spec.characterSvg}</svg>`)} className="aspect-square w-full" />
                </button>
              )}
              {CHARACTER_LIBRARY.map((c) => (
                <button key={c.id} type="button" title={c.name} onClick={() => set({ character: c.id })}
                  className={`rounded-xl border p-1 transition hover:scale-105 ${chosenChar === c.id ? "border-violet-600 ring-2 ring-violet-200" : "border-slate-200"}`}>
                  <img alt={c.name} src={toDataUrl(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 240">${c.svg}</svg>`)} className="aspect-square w-full" />
                </button>
              ))}
              <button type="button" onClick={() => set({ character: "none" })}
                className={`rounded-xl border p-1 text-[10px] text-slate-500 ${chosenChar === "none" ? "border-violet-600 ring-2 ring-violet-200" : "border-slate-200"}`}>Yox</button>
            </div>
          </Card>

          <Card>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Saxlanmış dizaynlar</p>
            {!saved ? <Spinner /> : saved.length === 0 ? <p className="text-sm text-slate-500">Hələ yoxdur. Bəyəndiyiniz dizaynı “Yadda saxla” ilə saxlayın.</p> : (
              <div className="grid grid-cols-2 gap-2 lg:grid-cols-1">
                {saved.map((d) => (
                  <div key={d.id} className={`group relative rounded-xl border p-1.5 ${editing?.id === d.id ? "border-violet-600" : "border-slate-200"}`}>
                    <button type="button" className="block w-full text-left" onClick={() => { setSpec(d.spec); setEditing(d); setVersion((v) => v + 1); setNotice(null); }}>
                      <img alt={d.name} src={toDataUrl(renderFront(d.spec, SAMPLE))} className="w-full rounded-lg" />
                      <span className="mt-1 block truncate text-xs font-medium text-navy-800">{d.name}</span>
                    </button>
                    <button type="button" onClick={() => remove(d)} className="absolute right-2 top-2 hidden rounded-full bg-white/90 px-2 text-xs text-rose-600 shadow group-hover:block">Sil</button>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </div>

        {/* ---------------- centre: the big preview, front / back (first on a phone) ---------------- */}
        <div className="order-first min-w-0 lg:order-none">
          <Card>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
              <div className="inline-flex rounded-xl bg-slate-100 p-1 text-sm">
                {(["front", "back"] as const).map((s) => (
                  <button key={s} type="button" onClick={() => setSide(s)}
                    className={`rounded-lg px-4 py-1.5 font-medium transition ${side === s ? "bg-white text-navy-800 shadow" : "text-slate-500"}`}>
                    {s === "front" ? "Ön tərəf" : "Arxa tərəf"}
                  </button>
                ))}
              </div>
              <span className="text-xs text-slate-500">{spec.source === "ai" ? "✨ AI ilə yaradılıb" : spec.source === "local" ? "Avtomatik generator" : ""} · 85,60 × 53,98 mm</span>
            </div>
            <div className="studio-stage relative mx-auto max-w-[640px]" style={{ perspective: "1600px" }}>
              <div key={version} className={`studio-card relative ${busy === "generate" ? "studio-busy" : "studio-in"}`}
                style={{ aspectRatio: `${CARD_W} / ${CARD_H}`, transformStyle: "preserve-3d", transition: "transform .8s cubic-bezier(.2,.8,.2,1)", transform: side === "back" ? "rotateY(180deg)" : "none" }}>
                <img alt="Kartın ön tərəfi" src={toDataUrl(front)} className="absolute inset-0 h-full w-full rounded-[28px] shadow-2xl" style={{ backfaceVisibility: "hidden" }} />
                <img alt="Kartın arxa tərəfi" src={toDataUrl(back)} className="absolute inset-0 h-full w-full rounded-[28px] shadow-2xl" style={{ backfaceVisibility: "hidden", transform: "rotateY(180deg)" }} />
                <div className="studio-shine pointer-events-none absolute inset-0 rounded-[28px]" />
              </div>
              {busy === "generate" && (
                <div className="absolute inset-0 flex items-center justify-center">
                  <div className="rounded-2xl bg-white/90 px-5 py-3 text-sm font-semibold text-violet-700 shadow-lg">{status?.ai ? "✨ AI yeni dizayn çəkir…" : "Yeni dizayn…"}</div>
                </div>
              )}
            </div>
            <p className="mt-4 text-center text-xs text-slate-500">Karta baxmaq üçün “Ön / Arxa” düymələrindən istifadə edin. Çapda 3 mm bleed avtomatik əlavə olunur.</p>
          </Card>
        </div>

        {/* ---------------- right: parameters, texts, export ---------------- */}
        <div className="space-y-4">
          <Card>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Kart məlumatları</p>
            <Field label="Müştəri">
              <select className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm" onChange={(e) => pickCard(e.target.value)} defaultValue="">
                <option value="">Nümunə (Ad Soyad)</option>
                {cards.map((c) => <option key={c.id} value={c.id}>{c.fullName} · {c.number}</option>)}
              </select>
            </Field>
            <div className="mt-2"><Field label="Etibarlılıq tarixi"><Input value={data.validUntil} maxLength={20} onChange={(e) => setData({ ...data, validUntil: e.target.value })} /></Field></div>
            <p className="mt-2 text-xs text-slate-500">QR kodda kartın nömrəsi var — kassada skan edəndə müştərinin bonus kartı açılır.</p>
          </Card>

          <Card>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Mətn və üslub</p>
            <div className="space-y-2">
              <Field label="Dizaynın adı"><Input value={spec.name} maxLength={80} onChange={(e) => set({ name: e.target.value })} /></Field>
              <Field label="Başlıq"><Input value={spec.title} maxLength={24} onChange={(e) => set({ title: e.target.value })} /></Field>
              <Field label="Alt yazı"><Input value={spec.tagline} maxLength={32} onChange={(e) => set({ tagline: e.target.value })} /></Field>
              <Field label="Arxa tərəfdə qeyd"><Input value={spec.backNote} maxLength={140} onChange={(e) => set({ backNote: e.target.value })} /></Field>
              <div className="grid grid-cols-2 gap-2">
                <Sel label="Şrift" value={spec.font} options={FONTS.map((f) => [f.id, f.label])} onChange={(v) => set({ font: v as Spec["font"] })} />
                <Sel label="İkon" value={spec.icon} options={[...Object.keys(ICONS).map((k) => [k, k]), ["none", "yox"]] as [string, string][]} onChange={(v) => set({ icon: v as IconId })} />
                <Sel label="Kompozisiya" value={spec.layout} options={LAYOUTS.map((l) => [l.id, l.label])} onChange={(v) => set({ layout: v as Spec["layout"] })} />
                <Sel label="Arxa fon" value={spec.background} options={BACKGROUNDS.map((b) => [b.id, b.label])} onChange={(v) => set({ background: v as Spec["background"] })} />
                <Sel label="Personaj tərəfi" value={spec.characterSide} options={[["right", "Sağda"], ["left", "Solda"]]} onChange={(v) => set({ characterSide: v as Spec["characterSide"] })} />
                <Sel label="QR (ön)" value={spec.qrFront} options={[["none", "Yox"], ["right", "Sağda"], ["left", "Solda"]]} onChange={(v) => set({ qrFront: v as Spec["qrFront"] })} />
                <Sel label="QR (arxa)" value={spec.qrBack} options={[["center", "Mərkəzdə"], ["left", "Solda"], ["right", "Sağda"]]} onChange={(v) => set({ qrBack: v as Spec["qrBack"] })} />
              </div>
              <label className="block text-sm text-slate-700">Personajın ölçüsü
                <input type="range" min={0.6} max={1.2} step={0.05} value={spec.characterScale} onChange={(e) => set({ characterScale: Number(e.target.value) })} className="mt-1 w-full accent-violet-600" />
              </label>
            </div>
          </Card>

          <Card>
            <div className="grid grid-cols-2 gap-2">
              <Button variant="gold" disabled={!!busy} onClick={() => save(false)}>{editing ? "Yadda saxla" : "Yadda saxla"}</Button>
              <Button variant="outline" disabled={!!busy} onClick={() => save(true)}>Yeni kimi saxla</Button>
            </div>
            <Button className="mt-2 w-full" disabled={!!busy} onClick={useAsStoreCard}>{busy === "apply" ? "Tətbiq olunur…" : "Mağazamın kartı et"}</Button>
            <p className="mb-2 mt-4 text-xs font-semibold uppercase tracking-wide text-slate-500">İxrac və çap</p>
            <div className="grid grid-cols-4 gap-1.5">
              {(["png", "jpg", "svg", "pdf"] as const).map((k) => (
                <button key={k} type="button" disabled={!!busy} onClick={() => exportAs(k)}
                  className="rounded-xl border border-slate-200 py-2 text-xs font-bold uppercase text-navy-800 transition hover:border-violet-400 hover:bg-violet-50 disabled:opacity-50">
                  {busy === `export-${k}` ? "…" : k}
                </button>
              ))}
            </div>
            <label className="mt-2 flex items-center gap-2 text-xs text-slate-600">
              <input type="checkbox" checked={cmyk} onChange={(e) => setCmyk(e.target.checked)} /> PDF-i CMYK rəng rejimində hazırla
            </label>
            <p className="mt-2 text-[11px] leading-snug text-slate-400">
              Çap ölçüsü: 85,60 × 53,98 mm, 300 DPI, hər tərəfdə 3 mm bleed (kəsim xətti PDF-də “trim box” kimi qeyd olunur). CMYK sadə çevirmədir (ICC profilsiz) — çapdan əvvəl mətbəədən sınaq nüsxəsi istəyin.
            </p>
          </Card>
        </div>
      </div>

      <style>{`
        .studio-gen { background: linear-gradient(110deg, #7c3aed, #db2777, #f59e0b, #7c3aed); background-size: 300% 100%; animation: studioGen 6s linear infinite; }
        .studio-in { animation: studioIn .7s cubic-bezier(.2,.9,.3,1.2); }
        .studio-busy { filter: blur(6px) saturate(.6); transform: scale(.96); transition: filter .4s, transform .4s; }
        .studio-shine { background: linear-gradient(115deg, transparent 35%, rgba(255,255,255,.28) 50%, transparent 65%); background-size: 250% 100%; animation: studioShine 4.5s ease-in-out infinite; mix-blend-mode: soft-light; }
        @keyframes studioGen { to { background-position: -300% 0; } }
        @keyframes studioIn { from { opacity: 0; transform: translateY(24px) scale(.92) rotateX(12deg); } to { opacity: 1; transform: none; } }
        @keyframes studioShine { 0%, 55% { background-position: 150% 0; } 100% { background-position: -100% 0; } }
        @media (prefers-reduced-motion: reduce) { .studio-gen, .studio-in, .studio-shine { animation: none; } }
      `}</style>
      {confirmDialog}
    </>
  );
}

function Sel({ label, value, options, onChange }: { label: string; value: string; options: [string, string][]; onChange: (v: string) => void }) {
  return (
    <label className="block text-xs text-slate-600">{label}
      <select value={value} onChange={(e) => onChange(e.target.value)} className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-sm">
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
    </label>
  );
}

