"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Select, Spinner, StatCard, Table, Td, Textarea, Th } from "@/components/ui";
import { AccountModal, DIRECTION_LABEL } from "@/components/debts/AccountModals";
import { HistoryModal, WhatsAppIcon } from "@/components/debts/DebtModals";
import { ApiError, api, download } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { DEBT_STATUS_LABEL, METHOD_LABEL, formatDate, formatDateTime, formatMoney } from "@/lib/format";
import type { CustomerNote, Debt, DebtStatus, DebtType, HistoryEntry, Party, PartyProfile, PortalLink } from "@/lib/types";
import { TEMPLATE_LABEL, templateText, waLink, waNumber, waWebLink, type TemplateKey } from "@/lib/whatsapp";

const STATUS_TONE: Record<DebtStatus, "amber" | "emerald" | "slate"> = { OPEN: "slate", PARTIAL: "amber", PAID: "emerald" };
const PERM: Record<DebtType, "CUSTOMER_DEBTS" | "COMPANY_RECEIVABLES" | "COMPANY_PAYABLES"> = {
  CUSTOMER_DEBT: "CUSTOMER_DEBTS",
  COMPANY_RECEIVABLE: "COMPANY_RECEIVABLES",
  COMPANY_PAYABLE: "COMPANY_PAYABLES",
};
const ENTRY_LABEL: Record<HistoryEntry["type"], string> = {
  DEBT_CREATED: "Borc yazıldı",
  DEBT_INCREASED: "Borc artırıldı",
  DEBT_ADJUSTED: "Məbləğ dəyişdirildi",
  PAYMENT_CASH: "Ödəniş — nağd",
  PAYMENT_CARD: "Ödəniş — kart",
  PAYMENT_TRANSFER: "Ödəniş — köçürmə",
  PAYMENT_OTHER: "Ödəniş — digər",
};

function QuickButton({ onClick, href, icon, label, tone = "bg-white text-navy-800" }: { onClick?: () => void; href?: string; icon: ReactNode; label: string; tone?: string }) {
  const cls = `flex flex-col items-center gap-1.5 rounded-2xl px-3 py-3 text-xs font-medium shadow-sm ring-1 ring-slate-200 transition hover:-translate-y-0.5 hover:shadow-md ${tone}`;
  return href ? <a href={href} onClick={onClick} className={cls}>{icon}{label}</a> : <button type="button" onClick={onClick} className={cls}>{icon}{label}</button>;
}

const svg = (d: string) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true"><path d={d} /></svg>
);

