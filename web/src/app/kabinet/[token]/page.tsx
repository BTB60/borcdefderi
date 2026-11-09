"use client";

import Image from "next/image";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { API_URL, ApiError, api } from "@/lib/api";
import { DEBT_STATUS_LABEL, METHOD_LABEL, formatDate, formatDateTime, formatMoney } from "@/lib/format";
import type { PortalView } from "@/lib/types";

const TYPE_LABEL: Record<string, string> = {
  DEBT_CREATED: "Borc yazıldı",
  DEBT_INCREASED: "Borc artırıldı",
  DEBT_ADJUSTED: "Məbləğ dəyişdirildi",
  PAYMENT_CASH: "Ödəniş",
  PAYMENT_CARD: "Ödəniş",
  PAYMENT_TRANSFER: "Ödəniş",
  PAYMENT_OTHER: "Ödəniş",
};

/** Customer portal ("Müştəri kabineti"): read-only, no login, one customer per secret link. */
export default function CustomerPortalPage() {
  const { token } = useParams<{ token: string }>();
  const [data, setData] = useState<PortalView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<PortalView>(`/api/public/portal/${encodeURIComponent(token)}`)
      .then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Məlumat yüklənmədi"));
  }, [token]);

  return (
    <main className="min-h-screen bg-canvas px-4 py-8">
      <div className="mx-auto max-w-2xl">
        <div className="mb-6 flex items-center justify-between">
          <Image src="/logo.png" alt="HesabPro" width={800} height={392} priority className="h-10 w-auto" />
          <span className="text-xs text-slate-400">Müştəri kabineti</span>
        </div>
        {error && (
          <div className="rounded-2xl bg-white p-8 text-center shadow-card">
            <p className="font-serif text-xl font-semibold text-navy-800">Link açılmadı</p>
            <p className="mt-2 text-sm text-slate-500">{error}. Mağaza ilə əlaqə saxlayıb yeni link istəyin.</p>
          </div>
        )}
        {!data && !error && <p className="text-center text-sm text-slate-500">Yüklənir…</p>}
        {data && (
          <>
            <section className="mb-5 rounded-3xl bg-gradient-to-br from-navy-800 to-violet-900 p-6 text-white shadow-xl">
              <p className="text-sm text-white/70">{data.storeName}{data.storePhone ? ` · ${data.storePhone}` : ""}</p>
              <h1 className="mt-1 font-serif text-2xl font-semibold">{data.customerName}</h1>
              <p className="text-xs text-white/60">{data.customerCode}</p>
              <div className="mt-5 grid grid-cols-3 gap-3 text-center">
                {[["Ümumi borc", data.totalDebt], ["Ödənilib", data.paid], ["Qalıq borc", data.remaining]].map(([l, v]) => (
                  <div key={l as string} className="rounded-2xl bg-white/10 px-2 py-3">
                    <p className="text-[11px] uppercase tracking-wider text-white/60">{l}</p>
                    <p className="mt-1 font-serif text-lg font-semibold tabular-nums">{formatMoney(v as number)}</p>
                  </div>
                ))}
              </div>
            </section>
            <a href={`${API_URL}/api/public/portal/${encodeURIComponent(token)}/statement`} className="mb-5 flex w-full items-center justify-center rounded-xl bg-navy-800 px-4 py-3 text-sm font-medium text-white hover:bg-navy-700">
              PDF çıxarışı yüklə
            </a>
            <section className="mb-5 rounded-2xl bg-white p-5 shadow-card">
              <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">Borclar</h2>
              {data.debts.length === 0 ? <p className="text-sm text-slate-500">Borc yoxdur.</p> : (
                <ul className="divide-y divide-slate-100">
                  {data.debts.map((d, i) => (
                    <li key={i} className="flex items-center justify-between py-2.5 text-sm">
                      <span>
                        {formatDate(d.debtDate)}
                        <span className="block text-xs text-slate-400">
                          {formatMoney(d.amount)} · ödənilib {formatMoney(d.paid)}
                          {d.overdueDays > 0 && <span className="ml-1 font-semibold text-rose-600">· {d.overdueDays} gün gecikir</span>}
                        </span>
                      </span>
                      <span className="text-right">
                        <span className="block font-semibold tabular-nums text-navy-800">{formatMoney(d.remaining)}</span>
                        <span className="text-xs text-slate-400">{DEBT_STATUS_LABEL[d.status]}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <section className="rounded-2xl bg-white p-5 shadow-card">
              <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">Tarixçə</h2>
              <ul className="divide-y divide-slate-100">
                {data.history.map((h, i) => (
                  <li key={i} className="flex items-center justify-between py-2.5 text-sm">
                    <span>
                      {TYPE_LABEL[h.type] ?? h.type}{h.paymentMethod ? ` (${METHOD_LABEL[h.paymentMethod]})` : ""}
                      <span className="block text-xs text-slate-400">{[h.date && formatDate(h.date), h.receiptNumber && `Çek: ${h.receiptNumber}`, h.note].filter(Boolean).join(" · ")}</span>
                    </span>
                    <span className="text-right">
                      <span className={`block font-semibold tabular-nums ${h.type.startsWith("PAYMENT") ? "text-emerald-700" : "text-navy-800"}`}>{h.type.startsWith("PAYMENT") ? "−" : "+"}{formatMoney(Math.abs(h.amount))}</span>
                      <span className="text-xs text-slate-400">qalıq {formatMoney(h.balanceAfter)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            </section>
            <p className="mt-6 text-center text-xs text-slate-400">
              Məlumat {formatDateTime(data.generatedAt)} vəziyyətinədir.{data.expiresAt ? ` Link ${formatDateTime(data.expiresAt).slice(0, 10)} tarixinədək etibarlıdır.` : ""}
            </p>
          </>
        )}
      </div>
    </main>
  );
}
