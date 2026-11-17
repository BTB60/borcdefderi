"use client";

import { useState } from "react";
import { Alert, Button, Input, Modal, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatMoney, todayBaku } from "@/lib/format";
import { waLink } from "@/lib/whatsapp";

type Summary = {
  date: string; storeName: string; salesCount: number; salesTotal: number; paidCash: number; paidCard: number; credit: number;
  grossProfit: number; returnsCount: number; returnsTotal: number; expensesTotal: number; debtsCollected: number; supplierPaid: number;
  accounts: { name: string; kind: string; balance: number }[]; topProducts: { name: string; quantity: number; total: number }[];
};

/** The day in a message the owner can send to themselves (or a partner) on WhatsApp. */
export function daySummaryText(s: Summary): string {
  const l = [
    `📊 ${s.storeName} — gün sonu (${formatDate(s.date)})`,
    "",
    `🛒 Satış: ${formatMoney(s.salesTotal)} (${s.salesCount} çek)`,
    `   Nağd ${formatMoney(s.paidCash)} · Kart ${formatMoney(s.paidCard)} · Nisyə ${formatMoney(s.credit)}`,
    `💰 Mənfəət (təxmini): ${formatMoney(s.grossProfit)}`,
  ];
  if (s.returnsCount > 0) l.push(`↩️ Qaytarma: ${formatMoney(s.returnsTotal)} (${s.returnsCount})`);
  if (s.expensesTotal > 0) l.push(`🧾 Xərc: ${formatMoney(s.expensesTotal)}`);
  if (s.debtsCollected > 0) l.push(`✅ Yığılan borc: ${formatMoney(s.debtsCollected)}`);
  if (s.supplierPaid > 0) l.push(`🚚 Tədarükçüyə ödənilib: ${formatMoney(s.supplierPaid)}`);
  if (s.topProducts.length > 0) {
    l.push("", "⭐ Ən çox satılan:");
    s.topProducts.forEach((p, i) => l.push(`   ${i + 1}. ${p.name} — ${Number(p.quantity)} əd. · ${formatMoney(p.total)}`));
  }
  if (s.accounts.length > 0) {
    l.push("", "🏦 Hazırda:");
    s.accounts.forEach((a) => l.push(`   ${a.name}: ${formatMoney(a.balance)}`));
  }
  l.push("", "HesabPro");
  return l.join("\n");
}

/** "Gün sonu hesabatı" button + window with the day's numbers and a WhatsApp / copy button. */
export function DaySummaryButton() {
  const { me } = useAuth();
  const [open, setOpen] = useState(false);
  const [date, setDate] = useState(todayBaku());
  const [data, setData] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  function load(d: string) {
    setData(null);
    setError(null);
    api<Summary>(`/api/reports/day-summary?date=${d}`).then(setData).catch((e) => setError(e instanceof ApiError ? e.message : "Hesabat yüklənmədi"));
  }

  const text = data ? daySummaryText(data) : "";
  const wa = data ? (waLink(me?.phone, text) ?? `https://wa.me/?text=${encodeURIComponent(text)}`) : null;

  return (
    <>
      <Button variant="outline" onClick={() => { setOpen(true); load(date); }}>Gün sonu hesabatı</Button>
      {open && (
        <Modal open title="Gün sonu hesabatı" onClose={() => setOpen(false)}
          footer={<>
            <Button variant="ghost" onClick={() => setOpen(false)}>Bağla</Button>
            <Button variant="outline" disabled={!data} onClick={async () => { try { await navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 2000); } catch { /* ignore */ } }}>
              {copied ? "Kopyalandı ✓" : "Kopyala"}
            </Button>
            {wa && <a href={wa} target="_blank" rel="noreferrer" className="inline-flex items-center rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700">WhatsApp-a göndər</a>}
          </>}>
          <div className="mb-4 flex items-center gap-2">
            <Input type="date" value={date} max={todayBaku()} onChange={(e) => { setDate(e.target.value); load(e.target.value); }} className="w-auto" aria-label="Tarix" />
            <span className="text-xs text-slate-500">{me?.phone ? "Mesaj öz nömrənizə göndəriləcək" : "Profildə telefon yazsanız, mesaj birbaşa sizə gedəcək"}</span>
          </div>
          {error && <Alert>{error}</Alert>}
          {!data && !error ? <Spinner /> : data && (
            <pre className="whitespace-pre-wrap rounded-xl bg-slate-50 p-4 font-sans text-sm leading-relaxed text-navy-800 ring-1 ring-slate-200">{text}</pre>
          )}
        </Modal>
      )}
    </>
  );
}