/** Premium customer / company card: code, status, quick actions, history, notes, portal link. */
export default function PartyProfilePage() {
  const params = useParams<{ kind: string; id: string }>();
  const isCustomer = params.kind === "musteri";
  const base = `/api/${isCustomer ? "customers" : "companies"}/${params.id}`;
  const { can, me } = useAuth();
  const [data, setData] = useState<PartyProfile | null>(null);
  const [history, setHistory] = useState<HistoryEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [account, setAccount] = useState<"charge" | "pay" | null>(null);
  const [historyOf, setHistoryOf] = useState<Debt | null>(null);
  const [whatsapp, setWhatsapp] = useState(false);
  const [editing, setEditing] = useState(false);
  const [tab, setTab] = useState<"history" | "debts" | "payments">("history");

  const load = useCallback(async () => {
    setError(null);
    try {
      const [p, h] = await Promise.all([api<PartyProfile>(`${base}/profile`), api<HistoryEntry[]>(`${base}/history`)]);
      setData(p);
      setHistory(h);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Məlumat yüklənə bilmədi");
    }
  }, [base]);

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
  const overdue = (party.overdueDebts ?? 0) > 0;
  const status = overdue ? { label: "Gecikmiş", tone: "rose" as const } : totals.remaining > 0 ? { label: "Aktiv borc", tone: "amber" as const } : { label: "Borcsuz", tone: "emerald" as const };
  const hasPhone = !!waNumber(party.phone);
  const logContact = (channel: "WHATSAPP" | "CALL") => {
    if (isCustomer) api<Party>(`${base}/contact`, { method: "POST", json: { channel } }).then(() => load()).catch(() => undefined);
  };
  const initials = party.name.split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase();

  return (
    <>
      <div className="mb-4"><Link href="/kontragentlar" className="text-sm text-slate-500 hover:text-navy-800">← Müştəri və şirkətlər</Link></div>
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      {/* Card header */}
      <section className="mb-6 overflow-hidden rounded-3xl bg-gradient-to-br from-navy-800 via-navy-700 to-violet-900 p-6 text-white shadow-xl">
        <div className="flex flex-col gap-5 sm:flex-row sm:items-center">
          <div className="flex h-20 w-20 shrink-0 items-center justify-center rounded-3xl bg-white/15 font-serif text-3xl font-semibold ring-2 ring-white/20">{initials || "?"}</div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="truncate font-serif text-2xl font-semibold sm:text-3xl">{party.name}</h1>
              <Badge tone={status.tone}>{status.label}</Badge>
            </div>
            <p className="mt-1 text-sm text-white/70">
              {[isCustomer ? party.code : "Şirkət", party.phone, `Qeydiyyat: ${formatDateTime(party.createdAt).slice(0, 10)}`].filter(Boolean).join(" · ")}
            </p>
            <p className="mt-1 text-xs text-white/50">
              Son əməliyyat: {party.lastActivityAt ? formatDateTime(party.lastActivityAt) : "—"}
              {isCustomer && <> · Son əlaqə: {party.lastContactAt ? formatDateTime(party.lastContactAt) : "—"}</>}
            </p>
            {party.note && <p className="mt-2 text-sm text-white/80">{party.note}</p>}
          </div>
        </div>
        <div className="mt-6 grid grid-cols-3 gap-3 text-center">
          {[["Ümumi borc", totals.totalDebt], ["Ödənilib", totals.paid], ["Qalıq borc", totals.remaining]].map(([l, v]) => (
            <div key={l as string} className="rounded-2xl bg-white/10 px-3 py-3">
              <p className="text-[11px] uppercase tracking-wider text-white/60">{l}</p>
              <p className="mt-1 font-serif text-lg font-semibold tabular-nums sm:text-2xl">{formatMoney(v as number)}</p>
            </div>
          ))}
        </div>
        {overdue && <p className="mt-4 rounded-xl bg-rose-500/20 px-4 py-2 text-sm text-rose-100">Son ödəmə tarixi keçmiş {party.overdueDebts} borc var.</p>}
      </section>

      {/* Quick actions */}
      <section className="mb-6 grid grid-cols-3 gap-3 sm:grid-cols-6" aria-label="Sürətli əməliyyatlar">
        <QuickButton onClick={() => (hasPhone ? setWhatsapp(true) : setError("Telefon nömrəsi yoxdur və ya düzgün deyil"))} icon={<WhatsAppIcon />} label="WhatsApp" tone="bg-emerald-50 text-emerald-700" />
        <QuickButton href={party.phone ? `tel:${party.phone.replace(/[^\d+]/g, "")}` : undefined} onClick={() => (party.phone ? logContact("CALL") : setError("Telefon nömrəsi yoxdur"))} icon={svg("M5 4h4l2 5-3 2a11 11 0 0 0 5 5l2-3 5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2Z")} label="Zəng et" tone="bg-sky-50 text-sky-700" />
        {writable.length > 0 && <QuickButton onClick={() => setAccount("charge")} icon={svg("M12 5v14M5 12h14")} label="Borc əlavə et" tone="bg-violet-50 text-violet-700" />}
        {writable.length > 0 && <QuickButton onClick={() => setAccount("pay")} icon={svg("M4 7h16v10H4V7Zm0 3h16")} label="Ödəniş qəbul et" tone="bg-teal-50 text-teal-700" />}
        <QuickButton onClick={() => download(`${base}/statement`, "cixaris.pdf").catch((e) => setError(e instanceof ApiError ? e.message : "PDF hazırlanmadı"))} icon={svg("M7 3h7l5 5v13H7V3Zm7 0v5h5")} label="PDF hesabat" tone="bg-rose-50 text-rose-700" />
        <QuickButton onClick={() => setEditing(true)} icon={svg("M4 20h4L19 9l-4-4L4 16v4Z")} label="Redaktə et" />
      </section>

      {!isCustomer && (
        <section className="mb-6 grid gap-4 sm:grid-cols-2">
          <StatCard label={DIRECTION_LABEL.COMPANY_RECEIVABLE} value={formatMoney(byType("COMPANY_RECEIVABLE"))} tone="emerald" />
          <StatCard label={DIRECTION_LABEL.COMPANY_PAYABLE} value={formatMoney(byType("COMPANY_PAYABLE"))} tone="rose" />
        </section>
      )}

      <div className="grid gap-6 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <Card>
            <div className="mb-4 inline-flex rounded-xl bg-slate-100 p-1" role="tablist">
              {([["history", "Tarixçə"], ["debts", "Borclar"], ["payments", "Ödənişlər"]] as const).map(([k, l]) => (
                <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)} className={`rounded-lg px-4 py-1.5 text-sm font-medium ${tab === k ? "bg-white text-navy-800 shadow" : "text-slate-500"}`}>{l}</button>
              ))}
            </div>
            {tab === "history" && (!history || history.length === 0 ? <EmptyState title="Əməliyyat yoxdur" /> : (
              <ol className="relative space-y-4 border-l-2 border-slate-200 pl-6">
                {[...history].reverse().map((e) => (
                  <li key={e.id} className="relative">
                    <span className={`absolute -left-[31px] top-1 h-3.5 w-3.5 rounded-full ring-4 ring-white ${e.type.startsWith("PAYMENT") ? "bg-emerald-500" : "bg-violet-600"}`} />
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                      <p className="text-sm font-medium text-navy-800">{ENTRY_LABEL[e.type]}{!isCustomer && <span className="ml-2 text-xs text-slate-400">{DIRECTION_LABEL[e.debtType]}</span>}</p>
                      <p className={`font-semibold tabular-nums ${e.type.startsWith("PAYMENT") ? "text-emerald-700" : "text-rose-700"}`}>{e.type.startsWith("PAYMENT") ? "−" : "+"}{formatMoney(Math.abs(e.amount))}</p>
                    </div>
                    <p className="text-xs text-slate-500">
                      {[e.entryDate && formatDate(e.entryDate), e.receiptNumber && `Çek: ${e.receiptNumber}`, e.note, e.actorName].filter(Boolean).join(" · ")}
                    </p>
                    <p className="text-xs text-slate-400">Qalıq: {formatMoney(e.balanceAfter)} · qeyd edilib {formatDateTime(e.createdAt)}</p>
                  </li>
                ))}
              </ol>
            ))}
            {tab === "debts" && (debts.length === 0 ? <EmptyState title="Borc yoxdur" /> : (
              <Table>
                <thead className="bg-slate-50/70"><tr><Th>Tarix</Th>{!isCustomer && <Th>İstiqamət</Th>}<Th className="text-right">Məbləğ</Th><Th className="text-right">Ödənilib</Th><Th className="text-right">Qalıq</Th><Th>Status</Th><Th /></tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {debts.map((d) => {
                    const late = d.dueDate && d.remainingAmount > 0 && d.dueDate < new Date().toISOString().slice(0, 10);
                    return (
                      <tr key={d.id}>
                        <Td>{formatDate(d.debtDate)}{d.dueDate && <div className={`text-xs ${late ? "font-semibold text-rose-600" : "text-slate-400"}`}>son: {formatDate(d.dueDate)}</div>}</Td>
                        {!isCustomer && <Td className="text-xs">{DIRECTION_LABEL[d.debtType]}</Td>}
                        <Td className="text-right tabular-nums">{formatMoney(d.amount)}</Td>
                        <Td className="text-right tabular-nums text-emerald-700">{formatMoney(d.paidAmount)}</Td>
                        <Td className={`text-right font-semibold tabular-nums ${d.remainingAmount > 0 ? "text-rose-700" : "text-emerald-700"}`}>{formatMoney(d.remainingAmount)}</Td>
                        <Td><Badge tone={late ? "rose" : STATUS_TONE[d.status]}>{late ? "Gecikmiş" : DEBT_STATUS_LABEL[d.status]}</Badge></Td>
                        <Td className="text-right"><Button variant="ghost" className="px-2.5 py-1.5" onClick={() => setHistoryOf(d)}>Tarixçə</Button></Td>
                      </tr>
                    );
                  })}
                </tbody>
              </Table>
            ))}
            {tab === "payments" && (payments.length === 0 ? <EmptyState title="Ödəniş yoxdur" /> : (
              <Table>
                <thead className="bg-slate-50/70"><tr><Th>Tarix</Th><Th>Üsul</Th><Th>Qəbul edən</Th><Th className="text-right">Məbləğ</Th></tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {payments.map((p) => (
                    <tr key={p.id}><Td>{formatDateTime(p.paidAt)}</Td><Td>{METHOD_LABEL[p.paymentMethod]}</Td><Td className="text-slate-500">{p.recordedByName ?? "—"}</Td><Td className="text-right font-semibold tabular-nums text-emerald-700">{formatMoney(p.amount)}</Td></tr>
                  ))}
                </tbody>
              </Table>
            ))}
          </Card>
        </div>
        {isCustomer && (
          <div className="space-y-6">
            <NotesCard base={base} />
            <PortalCard base={base} phone={party.phone} name={party.name} storeName={me?.storeName ?? ""} />
          </div>
        )}
      </div>

      {account && (
        <AccountModal mode={account} isCustomer={isCustomer} partyId={party.id} onClose={() => setAccount(null)}
          onSaved={(msg) => { setAccount(null); setNotice(msg); load(); }} />
      )}
      {historyOf && <HistoryModal debt={historyOf} onClose={() => setHistoryOf(null)} />}
      {whatsapp && (
        <WhatsAppModal phone={party.phone} name={party.name} storeName={me?.storeName ?? ""} total={totals.totalDebt} paid={totals.paid}
          remaining={totals.remaining} lastPayment={payments[0]?.amount ?? null} onOpen={() => logContact("WHATSAPP")} onClose={() => setWhatsapp(false)} />
      )}
      {editing && (
        <EditPartyModal base={base} party={party} isCustomer={isCustomer} onClose={() => setEditing(false)}
          onSaved={() => { setEditing(false); setNotice("Məlumatlar yeniləndi"); load(); }} />
      )}
    </>
  );
}

