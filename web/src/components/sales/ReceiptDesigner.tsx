"use client";

import { useEffect, useState, type ReactNode } from "react";
import { Button, Card, Select } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { desktop, receiptHtml, receiptSettings, saveReceiptSettings, type ReceiptSettings } from "@/lib/desktop";
import { onPrintDesignSaved } from "@/lib/printDesign";
import type { Sale } from "@/lib/types";

/** A made-up sale for the preview. */
function sampleSale(): Sale {
  const items = [
    { name: "Çay Azərçay 250q", quantity: 2, unitPrice: 4.2, discount: 0, lineTotal: 8.4 },
    { name: "Şəkər 1 kq", quantity: 1, unitPrice: 1.8, discount: 0, lineTotal: 1.8 },
    { name: "Süd 1 L", quantity: 3, unitPrice: 1.6, discount: 0.3, lineTotal: 4.5 },
  ];
  return {
    id: "preview", number: "S-000123", customerId: null, customerName: "Aysel Məmmədova", customerCode: null, status: "COMPLETED",
    subtotal: 14.7, discount: 0.7, total: 14, paidCash: 20, paidCard: 0, creditAmount: 0, returnedAmount: 0, costTotal: 0,
    debtId: null, soldOn: "", note: null, createdByName: "Kassir Nigar", createdAt: new Date().toISOString(), returns: [],
    createdBy: null, priceLevel: "RETAIL",
    items: items.map((i, k) => ({ id: String(k), productId: String(k), unitCost: 0, returnedQty: 0, ...i })),
  } as Sale;
}

function Line({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="grid grid-cols-[140px_1fr] items-center gap-3 text-sm text-slate-700">
      <span>{label}</span>
      {children}
    </label>
  );
}

function Check({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="flex cursor-pointer items-center gap-2 text-sm text-slate-700">
      <input type="checkbox" className="h-4 w-4 accent-navy-800" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  );
}

/**
 * Çek dizaynı: logo, store name and extra header lines, text size and alignment, what to show (cashier, customer),
 * bold total, last lines, paper width — with a live preview and a test print.
 */
