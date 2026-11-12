"use client";

import { useCallback, useEffect, useState } from "react";
import { PocketEntryModal, type PocketDay, type PocketDaySummary } from "@/components/pocket/Pocket";
import { Alert, Button, Card, EmptyState, Input, PageHeader, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatDateTime, formatMoney, todayBaku } from "@/lib/format";

function shift(iso: string, days: number) {
  const d = new Date(iso + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** "Cib pulu": money put into and spent from my own pocket, day by day; what is left carries over. */
export default function PocketPage() {
  const today = todayBaku();
  const [date, setDate] = useState(today);
  const [day, setDay] = useState<PocketDay | null>(null);
  const [history, setHistory] = useState<PocketDaySummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [kind, setKind] = useState<"IN" | "OUT" | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  const load = useCallback(() => {
    setError(null);
    api<PocketDay>(`/api/pocket?date=${date}`).then(setDay).catch((e: unknown) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
    api<PocketDaySummary[]>("/api/pocket/history?days=30").then(setHistory).catch(() => setHistory([]));
  }, [date]);
  useEffect(load, [load]);

  const remove = async (id: string) => {
    if (!(await confirm("Bu qeyd silinsin?", { confirmLabel: "Sil", danger: true }))) return;
    try {
      await api(`/api/pocket/${id}`, { method: "DELETE" });
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Silinmədi");
    }
  };

  const isToday = date === today;

  return (
    <>
      <PageHeader
        title="Cib pulu"
        subtitle="Cibinizə qoyduğunuz və xərclədiyiniz pul. Kassaya və xərclərə təsir etmir; dünəndən qalan pul avtomatik bu günə keçir."
        actions={
          <>
            <Button className="bg-emerald-600 hover:bg-emerald-700" onClick={() => setKind("IN")}>+ Pul qoy</Button>
            <Button variant="danger" onClick={() => setKind("OUT")}>− Xərclə</Button>
          </>
        }
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <div className="mb-5 flex flex-wrap items-center gap-2">
        <Button variant="outline" onClick={() => setDate(shift(date, -1))} aria-label="Əvvəlki gün">←</Button>
        <Input type="date" value={date} max={today} onChange={(e) => e.target.value && setDate(e.target.value)} className="w-44" aria-label="Tarix" />
        <Button variant="outline" disabled={isToday} onClick={() => setDate(shift(date, 1))} aria-label="Növbəti gün">→</Button>
        {!isToday && <Button variant="ghost" onClick={() => setDate(today)}>Bu gün</Button>}
      </div>

      {!day ? (
        !error && <Spinner />
      ) : (
        <>
          <section className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="Gün başı (dünəndən qalan)" value={formatMoney(day.openingBalance)} tone="navy" />
            <StatCard label="Qoyulan" value={`+${formatMoney(day.added)}`} tone="emerald" />
            <StatCard label="Xərclənən" value={`−${formatMoney(day.spent)}`} tone="rose" />
            <StatCard label={isToday ? "Cibimdə qalan" : "Gün sonu qalan"} value={formatMoney(day.closingBalance)} tone="gold" />
          </section>

          <section className="grid gap-6 xl:grid-cols-3">
            <Card className="xl:col-span-2">
              <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">{isToday ? "Bu günün qeydləri" : `${formatDate(date)} qeydləri`}</h2>
              {day.entries.length === 0 ? (
                <EmptyState title="Qeyd yoxdur" text={'"Pul qoy" və ya "Xərclə" düyməsi ilə yazın.'} />
              ) : (
                <Table>
                  <thead className="bg-slate-50/70">
                    <tr><Th>Vaxt</Th><Th>Qeyd</Th><Th className="text-right">Məbləğ</Th><Th /></tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {day.entries.map((e) => (
                      <tr key={e.id}>
                        <Td className="text-slate-500">{formatDateTime(e.createdAt).slice(-5)}</Td>
                        <Td>{e.note ?? (e.kind === "IN" ? "Cibə qoyuldu" : "Xərc")}</Td>
                        <Td className={`text-right font-semibold tabular-nums ${e.kind === "IN" ? "text-emerald-700" : "text-rose-700"}`}>
                          {e.kind === "IN" ? "+" : "−"}{formatMoney(e.amount)}
                        </Td>
                        <Td className="text-right"><Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => remove(e.id)}>Sil</Button></Td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              )}
            </Card>

            <Card>
              <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Son günlər</h2>
              {history.length === 0 ? (
                <EmptyState title="Hələ qeyd yoxdur" />
              ) : (
                <ul className="divide-y divide-slate-100">
                  {history.map((h) => (
                    <li key={h.date}>
                      <button type="button" onClick={() => setDate(h.date)} className="flex w-full items-center justify-between py-2.5 text-left hover:bg-slate-50/60">
                        <span>
                          <span className="block text-sm font-medium text-navy-800">{formatDate(h.date)}</span>
                          <span className="text-xs"><span className="text-emerald-700">+{formatMoney(h.added)}</span> <span className="text-rose-700">−{formatMoney(h.spent)}</span></span>
                        </span>
                        <span className="font-semibold tabular-nums text-navy-800">{formatMoney(h.closingBalance)}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </section>
        </>
      )}

      {kind && (
        <PocketEntryModal
          kind={kind}
          date={isToday ? undefined : date}
          onClose={() => setKind(null)}
          onSaved={() => {
            setKind(null);
            load();
          }}
        />
      )}
      {confirmDialog}
    </>
  );
}