/** Editable templates; opening WhatsApp does not mean the message was sent — the user sends it there. */
function WhatsAppModal(props: { phone: string | null; name: string; storeName: string; total: number; paid: number; remaining: number; lastPayment: number | null; onOpen: () => void; onClose: () => void }) {
  const [key, setKey] = useState<TemplateKey>(props.remaining > 0 ? "reminder" : "thanks");
  const vars = { store: props.storeName, name: props.name, total: props.total, paid: props.paid, remaining: props.remaining, lastPayment: props.lastPayment };
  const [text, setText] = useState(() => templateText(key, vars));
  const [opened, setOpened] = useState(false);
  const app = waLink(props.phone, text);
  const web = waWebLink(props.phone, text);
  const open = () => { setOpened(true); props.onOpen(); };
  return (
    <Modal open title={`WhatsApp — ${props.name}`} onClose={props.onClose} footer={<Button variant="ghost" onClick={props.onClose}>Bağla</Button>}>
      <Field label="Şablon">
        <Select value={key} onChange={(e) => { const k = e.target.value as TemplateKey; setKey(k); setText(templateText(k, vars)); }}>
          {(Object.keys(TEMPLATE_LABEL) as TemplateKey[]).map((k) => <option key={k} value={k}>{TEMPLATE_LABEL[k]}</option>)}
        </Select>
      </Field>
      <div className="mt-4">
        <Field label="Mesaj (dəyişə bilərsiniz)">
          <Textarea rows={7} value={text} onChange={(e) => setText(e.target.value)} />
        </Field>
      </div>
      <div className="mt-4 grid gap-2 sm:grid-cols-2">
        {app && <a href={app} target="_blank" rel="noopener noreferrer" onClick={open} className="inline-flex items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-emerald-500"><WhatsAppIcon /> WhatsApp tətbiqində aç</a>}
        {web && <a href={web} target="_blank" rel="noopener noreferrer" onClick={open} className="inline-flex items-center justify-center gap-2 rounded-xl border border-emerald-600/40 px-4 py-2.5 text-sm font-medium text-emerald-700 hover:bg-emerald-50">WhatsApp Web-də aç</a>}
      </div>
      <p className={`mt-3 text-xs ${opened ? "font-medium text-amber-700" : "text-slate-400"}`}>
        {opened ? "WhatsApp açıldı. Mesaj hələ göndərilməyib — WhatsApp-da “Göndər” düyməsini özünüz basın." : "WhatsApp yalnız hazır mətnlə açılır; mesajı orada özünüz göndərirsiniz."}
      </p>
    </Modal>
  );
}