export function ReceiptDesigner() {
  const { me } = useAuth();
  const app = desktop();
  const [d, setD] = useState<ReceiptSettings>(receiptSettings);
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => { setD(receiptSettings()); }, []);
  useEffect(() => onPrintDesignSaved((ok) => setNote(ok ? "Yadda saxlanıldı" : "Serverə yazılmadı — yalnız bu kompüterdə saxlanıldı")), []);

  const set = (patch: Partial<ReceiptSettings>) => {
    setD((cur) => ({ ...cur, ...patch }));
    saveReceiptSettings(patch);
  };

  function pickLogo(file: File | undefined) {
    if (!file) return;
    if (file.size > 400_000) { setNote("Şəkil çox böyükdür (ən çox 400 KB)"); return; }
    const reader = new FileReader();
    reader.onload = () => {
      // shrink to at most 360 px wide so it fits the receipt and the storage
      const img = new Image();
      img.onload = () => {
        const scale = Math.min(1, 360 / img.width);
        const c = document.createElement("canvas");
        c.width = Math.round(img.width * scale);
        c.height = Math.round(img.height * scale);
        const g = c.getContext("2d");
        if (!g) return;
        g.fillStyle = "#fff";
        g.fillRect(0, 0, c.width, c.height);
        g.drawImage(img, 0, 0, c.width, c.height);
        set({ logo: c.toDataURL("image/png") });
      };
      img.src = String(reader.result);
    };
    reader.readAsDataURL(file);
  }

  const html = receiptHtml(sampleSale(), { name: me?.storeName ?? "Mağaza" }, { change: 6, design: d });

  async function testPrint() {
    if (!app) return;
    const ok = await app.printHtml(html);
    setNote(ok ? "Sınaq çeki printerə göndərildi" : "Çap alınmadı — printeri yoxlayın");
  }

  return (
    <Card className="lg:col-span-2">
      <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-2 font-serif text-lg font-semibold text-navy-800"><span aria-hidden="true">🧾</span>Çek dizaynı</h2>
        {note && <span className="text-sm text-emerald-700">{note}</span>}
      </div>
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="space-y-3">
          <Line label="Loqo">
            <div className="flex flex-wrap items-center gap-2">
              {/* eslint-disable-next-line @next/next/no-img-element -- a local data URL, nothing to optimise */}
              {d.logo && <img src={d.logo} alt="" className="h-10 max-w-[120px] rounded border border-slate-200 object-contain" />}
              <label className="cursor-pointer rounded-xl border border-slate-300 px-3 py-1.5 text-sm font-medium text-navy-800 hover:border-gold-500">
                {d.logo ? "Dəyiş" : "Şəkil seç"}
                <input type="file" accept="image/png,image/jpeg" className="hidden" onChange={(e) => pickLogo(e.target.files?.[0])} />
              </label>
              {d.logo && <button type="button" onClick={() => set({ logo: null })} className="text-sm text-rose-600 hover:underline">Sil</button>}
            </div>
          </Line>
          <Line label="Başlıq sətirləri">
            <textarea rows={3} maxLength={300} value={d.header} onChange={(e) => set({ header: e.target.value })}
              placeholder={"Tel: 050 123 45 67\nBakı, Nizami küç. 10\nVÖEN: 1234567891"}
              className="rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-gold-500" />
          </Line>
          <Line label="Çekin sonu">
            <textarea rows={2} maxLength={200} value={d.footer} onChange={(e) => set({ footer: e.target.value })}
              placeholder="Təşəkkür edirik! Yenə gözləyirik."
              className="rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-gold-500" />
          </Line>
          <Line label="Kağız eni">
            <Select value={d.paper} onChange={(e) => set({ paper: e.target.value as "80" | "58" })}>
              <option value="80">80 mm (geniş)</option>
              <option value="58">58 mm (dar)</option>
            </Select>
          </Line>
          <Line label="Yazı ölçüsü">
            <Select value={d.fontSize} onChange={(e) => set({ fontSize: e.target.value as "s" | "m" | "l" })}>
              <option value="s">Kiçik</option>
              <option value="m">Orta</option>
              <option value="l">Böyük</option>
            </Select>
          </Line>
          <Line label="Başlıq düzülüşü">
            <Select value={d.align} onChange={(e) => set({ align: e.target.value as "center" | "left" })}>
              <option value="center">Ortada</option>
              <option value="left">Solda</option>
            </Select>
          </Line>
          <div className="grid gap-2 pt-1 sm:grid-cols-2">
            <Check label="Mağaza adı" checked={d.showStoreName} onChange={(v) => set({ showStoreName: v })} />
            <Check label="Kassirin adı" checked={d.showCashier} onChange={(v) => set({ showCashier: v })} />
            <Check label="Müştərinin adı" checked={d.showCustomer} onChange={(v) => set({ showCustomer: v })} />
            <Check label="Yekun qalın" checked={d.boldTotal} onChange={(v) => set({ boldTotal: v })} />
          </div>
          <div className="flex flex-wrap gap-2 pt-2">
            {app && <Button variant="outline" onClick={() => void testPrint()}>Sınaq çeki çap et</Button>}
            <Button variant="ghost" onClick={() => { const def = { paper: "80" as const, footer: "Təşəkkür edirik!", header: "", logo: null, fontSize: "m" as const, align: "center" as const, showCashier: true, showCustomer: true, showStoreName: true, boldTotal: true }; set(def); }}>Standarta qaytar</Button>
          </div>
        </div>
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Önizləmə</p>
          <div className="flex justify-center rounded-2xl bg-slate-100 p-4">
            <iframe title="Çek önizləməsi" srcDoc={html} className="h-[520px] rounded-sm bg-white shadow-lg"
              style={{ width: d.paper === "58" ? 230 : 310 }} />
          </div>
        </div>
      </div>
    </Card>
  );
}
