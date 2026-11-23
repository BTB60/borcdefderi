"use client";

import Image from "next/image";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, formatMoney } from "@/lib/format";

type Tracking = {
  number: string; status: "NEW" | "ASSIGNED" | "ON_THE_WAY" | "DELIVERED" | "CANCELLED"; storeName: string; storePhone: string | null;
  courierName: string | null; courierPhone: string | null; address: string; createdAt: string; assignedAt: string | null;
  pickedUpAt: string | null; deliveredAt: string | null; cancelledAt: string | null; collectAmount: number;
};

/** Çatdırılmanı izlə: the customer's page, no sign-in, opened from the link the store sent. */
export default function TrackDeliveryPage() {
  const { token } = useParams<{ token: string }>();
  const [data, setData] = useState<Tracking | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = () => api<Tracking>(`/api/public/deliveries/${encodeURIComponent(token)}`).then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Məlumat yüklənmədi"));
    load();
    const t = setInterval(load, 60_000);
    return () => clearInterval(t);
  }, [token]);

  const steps = data ? [
    { label: "Sifariş qəbul edildi", at: data.createdAt },
    { label: "Kuryerə verildi", at: data.assignedAt },
    { label: "Yoldadır", at: data.pickedUpAt },
    { label: "Çatdırıldı", at: data.deliveredAt },
  ] : [];

  return (
    <main className="min-h-screen bg-canvas px-4 py-8">
      <div className="mx-auto max-w-md">
        <div className="mb-6 flex items-center justify-between">
          <Image src="/logo.png" alt="HesabPro" width={800} height={392} priority className="h-10 w-auto" />
          <span className="text-xs text-slate-400">Çatdırılmanı izlə</span>
        </div>
        {error && (
          <div className="rounded-2xl bg-white p-8 text-center shadow-card">
            <p className="font-serif text-xl font-semibold text-navy-800">Link açılmadı</p>
            <p className="mt-2 text-sm text-slate-500">{error}. Mağaza ilə əlaqə saxlayın.</p>
          </div>
        )}
        {!data && !error && <p className="text-center text-sm text-slate-500">Yüklənir…</p>}
        {data && (
          <div className="rounded-3xl bg-white p-6 shadow-card">
            <p className="text-sm text-slate-500">{data.storeName}{data.storePhone ? ` · ${data.storePhone}` : ""}</p>
            <h1 className="mt-1 font-serif text-2xl font-semibold text-navy-800">Sifariş {data.number}</h1>
            <p className="mt-1 text-sm text-slate-600">📍 {data.address}</p>
            {data.status === "CANCELLED" ? (
              <p className="mt-6 rounded-2xl bg-rose-50 p-4 text-sm font-medium text-rose-700">Çatdırılma ləğv edilib ({formatDateTime(data.cancelledAt)}).</p>
            ) : (
              <ol className="mt-6 space-y-4">
                {steps.map((s, i) => {
                  const done = !!s.at;
                  return (
                    <li key={s.label} className="flex items-start gap-3">
                      <span className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold ${done ? "bg-emerald-500 text-white" : "bg-slate-200 text-slate-500"}`}>
                        {done ? "✓" : i + 1}
                      </span>
                      <div>
                        <p className={`text-sm font-medium ${done ? "text-navy-800" : "text-slate-400"}`}>{s.label}</p>
                        {s.at && <p className="text-xs text-slate-500">{formatDateTime(s.at)}</p>}
                      </div>
                    </li>
                  );
                })}
              </ol>
            )}
            {data.status === "ON_THE_WAY" && data.courierName && (
              <div className="mt-6 rounded-2xl bg-amber-50 p-4 text-sm text-amber-900">
                Kuryer: <b>{data.courierName}</b>
                {data.courierPhone && <> · <a href={`tel:${data.courierPhone}`} className="font-semibold underline">{data.courierPhone}</a></>}
              </div>
            )}
            {data.collectAmount > 0 && data.status !== "DELIVERED" && data.status !== "CANCELLED" && (
              <p className="mt-4 text-sm text-slate-600">Çatdırılanda ödəniləcək: <b>{formatMoney(data.collectAmount)}</b></p>
            )}
          </div>
        )}
      </div>
    </main>
  );
}
