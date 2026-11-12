"use client";

import { useEffect, useState, type ReactNode } from "react";
import { Badge, Button, Card, EmptyState, Spinner, StatCard } from "@/components/ui";
import { Countdown, countdown, useNow } from "@/components/Countdown";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import type { AdminStore } from "@/lib/types";

const SOON_DAYS = 7;
const EXPIRED_LOOKBACK_DAYS = 30;
/** A reminder younger than this counts as "already contacted". */
const RECENT_REMINDER_DAYS = 3;
const DAY = 86_400_000;

type Group = "today" | "week" | "expired";
type Channel = "WHATSAPP" | "PHONE" | "EMAIL";

const GROUPS: { key: Group; title: string; hint: string }[] = [
  { key: "today", title: "Bu gün və sabah bitir", hint: "Ən təcili — bu gün yazın" },
  { key: "week", title: `${SOON_DAYS} gün içində bitir`, hint: "Vaxtında xatırladın ki, fasilə olmasın" },
  { key: "expired", title: "Vaxtı bitib", hint: `Son ${EXPIRED_LOOKBACK_DAYS} gündə bitənlər — geri qaytarmaq üçün` },
];

/** Azerbaijani numbers ("050 798 81 77", "+994 50 …") as digits for wa.me; null when it is not a usable number. */
export function whatsappNumber(raw: string | null | undefined): string | null {
  if (!raw) return null;
  let d = raw.replace(/\D/g, "");
  if (d.startsWith("00")) d = d.slice(2);
  if (d.length === 10 && d.startsWith("0")) d = "994" + d.slice(1);
  if (d.length === 9) d = "994" + d;
  return d.length >= 11 && d.length <= 15 ? d : null;
}

function groupOf(s: AdminStore, now: number): Group | null {
  if (s.subscriptionState === "NONE" || !s.expiresAt) return null;
  const left = new Date(s.expiresAt).getTime() - now;
  if (left <= 0) return left > -EXPIRED_LOOKBACK_DAYS * DAY ? "expired" : null;
  if (left <= 2 * DAY) return "today";
  if (left <= SOON_DAYS * DAY) return "week";
  return null;
}

function reminderText(s: AdminStore, expired: boolean): string {
  const hello = s.ownerName ? `Salam, ${s.ownerName}!` : "Salam!";
  const end = formatDate(s.subscriptionEnd);
  const body = expired
    ? `HesabPro-da “${s.name}” mağazanızın abunəliyi ${end} tarixində bitib. Məlumatlarınız qorunur — abunəliyi yeniləyən kimi hər şey əvvəlki kimi işləyəcək.`
    : `HesabPro-da “${s.name}” mağazanızın abunəliyi ${end} tarixində bitir. İşinizdə fasilə olmasın deyə vaxtında yeniləməyi xatırladırıq.`;
  return `${hello}\n${body}\nYeniləmək üçün: Profil → Abunəlik bölməsində paketi seçib ödəniş qəbzini göndərin. Sualınız olsa, bu nömrəyə yazın.\nHesabPro`;
}

/**
 * Admin "Bitən abunəliklər": stores whose subscription ends within a week or ended recently, with a ready
 * WhatsApp / call / e-mail reminder. Each reminder is recorded so the admin sees who was already contacted.
 */
