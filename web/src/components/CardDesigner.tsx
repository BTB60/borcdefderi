"use client";

import { useState, type ReactNode } from "react";
import { Alert, Button, Input, Modal } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { CardFace, DEFAULT_CARD, cardDesign, shrinkImage, type CardDesign } from "@/components/LoyaltyCard";

const PRESETS: { name: string; c1: string; c2: string; text: string }[] = [
  { name: "Gecə", c1: "#0f1f3d", c2: "#3b1f6b", text: "#ffffff" },
  { name: "Qızıl", c1: "#8a6d1d", c2: "#e9c46a", text: "#1b1b1b" },
  { name: "Gümüş", c1: "#8e9eab", c2: "#eef2f3", text: "#1b1b1b" },
  { name: "Zümrüd", c1: "#064e3b", c2: "#10b981", text: "#ffffff" },
  { name: "Qırmızı", c1: "#7f1d1d", c2: "#ef4444", text: "#ffffff" },
  { name: "Qara", c1: "#000000", c2: "#3f3f46", text: "#f5d27a" },
];

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[130px_1fr] items-center gap-3 text-sm">
      <span className="text-slate-600">{label}</span>
      <div className="flex flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}

/**
 * Kart dizaynı: the store designs its own bonus card — colours or its own picture as background, its logo, text
 * colour and title — with a live preview. Saved with the store, so the till, the printed card and the customer's
 * kabinet all show it.
 */
export function CardDesigner({ initial, store, onClose, onSaved }: { initial?: CardDesign | null; store: string; onClose: () => void; onSaved: (d: CardDesign) => void }) {
  const [d, setD] = useState<Required<CardDesign>>(cardDesign(initial));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<CardDesign>) => setD((cur) => ({ ...cur, ...patch } as Required<CardDesign>));

  async function pick(file: File | undefined, kind: "backgroundImage" | "logo") {
    if (!file) return;
    if (file.size > 8_000_000) { setError("Şəkil çox böyükdür (ən çox 8 MB)"); return; }
    try {
      const url = kind === "logo" ? await shrinkImage(file, 320, 160, true) : await shrinkImage(file, 1012, 638, false);
      set({ [kind]: url } as Partial<CardDesign>);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Şəkil açılmadı");
    }
  }

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await api("/api/settings/print-design", { method: "PUT", json: { card: d } });
      onSaved(d);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title="Bonus kartının dizaynı" onClose={onClose}
      footer={<>
        <Button variant="ghost" onClick={() => setD({ ...DEFAULT_CARD })}>Standarta qaytar</Button>
        <Button variant="ghost" onClick={onClose}>Ləğv et</Button>
        <Button variant="gold" onClick={save} disabled={busy}>{busy ? "Saxlanılır…" : "Saxla"}</Button>
      </>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Row label="Hazır rənglər">
            {PRESETS.map((p) => (
              <button key={p.name} type="button" title={p.name} onClick={() => set({ color1: p.c1, color2: p.c2, textColor: p.text })}
                className={`h-8 w-12 rounded-lg ring-2 ${d.color1 === p.c1 && d.color2 === p.c2 ? "ring-gold-500" : "ring-transparent"}`}
                style={{ background: `linear-gradient(135deg, ${p.c1}, ${p.c2})` }} />
            ))}
          </Row>
          <Row label="Öz rəngləriniz">
            <input type="color" value={d.color1} onChange={(e) => set({ color1: e.target.value })} className="h-8 w-12 cursor-pointer rounded" aria-label="Birinci rəng" />
            <span className="text-slate-400">→</span>
            <input type="color" value={d.color2} onChange={(e) => set({ color2: e.target.value })} className="h-8 w-12 cursor-pointer rounded" aria-label="İkinci rəng" />
          </Row>
          <Row label="Arxa fon şəkli">
            <label className="cursor-pointer rounded-xl border border-slate-300 px-3 py-1.5 text-sm font-medium text-navy-800 hover:border-gold-500">
              {d.backgroundImage ? "Dəyiş" : "Şəkil yüklə"}
              <input type="file" accept="image/png,image/jpeg,image/webp" className="hidden" onChange={(e) => void pick(e.target.files?.[0], "backgroundImage")} />
            </label>
            {d.backgroundImage && <button type="button" className="text-sm text-rose-600 hover:underline" onClick={() => set({ backgroundImage: null })}>Sil</button>}
          </Row>
          {d.backgroundImage && (
            <Row label="Şəkli tündləşdir">
              <input type="range" min={0} max={80} step={5} value={d.shade} onChange={(e) => set({ shade: Number(e.target.value) })} className="flex-1" />
              <span className="w-10 text-right">{d.shade}%</span>
            </Row>
          )}
          <Row label="Loqo">
            {/* eslint-disable-next-line @next/next/no-img-element -- the picked logo (data URL) */}
            {d.logo && <img src={d.logo} alt="" className="h-8 max-w-[100px] rounded border border-slate-200 object-contain" />}
            <label className="cursor-pointer rounded-xl border border-slate-300 px-3 py-1.5 text-sm font-medium text-navy-800 hover:border-gold-500">
              {d.logo ? "Dəyiş" : "Loqo yüklə"}
              <input type="file" accept="image/png,image/jpeg,image/webp" className="hidden" onChange={(e) => void pick(e.target.files?.[0], "logo")} />
            </label>
            {d.logo && <button type="button" className="text-sm text-rose-600 hover:underline" onClick={() => set({ logo: null })}>Sil</button>}
          </Row>
          <Row label="Yazı rəngi">
            <input type="color" value={d.textColor} onChange={(e) => set({ textColor: e.target.value })} className="h-8 w-12 cursor-pointer rounded" aria-label="Yazı rəngi" />
          </Row>
          <Row label="Kartın başlığı">
            <Input value={d.title} maxLength={30} onChange={(e) => set({ title: e.target.value })} placeholder="Bonus kartı, VIP kart, Gold…" className="max-w-xs" />
          </Row>
          <Row label="Kartda göstər">
            <label className="flex items-center gap-2"><input type="checkbox" checked={d.showStore} onChange={(e) => set({ showStore: e.target.checked })} /> Mağaza adı</label>
            <label className="flex items-center gap-2"><input type="checkbox" checked={d.showPercent} onChange={(e) => set({ showPercent: e.target.checked })} /> Bonus faizi</label>
          </Row>
          <p className="text-xs text-slate-500">Şəkillər yüklənəndə avtomatik kiçildilir. Arxa fon üçün 1012×638 ölçülü (kart nisbətində) şəkil ən yaxşı görünür. Barkod sahəsi həmişə ağ qalır ki, kassada oxunsun.</p>
        </div>
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Önizləmə</p>
          <CardFace design={d} store={store || "Mağazanın adı"} name="Əliyeva Aynur" code="HP-000001" percent={5} balance={12.5} />
          <p className="mt-3 text-xs text-slate-500">Bu dizayn çap olunan kartda, proqramdakı kart görünüşündə və müştərinin kabinetində eyni görünür.</p>
        </div>
      </div>
    </Modal>
  );
}
