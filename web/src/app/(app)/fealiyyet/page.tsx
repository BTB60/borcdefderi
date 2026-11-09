"use client";

import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Card, EmptyState, Input, PageHeader, Select, Spinner, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney, todayBaku } from "@/lib/format";
import type { Activity } from "@/lib/types";

const ACTION_LABEL: Record<string, string> = {
  DEBT_CREATED: "Borc yazdı",
  DEBT_INCREASED: "Borc artırdı",
  DEBT_UPDATED: "Borcu dəyişdi",
  DEBT_DUE_DATE_SET: "Son ödəmə tarixi qoydu",
  PAYMENT_RECORDED: "Ödəniş qəbul etdi",
  CUSTOMER_CREATED: "Müştəri əlavə etdi",
  CUSTOMER_UPDATED: "Müştəri məlumatını dəyişdi",
  COMPANY_CREATED: "Şirkət əlavə etdi",
  COMPANY_UPDATED: "Şirkət məlumatını dəyişdi",
  CUSTOMER_NOTE_ADDED: "Müştəriyə qeyd yazdı",
  CUSTOMER_CONTACT_WHATSAPP: "WhatsApp açdı",
  CUSTOMER_CONTACT_CALL: "Zəng etdi",
  PORTAL_LINK_CREATED: "Kabinet linki yaratdı",
  PORTAL_LINK_REVOKED: "Kabinet linkini ləğv etdi",
};

/** Owner only: what each employee did and how much they wrote / received. */
export default function ActivityPage() {
  const { isOwner } = useAuth();
  const today = todayBaku();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [actor, setActor] = useState("");
  const [data, setData] = useState<Activity | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    const qs = new URLSearchParams({ from, to });
    if (actor) qs.set("actorId", actor);
    api<Activity>(`/api/activity?${qs.toString()}`).then(setData).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [from, to, actor]);

  useEffect(() => {
    if (isOwner) load();
  }, [isOwner, load]);

  if (!isOwner) return <Card><EmptyState title="Bu səhifə yalnız mağaza sahibi üçündür" /></Card>;

  return (
    <>
      <PageHeader title="İşçi fəaliyyəti" subtitle="Kim nə vaxt hansı əməliyyatı edib" />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <Card className="mb-5">
        <div className="grid gap-3 sm:grid-cols-4">
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="Başlanğıc" />
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="Son" />
          <Select value={actor} onChange={(e) => setActor(e.target.value)} aria-label="İşçi">
            <option value="">Bütün işçilər</option>
            {data?.employees.map((e) => <option key={e.userId} value={e.userId}>{e.fullName}</option>)}
          </Select>
          <Button variant="primary" onClick={load}>Göstər</Button>
        </div>
      </Card>
      {!data ? <Spinner /> : (
        <>
          <Card className="mb-5">
            <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">İşçilər üzrə cəmi</h2>
            <Table>
              <thead className="bg-slate-50/70"><tr><Th>İşçi</Th><Th className="text-right">Yazılan borc</Th><Th className="text-right">Qəbul edilən ödəniş</Th><Th className="text-right">Əməliyyat sayı</Th></tr></thead>
              <tbody className="divide-y divide-slate-100">
                {data.employees.map((e) => (
                  <tr key={e.userId}>
                    <Td><span className="font-medium text-navy-800">{e.fullName}</span><div className="text-xs text-slate-400">{e.role === "STORE_OWNER" ? "Sahib" : "İşçi"}</div></Td>
                    <Td className="text-right tabular-nums">{formatMoney(e.debtAmount)}<div className="text-xs text-slate-400">{e.debtEntries} dəfə</div></Td>
                    <Td className="text-right tabular-nums text-emerald-700">{formatMoney(e.paymentsAmount)}<div className="text-xs text-slate-400">{e.paymentsReceived} ödəniş</div></Td>
                    <Td className="text-right tabular-nums">{e.actions}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </Card>
          <Card>
            <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">Əməliyyat jurnalı</h2>
            {data.entries.length === 0 ? <EmptyState title="Bu dövrdə əməliyyat yoxdur" /> : (
              <Table>
                <thead className="bg-slate-50/70"><tr><Th>Vaxt</Th><Th>İşçi</Th><Th>Əməliyyat</Th><Th>Təfərrüat</Th></tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {data.entries.map((e) => (
                    <tr key={e.id}>
                      <Td className="whitespace-nowrap text-xs">{formatDateTime(e.at)}</Td>
                      <Td>{e.actorName ?? "—"}</Td>
                      <Td>{ACTION_LABEL[e.action] ?? e.action}</Td>
                      <Td className="max-w-[24rem] truncate text-xs text-slate-500">{e.details ?? "—"}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}
            <p className="mt-3 text-xs text-slate-400">Jurnal dəyişdirilə və silinə bilməz.</p>
          </Card>
        </>
      )}
    </>
  );
}