export function ExpiringTab({ onError, onNotice, actions, reload }: {
  onError: (m: string | null) => void;
  onNotice?: (m: string | null) => void;
  /** Renew / extend buttons, rendered by the page that owns those dialogs. */
  actions: (s: AdminStore) => ReactNode;
  reload: number;
}) {
  const [items, setItems] = useState<AdminStore[] | null>(null);
  const [reminded, setReminded] = useState<Record<string, string>>({});
  const now = useNow(60_000);

  useEffect(() => {
    api<AdminStore[]>("/api/admin/stores")
      .then(setItems)
      .catch((e) => onError(e instanceof ApiError ? e.message : "Mağazalar yüklənmədi"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  async function remind(s: AdminStore, channel: Channel) {
    const expired = groupOf(s, Date.now()) === "expired";
    const text = reminderText(s, expired);
    const phone = s.ownerPhone || s.phone;
    if (channel === "WHATSAPP") {
      const wa = whatsappNumber(phone);
      if (!wa) return onError("Bu mağazanın düzgün telefon nömrəsi yoxdur");
      window.open(`https://wa.me/${wa}?text=${encodeURIComponent(text)}`, "_blank", "noopener");
    } else if (channel === "PHONE") {
      window.location.href = `tel:${(phone ?? "").replace(/[^+\d]/g, "")}`;
    } else {
      const subject = expired ? "HesabPro abunəliyiniz bitib" : "HesabPro abunəliyiniz bitir";
      window.location.href = `mailto:${s.ownerEmail}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(text)}`;
    }
    try {
      const res = await api<{ remindedAt: string }>(`/api/admin/stores/${s.id}/remind`, { method: "POST", json: { channel } });
      setReminded((r) => ({ ...r, [s.id]: res.remindedAt }));
      onNotice?.(`“${s.name}” xatırladıldı kimi qeyd edildi`);
    } catch (e) {
      onError(e instanceof ApiError ? e.message : "Xatırlatma qeyd olunmadı");
    }
  }

  if (!items) return <Spinner />;

  const lastReminded = (s: AdminStore) => reminded[s.id] ?? s.lastRemindedAt ?? null;
  const recentlyReminded = (s: AdminStore) => {
    const at = lastReminded(s);
    return !!at && now - new Date(at).getTime() < RECENT_REMINDER_DAYS * DAY;
  };
  const byGroup: Record<Group, AdminStore[]> = { today: [], week: [], expired: [] };
  for (const s of items) {
    const g = groupOf(s, now);
    if (g) byGroup[g].push(s);
  }
  // Not yet contacted first, then by end date (soonest first; for expired: most recently ended first).
  for (const g of Object.keys(byGroup) as Group[]) {
    byGroup[g].sort((a, b) => {
      const r = Number(recentlyReminded(a)) - Number(recentlyReminded(b));
      if (r !== 0) return r;
      const ta = new Date(a.expiresAt ?? 0).getTime();
      const tb = new Date(b.expiresAt ?? 0).getTime();
      return g === "expired" ? tb - ta : ta - tb;
    });
  }
  const all = [...byGroup.today, ...byGroup.week, ...byGroup.expired];
  const waiting = all.filter((s) => !recentlyReminded(s) && !s.pendingPlanCode).length;

  return (
    <>
      <section className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Bu gün / sabah bitir" value={String(byGroup.today.length)} tone="rose" />
        <StatCard label={`${SOON_DAYS} gün içində`} value={String(byGroup.week.length)} tone="gold" />
        <StatCard label={`Bitib (son ${EXPIRED_LOOKBACK_DAYS} gün)`} value={String(byGroup.expired.length)} tone="rose" />
        <StatCard label="Xatırlatma gözləyir" value={String(waiting)} tone="navy" hint={`Son ${RECENT_REMINDER_DAYS} gündə yazılmayanlar`} />
      </section>
      {all.length === 0 ? (
        <Card><EmptyState title="Bitmək üzrə olan abunəlik yoxdur" text={`Növbəti ${SOON_DAYS} gündə heç bir abunəlik bitmir.`} /></Card>
      ) : (
        <div className="space-y-6">
          {GROUPS.filter((g) => byGroup[g.key].length > 0).map((g) => (
            <Card key={g.key}>
              <div className="mb-4">
                <h2 className={`text-base font-semibold ${g.key === "week" ? "text-navy-800" : "text-rose-700"}`}>{g.title} · {byGroup[g.key].length}</h2>
                <p className="text-xs text-slate-500">{g.hint}</p>
              </div>
              <ul className="divide-y divide-slate-100">
                {byGroup[g.key].map((s) => {
                  const phone = s.ownerPhone || s.phone;
                  const at = lastReminded(s);
                  const recent = recentlyReminded(s);
                  const c = countdown(s.expiresAt, now);
                  return (
                    <li key={s.id} className="flex flex-col gap-3 py-3 lg:flex-row lg:items-center lg:justify-between">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium text-navy-800">{s.name}</span>
                          {s.activePlan && <Badge tone="navy">{s.activePlan}</Badge>}
                          {s.pendingPlanCode && <Badge tone="amber">Ödəniş sorğusu göndərib</Badge>}
                          {s.status !== "ACTIVE" && <Badge tone="rose">Dayandırılıb</Badge>}
                          {at && <Badge tone={recent ? "emerald" : "slate"}>Xatırladıldı: {formatDateTime(at)}</Badge>}
                        </div>
                        <div className="mt-0.5 text-xs text-slate-500">
                          {s.ownerName ?? "—"} · {s.ownerEmail ?? "—"}{phone ? ` · ${phone}` : ""}
                        </div>
                        <div className="mt-0.5 text-xs">
                          <span className="text-slate-400">Bitmə: {formatDate(s.subscriptionEnd)} · </span>
                          {c?.expired
                            ? <span className="font-medium text-rose-700">{Math.max(1, Math.floor((now - new Date(s.expiresAt!).getTime()) / DAY))} gün əvvəl bitib</span>
                            : <Countdown expiresAt={s.expiresAt} now={now} className="text-xs" />}
                        </div>
                      </div>
                      <div className="flex flex-wrap gap-1.5 lg:justify-end">
                        <Button
                          variant={recent ? "outline" : "gold"}
                          onClick={() => remind(s, "WHATSAPP")}
                          disabled={!whatsappNumber(phone)}
                          title={whatsappNumber(phone) ? "Hazır mesajla WhatsApp açılır" : "Telefon nömrəsi yoxdur"}
                        >
                          {recent ? "Yenə yaz" : "WhatsApp"}
                        </Button>
                        {phone && <Button variant="ghost" onClick={() => remind(s, "PHONE")}>Zəng</Button>}
                        {s.ownerEmail && <Button variant="ghost" onClick={() => remind(s, "EMAIL")}>E-poçt</Button>}
                        {actions(s)}
                      </div>
                    </li>
                  );
                })}
              </ul>
            </Card>
          ))}
        </div>
      )}
    </>
  );
}
