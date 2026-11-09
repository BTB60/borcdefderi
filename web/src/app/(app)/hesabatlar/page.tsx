"use client";

import { useState } from "react";
import { Alert, Button, Card, Field, Input, PageHeader } from "@/components/ui";
import { ApiError, download } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { todayBaku } from "@/lib/format";

type Report = { title: string; text: string; path: string; file: string; formats?: ("xlsx" | "pdf")[]; dated?: boolean; show: boolean };

/** All PDF / Excel reports in one place. Every file is generated on the server from the live database. */
export default function ReportsPage() {
  const { can, isOwner } = useAuth();
  const today = todayBaku();
  const [from, setFrom] = useState(today.slice(0, 8) + "01");
  const [to, setTo] = useState(today);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reports = isOwner || can("REPORTS");
  const cust = isOwner || can("CUSTOMER_DEBTS");
  const rec = isOwner || can("COMPANY_RECEIVABLES");
  const pay = isOwner || can("COMPANY_PAYABLES");

  const list: Report[] = [
    { title: "Müştərilər", text: "Kod, telefon, ümumi borc, ödənilən, qalıq, status", path: "/api/reports/customers/export", file: "musteriler", formats: ["xlsx"], show: reports && cust },
    { title: "Şirkətlər", text: "Bizə borc və bizim borc ayrıca", path: "/api/reports/companies/export", file: "sirketler", formats: ["xlsx"], show: reports && (rec || pay) },
    { title: "Aktiv müştəri borcları", text: "Qalığı olan borclar", path: "/api/reports/debts/export?type=CUSTOMER_DEBT&status=UNPAID", file: "aktiv-borclar", show: cust },
    { title: "Bağlanmış müştəri borcları", text: "Tam ödənilmiş borclar", path: "/api/reports/debts/export?type=CUSTOMER_DEBT&status=PAID", file: "baglanmis-borclar", show: cust },
    { title: "Bütün müştəri borcları", text: "Seçilmiş dövr üzrə", path: "/api/reports/debts/export?type=CUSTOMER_DEBT", file: "borclar", dated: true, show: cust },
    { title: "Şirkətlər bizə borcludur", text: "Seçilmiş dövr üzrə", path: "/api/reports/debts/export?type=COMPANY_RECEIVABLE", file: "alacaqlar", dated: true, show: rec },
    { title: "Bizim şirkətlərə borcumuz", text: "Seçilmiş dövr üzrə", path: "/api/reports/debts/export?type=COMPANY_PAYABLE", file: "borcumuz", dated: true, show: pay },
    { title: "Ödənişlər — gündəlik/aylıq", text: "Seçilmiş dövr üzrə, ödəniş üsulları ilə", path: "/api/reports/payments/export?", file: "odenisler", dated: true, show: reports },
  ];

  async function get(r: Report, format: "xlsx" | "pdf") {
    setError(null);
    setBusy(r.title + format);
    const sep = r.path.includes("?") ? (r.path.endsWith("?") ? "" : "&") : "?";
    const dates = r.dated ? `&from=${from}&to=${to}` : "";
    try {
      await download(`${r.path}${sep}format=${format}${dates}`, `${r.file}.${format}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Hesabat hazırlanmadı");
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <PageHeader title="Hesabatlar" subtitle="PDF və Excel faylları canlı məlumatdan hazırlanır (Azərbaycan hərfləri ilə)" />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <Card className="mb-5">
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Başlanğıc tarix"><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="Son tarix"><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <p className="self-end pb-2 text-xs text-slate-500">Tarix yalnız “seçilmiş dövr üzrə” hesabatlara tətbiq olunur. Gündəlik hesabat üçün hər iki tarixi eyni günə qoyun.</p>
        </div>
      </Card>
      <div className="grid gap-4 md:grid-cols-2">
        {list.filter((r) => r.show).map((r) => (
          <Card key={r.title}>
            <h2 className="font-serif text-lg font-semibold text-navy-800">{r.title}</h2>
            <p className="mb-4 text-sm text-slate-500">{r.text}</p>
            <div className="flex gap-2">
              {(r.formats ?? ["xlsx", "pdf"]).map((f) => (
                <Button key={f} variant={f === "xlsx" ? "outline" : "primary"} disabled={busy !== null} onClick={() => get(r, f)}>
                  {busy === r.title + f ? "Hazırlanır…" : f === "xlsx" ? "Excel" : "PDF"}
                </Button>
              ))}
            </div>
          </Card>
        ))}
      </div>
    </>
  );
}