function NotesCard({ base }: { base: string }) {
  const [notes, setNotes] = useState<CustomerNote[] | null>(null);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => {
    api<CustomerNote[]>(`${base}/notes`).then(setNotes).catch((e) => setError(e instanceof ApiError ? e.message : "Qeydlər yüklənmədi"));
  }, [base]);
  useEffect(load, [load]);
  async function add(e: FormEvent) {
    e.preventDefault();
    if (!text.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api(`${base}/notes`, { method: "POST", json: { body: text.trim() } });
      setText("");
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Qeyd saxlanmadı");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Daxili qeydlər</h2>
      <p className="mb-3 text-xs text-slate-400">Yalnız mağaza işçiləri görür; müştəri görmür.</p>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <form onSubmit={add} className="mb-4 flex gap-2">
        <Input maxLength={1000} placeholder="Məs: Ayın sonunda ödəyəcək" value={text} onChange={(e) => setText(e.target.value)} aria-label="Yeni qeyd" />
        <Button type="submit" variant="primary" disabled={busy || !text.trim()}>Yaz</Button>
      </form>
      {!notes ? <Spinner /> : notes.length === 0 ? <p className="text-sm text-slate-400">Qeyd yoxdur.</p> : (
        <ul className="space-y-3">
          {notes.map((n) => (
            <li key={n.id} className="rounded-xl bg-slate-50 px-3 py-2">
              <p className="text-sm text-navy-800">{n.body}</p>
              <p className="mt-1 text-xs text-slate-400">{n.authorName} · {formatDateTime(n.createdAt)}</p>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function PortalCard({ base, phone, name, storeName }: { base: string; phone: string | null; name: string; storeName: string }) {
  const [links, setLinks] = useState<PortalLink[] | null>(null);
  const [days, setDays] = useState("30");
  const [created, setCreated] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    api<PortalLink[]>(`${base}/portal-links`).then(setLinks).catch((e) => setError(e instanceof ApiError ? e.message : "Linklər yüklənmədi"));
  }, [base]);
  useEffect(load, [load]);
  async function create() {
    setError(null);
    try {
      const res = await api<{ url: string }>(`${base}/portal-links`, { method: "POST", json: { days: Number(days) } });
      setCreated(res.url);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Link yaradılmadı");
    }
  }
  async function revoke(id: string) {
    setError(null);
    try {
      await api(`${base}/portal-links/${id}`, { method: "DELETE" });
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Ləğv edilmədi");
    }
  }
  const share = created ? waLink(phone, `Salam, ${name}. "${storeName}" mağazasında borc məlumatınıza bu linkdən baxa bilərsiniz:\n${created}`) : null;
  return (
    <Card>
      <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Müştəri kabineti</h2>
      <p className="mb-3 text-xs text-slate-400">Müştəri girişsiz yalnız öz borcunu, ödənişlərini və PDF çıxarışını görür.</p>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <div className="flex gap-2">
        <Select value={days} onChange={(e) => setDays(e.target.value)} aria-label="Müddət">
          {[["7", "7 gün"], ["30", "30 gün"], ["90", "90 gün"], ["365", "1 il"]].map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </Select>
        <Button variant="gold" onClick={create}>Link yarat</Button>
      </div>
      {created && (
        <div className="mt-3 rounded-xl bg-emerald-50 p-3 text-xs">
          <p className="mb-1 font-medium text-emerald-800">Link hazırdır (yalnız indi göstərilir):</p>
          <p className="break-all text-emerald-900">{created}</p>
          <div className="mt-2 flex gap-2">
            <Button variant="outline" className="px-3 py-1.5" onClick={() => navigator.clipboard?.writeText(created)}>Kopyala</Button>
            {share && <a href={share} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 rounded-xl bg-emerald-600 px-3 py-1.5 font-medium text-white"><WhatsAppIcon /> Göndər</a>}
          </div>
        </div>
      )}
      <ul className="mt-4 space-y-2">
        {links?.map((l) => (
          <li key={l.id} className="flex items-center justify-between gap-2 text-xs">
            <span>
              <Badge tone={l.active ? "emerald" : "slate"}>{l.active ? "Aktiv" : l.revokedAt ? "Ləğv edilib" : "Vaxtı bitib"}</Badge>
              <span className="ml-2 text-slate-500">{formatDateTime(l.createdAt)} · bitir {l.expiresAt ? formatDateTime(l.expiresAt).slice(0, 10) : "—"}{l.lastUsedAt ? ` · açılıb ${formatDateTime(l.lastUsedAt)}` : ""}</span>
            </span>
            {l.active && <button type="button" onClick={() => revoke(l.id)} className="font-medium text-rose-600 hover:underline">Ləğv et</button>}
          </li>
        ))}
      </ul>
    </Card>
  );
}

function EditPartyModal({ base, party, isCustomer, onClose, onSaved }: { base: string; party: Party; isCustomer: boolean; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ name: party.name, phone: party.phone ?? "", note: party.note ?? "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api(base, { method: "PUT", json: { name: form.name, phone: form.phone || null, note: form.note || null } });
      onSaved();
    } catch (err) {
      if (err instanceof ApiError) { setError(err.message); setErrors(err.fields ?? {}); } else setError("Saxlanmadı");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal open title={`Redaktə — ${party.name}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="edit-party" disabled={busy}>Saxla</Button></>}>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="edit-party" onSubmit={submit} className="space-y-4">
        <Field label={isCustomer ? "Ad, soyad" : "Şirkət adı"} error={errors.name}><Input required maxLength={150} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        <Field label="Telefon" error={errors.phone}><Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} placeholder="+994 50 000 00 00" /></Field>
        <Field label="Qeyd"><Input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></Field>
      </form>
    </Modal>
  );
}
