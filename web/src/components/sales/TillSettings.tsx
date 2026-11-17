"use client";

import { useEffect, useState, type ReactNode } from "react";
import { Button, Card, Select } from "@/components/ui";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { desktop, receiptSettings, saveReceiptSettings, type UpdateStatus } from "@/lib/desktop";
import type { Product } from "@/lib/types";
import { SetPinModal } from "@/components/sales/SetPin";
import { TillLabelsModal } from "@/components/sales/TillLabels";
import { lockMinutes, setLockMinutes } from "@/components/sales/TillLock";

const AUTO_PRINT_KEY = "pos_auto_print";

function Row({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2 py-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <p className="text-sm font-medium text-navy-800">{title}</p>
        {hint && <p className="text-xs text-slate-500">{hint}</p>}
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}

function Toggle({ on, onChange, label }: { on: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button type="button" role="switch" aria-checked={on} aria-label={label} onClick={() => onChange(!on)}
      className={`relative h-7 w-12 rounded-full transition ${on ? "bg-emerald-500" : "bg-slate-300"}`}>
      <span className={`absolute top-0.5 h-6 w-6 rounded-full bg-white shadow transition-all ${on ? "left-[22px]" : "left-0.5"}`} />
    </button>
  );
}

function Section({ icon, title, children }: { icon: string; title: string; children: ReactNode }) {
  return (
    <Card>
      <h2 className="mb-1 flex items-center gap-2 font-serif text-lg font-semibold text-navy-800"><span aria-hidden="true">{icon}</span>{title}</h2>
      <div className="divide-y divide-slate-100">{children}</div>
    </Card>
  );
}

/**
 * Parametrlər of the Windows kassa: printers and receipt, cash drawer, customer display, PIN and screen lock,
 * touch mode, start with Windows, app version / update and the sales waiting to be sent — all in one place.
 */
export function TillSettings() {
  const app = desktop();
  const { me } = useAuth();
  const [printers, setPrinters] = useState<{ name: string; isDefault: boolean }[]>([]);
  const [printer, setPrinter] = useState("");
  const [labelPrinter, setLabelPrinter] = useState("");
  const [paper, setPaper] = useState<"80" | "58">("80");
  const [footer, setFooter] = useState("Təşəkkür edirik!");
  const [autoPrint, setAutoPrint] = useState(true);
  const [touch, setTouch] = useState(false);
  const [drawerAuto, setDrawerAuto] = useState(false);
  const [autoStart, setAutoStart] = useState<boolean | null>(null);
  const [lockMin, setLockMin] = useState(5);
  const [pending, setPending] = useState<{ count: number; failed: { total: number; error: string; savedAt: string }[] }>({ count: 0, failed: [] });
  const [update, setUpdate] = useState<UpdateStatus | null>(null);
  const [pinOpen, setPinOpen] = useState(false);
  const [labels, setLabels] = useState<Product[] | null>(null);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    const r = receiptSettings();
    setPaper(r.paper);
    setFooter(r.footer);
    setLockMin(lockMinutes());
    try {
      setAutoPrint(localStorage.getItem(AUTO_PRINT_KEY) !== "false");
      setTouch(localStorage.getItem("hp_touch") === "1");
    } catch { /* ignore */ }
    if (!app) return;
    app.getPrinters().then(setPrinters).catch(() => undefined);
    app.getPrinter().then((p) => setPrinter(p ?? "")).catch(() => undefined);
    app.getLabelPrinter?.().then((p) => setLabelPrinter(p ?? "")).catch(() => undefined);
    app.getDrawerAuto?.().then(setDrawerAuto).catch(() => undefined);
    app.getAutoStart?.().then(setAutoStart).catch(() => undefined);
    app.pending().then(setPending).catch(() => undefined);
    app.updateStatus?.().then(setUpdate).catch(() => undefined);
    const offP = app.onPendingChange(() => { app.pending().then(setPending).catch(() => undefined); });
    const offU = app.onUpdate?.(setUpdate);
    return () => { offP(); offU?.(); };
  }, [app]);

  const flash = (m: string) => { setNote(m); setTimeout(() => setNote(null), 2500); };
  const store = (k: string, v: string) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };

  async function testReceipt() {
    if (!app) return;
    const ok = await app.printHtml(`<!doctype html><html><head><meta charset="utf-8"><style>@page{size:${paper}mm auto;margin:0}body{width:${paper === "58" ? 50 : 72}mm;margin:0 auto;padding:4mm 0;font:12px 'Segoe UI',Arial;text-align:center}</style></head>
      <body><b>${me?.storeName ?? "HesabPro"}</b><br>Sınaq çeki<br>${new Date().toLocaleString()}<br>----------------<br>${footer || ""}</body></html>`);
    flash(ok ? "Sınaq çeki göndərildi" : "Çap alınmadı — printeri yoxlayın");
  }

  async function openLabels() {
    setLabels(await api<Product[]>("/api/products?active=true").catch(() => []));
  }

  const printerOptions = (
    <>
      <option value="">Windows-un əsas printeri</option>
      {printers.map((p) => <option key={p.name} value={p.name}>{p.name}{p.isDefault ? " (əsas)" : ""}</option>)}
    </>
  );

  return (
    <div className="mx-auto grid max-w-5xl gap-4 pb-6 lg:grid-cols-2">
      {note && <div className="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 rounded-xl bg-navy-800 px-4 py-2 text-sm text-white shadow-lg">{note}</div>}

      <Section icon="🖨" title="Çek printeri">
        <Row title="Printer" hint="Satış çeki və Z-hesabat buraya çıxır">
          <Select value={printer} onChange={(e) => { setPrinter(e.target.value); void app?.setPrinter(e.target.value || null); }} className="w-56">{printerOptions}</Select>
        </Row>
        <Row title="Çek eni">
          <Select value={paper} onChange={(e) => { const v = e.target.value as "80" | "58"; setPaper(v); saveReceiptSettings({ paper: v }); }} className="w-56">
            <option value="80">80 mm (geniş)</option>
            <option value="58">58 mm (dar)</option>
          </Select>
        </Row>
        <Row title="Çekin sonundakı yazı">
          <input value={footer} maxLength={120} onChange={(e) => { setFooter(e.target.value); saveReceiptSettings({ footer: e.target.value }); }}
            placeholder="Təşəkkür edirik!" className="w-56 rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-gold-500" />
        </Row>
        <Row title="Satışdan sonra çeki avtomatik çap et">
          <Toggle label="Avtomatik çap" on={autoPrint} onChange={(v) => { setAutoPrint(v); store(AUTO_PRINT_KEY, String(v)); }} />
        </Row>
        <Row title="Printeri yoxla"><Button variant="outline" onClick={() => void testReceipt()}>Sınaq çeki çap et</Button></Row>
      </Section>

      <Section icon="🏷" title="Etiket printeri">
        <Row title="Printer" hint="Barkod etiketləri birbaşa bu printerə gedir">
          <Select value={labelPrinter} onChange={(e) => { setLabelPrinter(e.target.value); void app?.setLabelPrinter?.(e.target.value || null); }} className="w-56">{printerOptions}</Select>
        </Row>
        <Row title="Etiket çap et" hint="Məhsulu seçin, sayını yazın"><Button variant="outline" onClick={() => void openLabels()}>Etiketlər…</Button></Row>
      </Section>

      <Section icon="💰" title="Pul qutusu və müştəri ekranı">
        <Row title="Nağd satışdan sonra pul qutusunu aç" hint="Qutu çek printerinə qoşulmalıdır">
          <Toggle label="Pul qutusu" on={drawerAuto} onChange={(v) => { setDrawerAuto(v); void app?.setDrawerAuto?.(v); }} />
        </Row>
        <Row title="Pul qutusunu indi aç">
          <Button variant="outline" onClick={async () => flash((await app?.openDrawer?.()) ? "Qutu açıldı" : "Açılmadı — printeri yoxlayın")}>Qutunu aç</Button>
        </Row>
        <Row title="Müştəri ekranı" hint="İkinci monitor varsa, orada tam ekran açılır">
          <Button variant="outline" onClick={() => void app?.openCustomerDisplay?.()}>Aç</Button>
        </Row>
      </Section>

      <Section icon="🔐" title="PIN və ekran kilidi">
        <Row title="Mənim kassa PIN-im" hint="“Kassir dəyiş” və kilidi açmaq üçün">
          <Button variant="outline" onClick={() => setPinOpen(true)}>PIN təyin et / dəyiş</Button>
        </Row>
        <Row title="Ekran kilidi" hint="Bu müddət toxunulmasa, ekran kilidlənir">
          <Select value={String(lockMin)} onChange={(e) => { const v = Number(e.target.value); setLockMin(v); setLockMinutes(v); }} className="w-40">
            <option value="0">Söndür</option>
            <option value="2">2 dəqiqə</option>
            <option value="5">5 dəqiqə</option>
            <option value="10">10 dəqiqə</option>
            <option value="30">30 dəqiqə</option>
          </Select>
        </Row>
      </Section>

      <Section icon="🖥" title="Ekran və Windows">
        <Row title="Sensor ekran rejimi" hint="Böyük düymələr və ödənişdə rəqəm klaviaturası">
          <Toggle label="Sensor rejimi" on={touch} onChange={(v) => { setTouch(v); store("hp_touch", v ? "1" : "0"); }} />
        </Row>
        {autoStart !== null && (
          <Row title="Kompüter açılanda kassa özü açılsın">
            <Toggle label="Avtomatik başlama" on={autoStart} onChange={(v) => { setAutoStart(v); void app?.setAutoStart?.(v); }} />
          </Row>
        )}
        <Row title="Proqramın versiyası" hint={update?.state === "ready" ? `Yeni versiya ${update.version ?? ""} hazırdır` : update?.state === "downloading" ? `Yeni versiya yüklənir ${update.percent}%` : "Ən son versiya"}>
          <span className="font-mono text-sm text-slate-600">v{app?.version ?? "—"}</span>
          {update?.state === "ready" && <Button variant="gold" onClick={() => void app?.installUpdate?.()}>Yenilə</Button>}
        </Row>
      </Section>

      <Section icon="📡" title="İnternetsiz satışlar">
        <Row title={pending.count > 0 ? `${pending.count} satış serverə göndərilməyib` : "Hamısı serverə göndərilib ✓"}
          hint="İnternet gələndə özü göndərilir; ehtiyat nüsxə: Sənədlər → HesabPro Kassa → ehtiyat">
          {pending.count > 0 && <Button variant="outline" onClick={async () => flash(`${(await app?.syncNow()) ?? 0} satış göndərildi`)}>İndi göndər</Button>}
        </Row>
        {pending.failed.length > 0 && (
          <div className="py-3">
            <p className="mb-1 text-sm font-medium text-rose-700">Server qəbul etmədi:</p>
            <ul className="space-y-1 text-xs text-slate-600">
              {pending.failed.map((f, i) => <li key={i}>{new Date(f.savedAt).toLocaleString()} · {f.total.toFixed(2)} ₼ — {f.error}</li>)}
            </ul>
          </div>
        )}
      </Section>

      {pinOpen && <SetPinModal onClose={() => setPinOpen(false)} />}
      {labels && <TillLabelsModal products={labels} onClose={() => setLabels(null)} />}
    </div>
  );
}
