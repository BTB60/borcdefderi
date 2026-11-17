"use client";

import { useEffect, useState } from "react";
import { Button, Card, Input, Select } from "@/components/ui";
import { desktop, type ScaleConfig, type ScaleProtocol, type ScaleReading } from "@/lib/desktop";
import { useScale } from "@/components/sales/useScale";

const BAUDS = [1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200];
const FORMATS = [["8N1", "8 bit, cütlük yox, 1 stop (8N1)"], ["7E1", "7 bit, cüt (7E1)"], ["8E1", "8 bit, cüt (8E1)"], ["7O1", "7 bit, tək (7O1)"], ["8O1", "8 bit, tək (8O1)"], ["8N2", "8 bit, 2 stop (8N2)"]];

/** Live weight as text: "1,250 kq ✓", "tərpənir…", the error. */
export function scaleText(r: ScaleReading | null): { text: string; tone: "ok" | "wait" | "err" } {
  if (!r) return { text: "Gözlənilir…", tone: "wait" };
  if (r.error) return { text: r.error, tone: "err" };
  if (r.kg == null) return { text: r.open ? "Qoşuldu — malı qoyun" : "Tərəzi tərpənir…", tone: "wait" };
  const kg = r.kg.toLocaleString("az-AZ", { minimumFractionDigits: 3, maximumFractionDigits: 3 });
  return { text: `${kg} kq${r.stable ? " ✓ sabit" : " · tərpənir…"}`, tone: r.stable ? "ok" : "wait" };
}

/**
 * ⚖ Tərəzi in the kassa Parametrlər: the COM port, the model (protocol), speed and data format, and the live weight
 * so the store can see at once that the scale talks to the till.
 */
