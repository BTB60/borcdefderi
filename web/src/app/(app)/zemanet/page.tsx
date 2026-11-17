"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, PageHeader } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatMoney } from "@/lib/format";

type Hit = {
  serial: string; productName: string; saleId: string; saleNumber: string; soldOn: string; customerName: string | null;
  customerPhone: string | null; price: number; warrantyMonths: number | null; warrantyUntil: string | null; inWarranty: boolean;
  returned: boolean; cancelled: boolean; soldBy: string;
};

/** Zəmanət yoxla: find a sold phone / device by its IMEI or serial — when, to whom, for how much, warranty left. */
export default function WarrantyPage() {
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<Hit[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function search(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setHits(await api<Hit[]>(`/api/warranty?q=${encodeURIComponent(q.trim())}`));
    } catch (err) {
      setHits(null);
      setError(err instanceof ApiError ? err.message : "Axtarış alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Zəmanət (IMEI)" subtitle="IMEI və ya seriya nömrəsini oxudun: nə vaxt, kimə, neçəyə satılıb, zəmanət qalıb ya yox" />
      <Card>
        <form onSubmit={search} className="flex flex-wrap gap-3">
          <Input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="IMEI / seriya nömrəsi və ya son 4+ rəqəmi"
            aria-label="IMEI / seriya" className="min-w-0 flex-1 font-mono" />
          <Button type="submit" variant="gold" disabled={busy || q.trim().length < 4}>Axtar</Button>
        </form>
      </Card>
      <div className="mt-6 space-y-4">
        {error && <Alert>{error}</Alert>}
        {hits && hits.length === 0 && <Card><EmptyState title="Tapılmadı" text="Bu nömrə ilə satış yoxdur." /></Card>}
        {hits?.map((h) => (
          <Card key={h.serial + h.saleId}>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="font-mono text-sm text-slate-500">{h.serial}</p>
                <p className="mt-0.5 text-lg font-semibold text-navy-800">{h.productName}</p>
              </div>
              {h.cancelled ? <Badge tone="slate">Satış ləğv olunub</Badge>
                : h.returned ? <Badge tone="amber">Qaytarılıb</Badge>
                : h.warrantyMonths == null ? <Badge tone="slate">Zəmanət yoxdur</Badge>
                : h.inWarranty ? <Badge tone="emerald">Zəmanətdədir · {formatDate(h.warrantyUntil)}-dək</Badge>
                : <Badge tone="rose">Zəmanət bitib · {formatDate(h.warrantyUntil)}</Badge>}
            </div>
            <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-4">
              <div><dt className="text-slate-500">Satış</dt><dd className="font-medium text-navy-800"><Link href="/satis" className="hover:underline">{h.saleNumber}</Link> · {formatDate(h.soldOn)}</dd></div>
              <div><dt className="text-slate-500">Müştəri</dt><dd className="font-medium text-navy-800">{h.customerName ?? "—"}{h.customerPhone ? ` · ${h.customerPhone}` : ""}</dd></div>
              <div><dt className="text-slate-500">Qiymət</dt><dd className="font-medium text-navy-800">{formatMoney(h.price)}</dd></div>
              <div><dt className="text-slate-500">Satan</dt><dd className="font-medium text-navy-800">{h.soldBy}{h.warrantyMonths ? ` · zəmanət ${h.warrantyMonths} ay` : ""}</dd></div>
            </dl>
          </Card>
        ))}
      </div>
    </>
  );
}
