"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, PageHeader, Select, Spinner, Textarea, cx } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { formatDate, formatMoney } from "@/lib/format";
import { waLink } from "@/lib/whatsapp";

type Recipient = { id: string; name: string; phone: string; openBalance: number; lastPurchase: string | null; birthday: string | null; bonusBalance: number };
type Birthday = { id: string; name: string; phone: string | null; birthday: string; daysUntil: number };

const FILTERS: [string, string][] = [
  ["ALL", "Bütün müştərilər"],
  ["NO_DEBT", "Borcu olmayanlar"],
  ["HAS_DEBT", "Borcu olanlar"],
  ["INACTIVE", "60 gündür alış etməyənlər"],
  ["BONUS", "Bonusu olanlar"],
];

const TEMPLATES = [
  "Salam, {ad}! {mağaza}-da bu həftə bütün mallara 10% endirim var. Sizi gözləyirik!",
  "Salam, {ad}! {mağaza}-a yeni mallar gəlib. Buyurun, baxın!",
  "Salam, {ad}! Sizdə {bonus} bonus var — növbəti alışda istifadə edə bilərsiniz. {mağaza}",
];

/**
 * Toplu WhatsApp mesajı: pick who to write to and the text ({ad}, {borc}, {bonus}, {mağaza} are filled in);
 * every message opens in WhatsApp with one click. Ad günü greetings at the top.
 */
export default function CampaignPage() {
  const { me } = useAuth();
  const store = me?.storeName ?? "Mağazamız";
  const [filter, setFilter] = useState("ALL");
  const [list, setList] = useState<Recipient[] | null>(null);
  const [birthdays, setBirthdays] = useState<Birthday[]>([]);
  const [text, setText] = useState(TEMPLATES[0]);
  const [skip, setSkip] = useState<Record<string, boolean>>({});
  const [sent, setSent] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setList(null);
    api<Recipient[]>(`/api/campaign/recipients?filter=${filter}`).then(setList).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
  }, [filter]);
  useEffect(() => {
    api<Birthday[]>("/api/campaign/birthdays?days=7").then(setBirthdays).catch(() => setBirthdays([]));
  }, []);

  const fill = (r: { name: string; openBalance?: number; bonusBalance?: number }) =>
    text.replaceAll("{ad}", r.name).replaceAll("{mağaza}", store)
      .replaceAll("{borc}", formatMoney(r.openBalance ?? 0)).replaceAll("{bonus}", formatMoney(r.bonusBalance ?? 0));
  const chosen = useMemo(() => (list ?? []).filter((r) => !skip[r.id]), [list, skip]);
  const sentCount = chosen.filter((r) => sent[r.id]).length;

  function open(r: Recipient) {
    const link = waLink(r.phone, fill(r));
    if (!link) return;
    window.open(link, "_blank", "noopener");
    setSent((s) => ({ ...s, [r.id]: true }));
    if (sentCount + 1 === chosen.length) {
      api("/api/campaign/sent", { method: "POST", json: { count: chosen.length, text } }).catch(() => undefined);
    }
  }

  const greet = (b: Birthday) => `Salam, ${b.name}! ${store} kollektivi olaraq ad gününüzü təbrik edirik! 🎂 Sizə cansağlığı və uğurlar arzulayırıq!`;

  return (
    <>
      <PageHeader
        title="WhatsApp kampaniyası"
        subtitle="Müştərilərə endirim, yeni mal və ya bonus xəbəri göndərin — hər mesaj WhatsApp-da bir kliklə açılır"
        actions={<Link href="/kontragentlar" className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:border-slate-400">← Müştərilər</Link>}
      />
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      {birthdays.length > 0 && (
        <Card className="mb-5">
          <h2 className="mb-3 font-serif text-lg font-semibold text-navy-800">🎂 Ad günləri (7 gün)</h2>
          <ul className="divide-y divide-slate-100">
            {birthdays.map((b) => {
              const link = waLink(b.phone, greet(b));
              return (
                <li key={b.id} className="flex flex-wrap items-center gap-3 py-2">
                  <span className="flex-1 font-medium text-navy-800">{b.name}</span>
                  <span className="text-sm text-slate-500">{formatDate(b.birthday).slice(0, 5)}</span>
                  <Badge tone={b.daysUntil === 0 ? "emerald" : "slate"}>{b.daysUntil === 0 ? "Bu gün" : `${b.daysUntil} gün sonra`}</Badge>
                  {link && <a href={link} target="_blank" rel="noreferrer" className="rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-emerald-700">Təbrik et</a>}
                </li>
              );
            })}
          </ul>
        </Card>
      )}

      <div className="grid gap-5 lg:grid-cols-[380px_1fr]">
        <Card>
          <Field label="Kimə">
            <Select value={filter} onChange={(e) => { setFilter(e.target.value); setSkip({}); setSent({}); }}>
              {FILTERS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
            </Select>
          </Field>
          <div className="mt-4">
            <Field label="Mesaj" hint="{ad} — müştərinin adı, {borc} — borcu, {bonus} — bonusu, {mağaza} — mağazanın adı">
              <Textarea rows={6} maxLength={1000} value={text} onChange={(e) => setText(e.target.value)} />
            </Field>
          </div>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {TEMPLATES.map((t, i) => (
              <button key={i} onClick={() => setText(t)} className="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600 hover:bg-slate-200">Şablon {i + 1}</button>
            ))}
          </div>
          {list && list[0] && (
            <div className="mt-4 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-900 ring-1 ring-emerald-200">
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-emerald-700">Nümunə</p>
              {fill(list[0])}
            </div>
          )}
        </Card>

        <Card>
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="font-serif text-lg font-semibold text-navy-800">{chosen.length} müştəri</h2>
            <span className="text-sm text-slate-500">Göndərilib: <b className="text-emerald-700">{sentCount}</b> / {chosen.length}</span>
          </div>
          {!list ? <Spinner /> : list.length === 0 ? <EmptyState title="Bu filtrdə telefonu olan müştəri yoxdur" /> : (
            <ul className="max-h-[560px] divide-y divide-slate-100 overflow-y-auto">
              {list.map((r) => (
                <li key={r.id} className={cx("flex items-center gap-3 py-2.5", skip[r.id] && "opacity-40")}>
                  <input type="checkbox" checked={!skip[r.id]} onChange={(e) => setSkip((s) => ({ ...s, [r.id]: !e.target.checked }))} aria-label={`${r.name} seçilsin`} />
                  <div className="min-w-0 flex-1">
                    <div className="font-medium text-navy-800">{r.name}</div>
                    <div className="text-xs text-slate-500">
                      {r.phone}{r.openBalance > 0 ? ` · borc ${formatMoney(r.openBalance)}` : ""}{r.lastPurchase ? ` · son alış ${formatDate(r.lastPurchase)}` : ""}
                    </div>
                  </div>
                  {sent[r.id] ? <Badge tone="emerald">Göndərildi ✓</Badge> : (
                    <Button variant="outline" disabled={!!skip[r.id]} onClick={() => open(r)}>WhatsApp</Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="mt-3 text-xs text-slate-400">WhatsApp hər dəfə bir mesaj açır: göndərib bu səhifəyə qayıdın və növbətini basın. Toplu avtomatik göndəriş WhatsApp qaydalarına görə nömrənin bloklanmasına səbəb ola bilər.</p>
        </Card>
      </div>
    </>
  );
}