export function ScaleSettings() {
  const app = desktop();
  const [protocols, setProtocols] = useState<ScaleProtocol[]>([]);
  const [ports, setPorts] = useState<string[]>([]);
  const [cfg, setCfg] = useState<ScaleConfig>({ port: "", protocol: "auto", baud: 9600, format: "8N1", unit: "auto", command: "" });
  const [saved, setSaved] = useState(false);
  const reading = useScale();

  useEffect(() => {
    if (!app?.scaleProtocols) return;
    app.scaleProtocols().then(setProtocols).catch(() => undefined);
    app.scalePorts?.().then(setPorts).catch(() => undefined);
    app.getScale?.().then((c) => { if (c) setCfg(c); }).catch(() => undefined);
  }, [app]);

  if (!app?.scaleProtocols) return null; // older app version: the section shows after the app updates

  const pickProtocol = (id: string) => {
    const p = protocols.find((x) => x.id === id);
    setCfg((c) => ({ ...c, protocol: id, ...(p ? { baud: p.baud, format: p.format } : {}) }));
    setSaved(false);
  };
  const patch = (p: Partial<ScaleConfig>) => { setCfg((c) => ({ ...c, ...p })); setSaved(false); };

  async function save(off = false) {
    const next = await app?.setScale?.(off ? null : cfg);
    if (off) setCfg((c) => ({ ...c, port: "" }));
    else if (next) setCfg(next);
    setSaved(true);
  }

  const live = scaleText(reading);
  const tone = live.tone === "ok" ? "bg-emerald-50 text-emerald-700 ring-emerald-200" : live.tone === "err" ? "bg-rose-50 text-rose-700 ring-rose-200" : "bg-slate-50 text-slate-600 ring-slate-200";

  return (
    <Card className="lg:col-span-2">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-2 font-serif text-lg font-semibold text-navy-800"><span aria-hidden="true">⚖</span>Tərəzi (kabellə)</h2>
        {saved && <span className="text-sm text-emerald-700">Yadda saxlandı</span>}
      </div>
      <p className="mb-4 text-sm text-slate-500">
        Tərəzini kompüterə COM (RS-232) və ya USB-COM kabellə qoşun, aşağıda portu və modeli seçin.
        Kq / qram ilə satılan mala toxunanda çəki tərəzidən özü yazılacaq.
      </p>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-3">
          <label className="block text-sm text-slate-700">Port
            <div className="mt-1 flex gap-2">
              <Select value={cfg.port} onChange={(e) => patch({ port: e.target.value })} className="flex-1">
                <option value="">— seçin —</option>
                {[...new Set([...ports, ...(cfg.port ? [cfg.port] : [])])].map((p) => <option key={p} value={p}>{p}</option>)}
              </Select>
              <Button variant="ghost" onClick={() => void app.scalePorts?.().then(setPorts)}>Yenilə</Button>
            </div>
            {ports.length === 0 && <span className="mt-1 block text-xs text-amber-700">Port tapılmadı — kabeli qoşun, USB-COM üçün drayveri quraşdırın və “Yenilə” basın</span>}
          </label>
          <label className="block text-sm text-slate-700">Tərəzinin modeli / protokol
            <Select value={cfg.protocol} onChange={(e) => pickProtocol(e.target.value)} className="mt-1">
              {protocols.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
            </Select>
          </label>
          {cfg.protocol === "custom" && (
            <label className="block text-sm text-slate-700">Sorğu əmri
              <Input value={cfg.command} maxLength={60} onChange={(e) => patch({ command: e.target.value })} placeholder="məs. W  ·  P\r\n  ·  <ENQ>  ·  \x05" className="mt-1 font-mono" />
              <span className="mt-1 block text-xs text-slate-500">Tərəzinin təlimatındakı “çəkini göndər” əmri. \r, \n, \x05, &lt;ENQ&gt; yazmaq olar; boş qalsa, tərəzi özü göndərir.</span>
            </label>
          )}
          <div className="grid grid-cols-2 gap-3">
            <label className="block text-sm text-slate-700">Sürət (baud)
              <Select value={String(cfg.baud)} onChange={(e) => patch({ baud: Number(e.target.value) })} className="mt-1">
                {BAUDS.map((b) => <option key={b} value={b}>{b}</option>)}
              </Select>
            </label>
            <label className="block text-sm text-slate-700">Çəki vahidi
              <Select value={cfg.unit} onChange={(e) => patch({ unit: e.target.value as ScaleConfig["unit"] })} className="mt-1">
                <option value="auto">Avtomatik</option>
                <option value="kg">kq</option>
                <option value="g">qram</option>
                <option value="lb">funt (lb)</option>
              </Select>
            </label>
          </div>
          <label className="block text-sm text-slate-700">Məlumat formatı
            <Select value={cfg.format} onChange={(e) => patch({ format: e.target.value })} className="mt-1">
              {FORMATS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </Select>
          </label>
          <div className="flex flex-wrap gap-2 pt-1">
            <Button variant="gold" disabled={!cfg.port} onClick={() => void save()}>Yadda saxla və qoşul</Button>
            <Button variant="ghost" onClick={() => void save(true)}>Söndür</Button>
          </div>
        </div>
        <div className="space-y-3">
          <div className={`rounded-2xl px-5 py-6 text-center ring-1 ${tone}`}>
            <p className="text-xs font-semibold uppercase tracking-wider opacity-70">Tərəzidə indi</p>
            <p className="mt-1 font-serif text-3xl font-semibold tabular-nums">{live.text}</p>
          </div>
          <ul className="list-disc space-y-1 pl-5 text-xs text-slate-500">
            <li>Model siyahıda yoxdursa, əvvəlcə <b>Avtomatik</b> seçin — çox tərəzi çəkini özü göndərir.</li>
            <li>Çəki görünmürsə: tərəzinin menyusunda göndərmə rejimini (RS-232, “continuous” / “davamlı”) və sürəti yoxlayın; sürət hər iki tərəfdə eyni olmalıdır.</li>
            <li>Rəqəmlər səhv görünürsə, <b>Məlumat formatı</b>nı dəyişin (çox vaxt 8N1 və ya 7E1).</li>
            <li>Çəki 1000 dəfə böyük və ya kiçik çıxırsa, <b>Çəki vahidi</b>ni kq və ya qram seçin.</li>
          </ul>
        </div>
      </div>
    </Card>
  );
}
