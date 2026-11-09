"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, PageHeader, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { AccountModal, DIRECTION_LABEL } from "@/components/debts/AccountModals";
import { HistoryModal, WhatsAppIcon } from "@/components/debts/DebtModals";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { DEBT_STATUS_LABEL, METHOD_LABEL, formatDate, formatDateTime, formatMoney } from "@/lib/format";
import type { Debt, DebtStatus, DebtType, PartyProfile } from "@/lib/types";
import { debtReminderText, waLink } from "@/lib/whatsapp";

const STATUS_TONE: Record<DebtStatus, "amber" | "emerald" | "slate"> = { OPEN: "slate", PARTIAL: "amber", PAID: "emerald" };
const PERM: Record<DebtType, "CUSTOMER_DEBTS" | "COMPANY_RECEIVABLES" | "COMPANY_PAYABLES"> = {
  CUSTOMER_DEBT: "CUSTOMER_DEBTS",
  COMPANY_RECEIVABLE: "COMPANY_RECEIVABLES",
  COMPANY_PAYABLE: "COMPANY_PAYABLES",
};

/** Customer / company profile: totals, all debts, all payments, and the actions on them. */
export default function PartyProfilePage() {
  const params = useParams<{ kind: string; id: string }>();
  const isCustomer = params.kind === "musteri";
  const { can, me } = useAuth();
  const [data, setData] = useState<PartyProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [account, setAccount] = useState<"charge" | "pay" | null>(null);
  const [historyOf, setHistoryOf] = useState<Debt | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setData(await api<PartyProfile>(`/api/${isCustomer ? "customers" : "companies"}/${params.id}/profile`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Məlumat yüklənə bilmədi");
    }
  }, [isCustomer, params.id]);

  useEffect(() => {
    load();
  }, [load]);

  const writable: DebtType[] = isCustomer
    ? (can("CUSTOMER_DEBTS") ? ["CUSTOMER_DEBT"] : [])
    : (["COMPANY_RECEIVABLE", "COMPANY_PAYABLE"] as DebtType[]).filter((t) => can(PERM[t]));

  if (error && !data) {
    return (
      <>
        <PageHeader title={isCustomer ? "Müştəri" : "Şirkət"} actions={<Link href="/kontragentlar"><Button variant="outline">← Siyahı</Button></Link>} />
        <Alert>{error}</Alert>
      </>
    );
  }
  if (!data) return <Spinner />;

  const { party, totals, debts, payments } = data;
  const byType = (t: DebtType) => Math.round(debts.filter((d) => d.debtType === t).reduce((s, d) => s + d.remainingAmount, 0) * 100) / 100;
  const reminder = isCustomer && totals.remaining > 0
    ? waLink(party.phone, debtReminderText(me?.storeName ?? "", party.name, totals.remaining, null))
    : null;

  return (
    <>
      <PageHeader
        title={party.name}
        subtitle={[isCustomer ? "Müştəri" : "Şirkət", party.phone, party.note].filter(Boolean).join(" · ")}
        actions={
          <>
            <Link href="/kontragentlar"><Button variant="outline">← Siyahı</Button></Link>
            {reminder && (
              <a href={reminder} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-xl border border-emerald-600/30 px-4 py-2.5 text-sm font-medium text-emerald-700 transition hover:bg-emerald-50">
                <WhatsAppIcon /> WhatsApp-da xatırlatma aç
              </a>
            )}
            {writable.length > 0 && (
              <>
                <Button variant="gold" onClick={() => setAccount("charge")}>+ Borc əlavə et</Button>
                <Button variant="primary" onClick={() => setAccount("pay")}>Borc ödə</Button>
              </>
            )}
          </>
        }
      />
      {reminder && <p className="-mt-3 mb-4 text-xs text-slate-400">WhatsApp yalnız hazır mətnlə açılır — mesajı WhatsApp-da özünüz göndərirsiniz.</p>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <section className="mb-6 grid gap-4 sm:grid-cols-3" aria-label="Cəmi">
        <StatCard label="Ümumi borc" value={formatMoney(totals.totalDebt)} tone="navy" />
        <StatCard label="Ödənilib" value={formatMoney(totals.paid)} tone="emerald" />
        <StatCard label="Qalıq borc" value={formatMoney(totals.remaining)} tone="gold" />
      </section>
      {!isCustomer && (
        <section className="mb-6 grid gap-4 sm:grid-cols-2" aria-label="İstiqamətlər">
          <StatCard label={DIRECTION_LABEL.COMPANY_RECEIVABLE} value={formatMoney(byType("COMPANY_RECEIVABLE"))} tone="emerald" />
          <StatCard label={DIRECTION_LABEL.COMPANY_PAYABLE} value={formatMoney(byType("COMPANY_PAYABLE"))} tone="rose" />
        </section>
      )}

      <Card className="mb-6">
        <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Borc hesabı</h2>
        {debts.length === 0 ? (
          <EmptyState title="Borc yoxdur" text="“+ Borc əlavə et” düyməsi ilə əlavə edin." />
        ) : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr>
                <Th>Tarix</Th>{!isCustomer && <Th>İstiqamət</Th>}<Th>Təsvir</Th><Th className="text-right">Məbləğ</Th>
                <Th className="text-right">Ödənilib</Th><Th className="text-right">Qalıq</Th><Th>Status</Th><Th className="text-right">Tarixçə</Th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {debts.map((d) => {
                return (
                  <tr key={d.id} className="transition-colors hover:bg-gold-500/5">
                    <Td>{formatDate(d.debtDate)}{d.dueDate && <div className="text-xs text-slate-400">son: {formatDate(d.dueDate)}</div>}</Td>
                    {!isCustomer && <Td className="text-xs">{DIRECTION_LABEL[d.debtType]}</Td>}
                    <Td className="max-w-[14rem] truncate text-slate-500">{d.description ?? "—"}</Td>
                    <Td className="text-right tabular-nums">{formatMoney(d.amount)}</Td>
                    <Td className="text-right tabular-nums text-emerald-700">{formatMoney(d.paidAmount)}</Td>
                    <Td className="text-right font-semibold tabular-nums text-navy-800">{formatMoney(d.remainingAmount)}</Td>
                    <Td><Badge tone={STATUS_TONE[d.status]}>{DEBT_STATUS_LABEL[d.status]}</Badge></Td>
                    <Td className="text-right">
                      <Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setHistoryOf(d)}>Tarixçə</Button>
                    </Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        )}
        <p className="mt-3 text-xs text-slate-400">“Borc ödə” ilə edilən ödəniş ümumi qalıqdan çıxılır (köhnə borclar varsa, əvvəl onlar bağlanır).</p>
      </Card>

      <Card>
        <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Ödəniş tarixçəsi</h2>
        {payments.length === 0 ? (
          <EmptyState title="Ödəniş yoxdur" />
        ) : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Tarix</Th><Th>Üsul</Th><Th>Təsvir</Th><Th>Qəbul edən</Th><Th className="text-right">Məbləğ</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {payments.map((p) => (
                <tr key={p.id}>
                  <Td>{formatDateTime(p.paidAt)}</Td>
                  <Td>{METHOD_LABEL[p.paymentMethod]}</Td>
                  <Td className="max-w-[16rem] truncate text-slate-500">{p.description ?? "—"}</Td>
                  <Td className="text-slate-500">{p.recordedByName ?? "—"}</Td>
                  <Td className="text-right font-semibold tabular-nums text-emerald-700">{formatMoney(p.amount)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      {account && (
        <AccountModal
          mode={account}
          isCustomer={isCustomer}
          partyId={party.id}
          onClose={() => setAccount(null)}
          onSaved={(msg) => {
            setAccount(null);
            setNotice(msg);
            load();
          }}
        />
      )}
      {historyOf && <HistoryModal debt={historyOf} onClose={() => setHistoryOf(null)} />}
    </>
  );
}
