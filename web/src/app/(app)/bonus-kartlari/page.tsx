"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Spinner, StatCard, Table, Td, Th } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatMoney } from "@/lib/format";
import { useDebounced } from "@/lib/useDebounced";
import { waLink } from "@/lib/whatsapp";
import { CardFace, printLoyaltyCard, type CardDesign } from "@/components/LoyaltyCard";
import { CardDesigner } from "@/components/CardDesigner";

type LoyaltyCard = {
  id: string; customerId: string; number: string; firstName: string; lastName: string; patronymic: string | null; fullName: string;
  phone: string; percent: number; active: boolean; balance: number; earned: number; spent: number; lastSaleAt: string | null;
  activationCode: string; createdAt: string;
};
type Entry = { id: string; kind: string; amount: number; saleNumber: string | null; note: string | null; createdAt: string };

/** The customers' app (HesabPro Kart), built by CI as the kart-latest release. */
const KART_APP_URL = "https://github.com/BTB60/borcdefderi/releases/download/kart-latest/HesabPro-Kart.apk";

const KIND: Record<string, string> = { EARN: "Yığıldı", USE: "Xərcləndi", ADJUST: "Əl ilə", REVERSE: "Qaytarma" };

/** Bonus kartları: each card has its own bonus percent; the card number (barcode) is scanned at the till. */
export default function LoyaltyCardsPage() {
  const { isOwner, can, me } = useAuth();
  const allowed = isOwner || can("SALES") || can("CUSTOMER_DEBTS");
  const [q, setQ] = useState("");
  const query = useDebounced(q);
  const [cards, setCards] = useState<LoyaltyCard[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<LoyaltyCard | "new" | null>(null);
  const [viewing, setViewing] = useState<LoyaltyCard | null>(null);
  const [design, setDesign] = useState<CardDesign | null>(null);
  const [designing, setDesigning] = useState(false);
  const mayDesign = isOwner || can("TILL_SETTINGS");
  useEffect(() => { api<{ card?: CardDesign }>("/api/settings/print-design").then((d) => setDesign(d.card ?? null)).catch(() => undefined); }, []);

  const load = useCallback(() => {
    api<LoyaltyCard[]>(`/api/loyalty-cards${query.trim() ? `?q=${encodeURIComponent(query.trim())}` : ""}`)
      .then(setCards).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [query]);
  useEffect(() => { if (allowed) load(); }, [allowed, load]);

  if (!allowed) return <Card><EmptyState title="Bu bölmə üçün icazəniz yoxdur" /></Card>;

  const total = cards?.reduce((s, c) => s + c.balance, 0) ?? 0;

  return (
    <>
      <PageHeader title="Bonus kartları" subtitle="Hər müştəriyə öz faizi ilə bonus kartı. Kassada kartın barkodunu skan edin — bonus yığılır və xərclənir."
        actions={<div className="flex flex-wrap gap-2">
          {mayDesign && <Button variant="outline" onClick={() => setDesigning(true)}>🎨 Kart dizaynı</Button>}
          <Button variant="gold" onClick={() => setEditing("new")}>+ Yeni kart</Button>
        </div>} />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}
      <div className="mb-4 grid gap-3 sm:grid-cols-2">
        <StatCard label="Kartlar" value={String(cards?.length ?? 0)} hint={query ? "axtarışa görə" : "hamısı"} />
        <StatCard label="Kartlardakı bonus" value={formatMoney(total)} hint="müştərilərin xərcləyə biləcəyi" tone="emerald" />
      </div>
      <Card>
        <Input className="mb-3" placeholder="Ad, soyad, telefon və ya kart nömrəsi" value={q} onChange={(e) => setQ(e.target.value)} />
        {!cards ? <Spinner /> : cards.length === 0 ? (
          <EmptyState title={query ? "Tapılmadı" : "Hələ bonus kartı yoxdur"} text={query ? undefined : "“+ Yeni kart” ilə ilk kartı yaradın."} />
        ) : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Kart</Th><Th>Müştəri</Th><Th>Telefon</Th><Th className="text-right">Faiz</Th><Th className="text-right">Bonus</Th><Th>Son alış</Th><Th /></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {cards.map((c) => (
                <tr key={c.id} className={`cursor-pointer hover:bg-slate-50 ${c.active ? "" : "opacity-60"}`} onClick={() => setViewing(c)}>
                  <Td className="font-mono text-xs">{c.number}{!c.active && <div><Badge tone="rose">bloklanıb</Badge></div>}</Td>
                  <Td className="font-medium text-navy-800">{c.fullName}</Td>
                  <Td className="text-sm">{c.phone}</Td>
                  <Td className="text-right font-semibold tabular-nums">{Number(c.percent)}%</Td>
                  <Td className="text-right font-semibold tabular-nums">{formatMoney(c.balance)}</Td>
                  <Td className="text-xs text-slate-500">{c.lastSaleAt ? formatDateTime(c.lastSaleAt) : "—"}</Td>
                  <Td className="text-right"><Button variant="ghost" className="px-2.5 py-1.5" onClick={(e) => { e.stopPropagation(); setEditing(c); }}>Dəyiş</Button></Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {editing && <CardModal card={editing === "new" ? null : editing} onClose={() => setEditing(null)}
        onSaved={(c, created) => { setEditing(null); setNotice(created ? `Kart yaradıldı: ${c.number} · ${c.fullName} · ${Number(c.percent)}%` : `${c.fullName}: kart yeniləndi`); load(); if (created) setViewing(c); }} />}
      {viewing && <CardDetail card={viewing} design={design} storeName={me?.storeName ?? ""} onClose={() => setViewing(null)} onEdit={() => { setEditing(viewing); setViewing(null); }} />}
      {designing && <CardDesigner initial={design} store={me?.storeName ?? ""} onClose={() => setDesigning(false)}
        onSaved={(d) => { setDesign(d); setDesigning(false); setNotice("Kartın dizaynı yadda saxlanıldı"); }} />}
    </>
  );
}

function CardModal({ card, onClose, onSaved }: { card: LoyaltyCard | null; onClose: () => void; onSaved: (c: LoyaltyCard, created: boolean) => void }) {
  const [form, setForm] = useState({
    firstName: card?.firstName ?? "", lastName: card?.lastName ?? "", patronymic: card?.patronymic ?? "", phone: card?.phone ?? "",
    percent: card ? String(card.percent) : "", active: card?.active ?? true,
  });
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      const saved = await api<LoyaltyCard>(card ? `/api/loyalty-cards/${card.id}` : "/api/loyalty-cards", {
        method: card ? "PUT" : "POST", json: { ...form, patronymic: form.patronymic || null, percent: form.percent.replace(",", ".") },
      });
      onSaved(saved, !card);
    } catch (err) {
      if (err instanceof ApiError) { setError(err.message); setFields(err.fields ?? {}); } else setError("Yadda saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title={card ? `Kart — ${card.number}` : "Yeni bonus kartı"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="card-form" variant="gold" disabled={busy}>{card ? "Saxla" : "Kartı yarat"}</Button></>}>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <form id="card-form" onSubmit={submit} className="grid gap-3 sm:grid-cols-2">
        <Field label="Ad" error={fields.firstName}><Input required autoFocus maxLength={60} value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} /></Field>
        <Field label="Soyad" error={fields.lastName}><Input required maxLength={60} value={form.lastName} onChange={(e) => setForm({ ...form, lastName: e.target.value })} /></Field>
        <Field label="Ata adı"><Input maxLength={60} value={form.patronymic} onChange={(e) => setForm({ ...form, patronymic: e.target.value })} placeholder="məs. Rauf qızı" /></Field>
        <Field label="Telefon nömrəsi" error={fields.phone}><Input required maxLength={30} value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} placeholder="050 123 45 67" /></Field>
        <Field label="Kartın faizi (%)" error={fields.percent} hint="Hər alışın ödənilən hissəsindən bu qədər bonus yığılır (0–50)">
          <Input required type="number" min="0" max="50" step="0.5" value={form.percent} onChange={(e) => setForm({ ...form, percent: e.target.value })} />
        </Field>
        {card && (
          <label className="flex items-center gap-2 self-end pb-2 text-sm">
            <input type="checkbox" className="h-4 w-4" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
            Kart aktivdir (söndürülsə, bonus yığılmır və xərclənmir)
          </label>
        )}
        {!card && <p className="text-xs text-slate-500 sm:col-span-2">Bu telefon nömrəsi ilə müştəri varsa, kart ona bağlanır; yoxdursa, müştəri avtomatik yaradılır.</p>}
      </form>
    </Modal>
  );
}

function CardDetail({ card, design, storeName, onClose, onEdit }: { card: LoyaltyCard; design: CardDesign | null; storeName: string; onClose: () => void; onEdit: () => void }) {
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [data, setData] = useState<LoyaltyCard>(card);
  useEffect(() => {
    api<{ card: LoyaltyCard; entries: Entry[] }>(`/api/loyalty-cards/${card.id}`).then((d) => { setData(d.card); setEntries(d.entries); }).catch(() => setEntries([]));
  }, [card.id]);
  const share = waLink(data.phone, `Salam, ${data.firstName}! “${storeName}” bonus kartınız hazırdır.\nKart nömrəsi: ${data.number}\nFaiz: hər alışdan ${Number(data.percent)}% bonus.\nBalans: ${formatMoney(data.balance)}\nKassada kart nömrəsini və ya telefon nömrənizi deyin.\n\nBütün bonus kartlarınızı “HesabPro Kart” tətbiqində görün: ${KART_APP_URL}\nGiriş üçün aktivləşdirmə kodu: ${data.activationCode}`);
  return (
    <Modal open size="xl" title={`${data.fullName} — bonus kartı`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Bağla</Button><Button variant="outline" onClick={onEdit}>Dəyiş</Button></>}>
      <div className="grid gap-5 md:grid-cols-[300px_1fr]">
        <div>
          <CardFace design={design} store={storeName} name={data.fullName} code={data.number} percent={data.percent} balance={data.balance} />
          {!data.active && <div className="mt-2"><Badge tone="rose">Kart bloklanıb</Badge></div>}
          <div className="mt-3 flex flex-wrap gap-2">
            <Button variant="gold" onClick={() => printLoyaltyCard({ store: storeName, name: data.fullName, code: data.number, percent: Number(data.percent), design })}>Kartı çap et</Button>
            {share && <a href={share} target="_blank" rel="noreferrer" className="rounded-xl bg-emerald-600 px-3 py-2 text-sm font-semibold text-white hover:bg-emerald-700">WhatsApp ilə göndər</a>}
          </div>
        </div>
        <div>
          <div className="mb-3 grid grid-cols-3 gap-2 text-center">
            {[["Balans", data.balance], ["Yığılıb", data.earned], ["Xərclənib", data.spent]].map(([l, v]) => (
              <div key={l as string} className="rounded-xl bg-slate-50 p-2"><p className="text-xs text-slate-500">{l}</p><p className="font-semibold tabular-nums">{formatMoney(v as number)}</p></div>
            ))}
          </div>
          <p className="mb-1 text-xs text-slate-500">Telefon: {data.phone} · yaradılıb {formatDateTime(data.createdAt)}</p>
          <p className="mb-1 text-xs text-slate-600">
            “HesabPro Kart” tətbiqi üçün aktivləşdirmə kodu: <b className="font-mono tracking-widest text-navy-800">{data.activationCode}</b>
            <span className="text-slate-400"> · müştəri kart nömrəsi və bu kodla daxil olur</span>
          </p>
          <h3 className="mb-2 mt-3 text-sm font-semibold text-navy-800">Bonus tarixçəsi</h3>
          {!entries ? <Spinner /> : entries.length === 0 ? <p className="text-sm text-slate-500">Hələ bonus hərəkəti yoxdur.</p> : (
            <div className="max-h-72 divide-y divide-slate-100 overflow-y-auto rounded-xl ring-1 ring-slate-200">
              {entries.map((e) => (
                <div key={e.id} className="flex items-center justify-between px-3 py-2 text-sm">
                  <div><b className="text-navy-800">{KIND[e.kind] ?? e.kind}</b><span className="ml-2 text-xs text-slate-500">{formatDateTime(e.createdAt)}{e.saleNumber ? ` · ${e.saleNumber}` : ""}{e.note ? ` · ${e.note}` : ""}</span></div>
                  <span className={`font-semibold tabular-nums ${e.amount < 0 ? "text-rose-600" : "text-emerald-700"}`}>{e.amount > 0 ? "+" : ""}{formatMoney(e.amount)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
