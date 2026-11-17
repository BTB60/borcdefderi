"use client";

import { useEffect, useState } from "react";
import { Card, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { waLink } from "@/lib/whatsapp";
import type { AdminStore } from "@/lib/types";
import type { AdminNote } from "@/components/admin/StoreNotes";

type Go = (tab: "stores" | "expiring" | "subscriptions" | "plans") => void;

/**
 * "Bu gün": everything that needs the admin today in one list — payments to approve, follow-ups that are due,
 * packages ending within 3 days, paying stores that stopped using the app and new sign-ups.
 */
export function TodayPanel({ pending, go }: { pending: number; go: Go }) {
  const [stores, setStores] = useState<AdminStore[] | null>(null);
  const [notes, setNotes] = useState<AdminNote[] | null>(null);

  const loadNotes = () => api<AdminNote[]>("/api/admin/follow-ups").then(setNotes).catch(() => setNotes([]));
  useEffect(() => {
    api<AdminStore[]>("/api/admin/stores").then(setStores).catch(() => setStores([]));
    loadNotes();
  }, []);

  async function done(n: AdminNote) {
    setNotes((l) => l?.filter((x) => x.id !== n.id) ?? null);
    await api(`/api/admin/notes/${n.id}/done`, { method: "POST", json: { done: true } }).catch(loadNotes);
  }

  const soon = (stores ?? []).filter((s) => s.subscriptionState === "ACTIVE" && (s.daysRemaining ?? 99) <= 3);
  const idle = (stores ?? []).filter((s) => s.idle);
  const fresh = (stores ?? []).filter((s) => Date.now() - new Date(s.createdAt).getTime() < 2 * 86_400_000);

  const rows: { key: string; icon: string; tone: string; title: string; text: string; onClick: () => void; count: number }[] = [
    { key: "pay", icon: "₼", tone: "bg-amber-100 text-amber-700", title: "Ödəniş təsdiq gözləyir", text: "Çeki yoxlayıb təsdiqləyin", onClick: () => go("subscriptions"), count: pending },
    { key: "soon", icon: "⏳", tone: "bg-rose-100 text-rose-700", title: "3 gün ərzində bitir", text: soon.slice(0, 3).map((s) => s.name).join(", "), onClick: () => go("expiring"), count: soon.length },
    { key: "idle", icon: "💤", tone: "bg-slate-200 text-slate-700", title: "Ödəyir, amma istifadə etmir", text: "14 gündür heç bir əməliyyat yoxdur — zəng edin", onClick: () => go("stores"), count: idle.length },
    { key: "new", icon: "✦", tone: "bg-emerald-100 text-emerald-700", title: "Yeni qeydiyyat (2 gün)", text: fresh.slice(0, 3).map((s) => s.name).join(", "), onClick: () => go("stores"), count: fresh.length },
  ].filter((r) => r.count > 0);

  const nothing = stores && notes && rows.length === 0 && notes.length === 0;

  return (
    <Card>
      <div className="mb-4 flex items-baseline justify-between">
        <h2 className="font-serif text-lg font-semibold text-navy-800">Bu gün</h2>
        <span className="text-xs text-slate-400">{formatDate(new Date().toISOString())}</span>
      </div>
      {!stores || !notes ? <p className="text-sm text-slate-400">Yüklənir…</p> : nothing ? (
        <div className="rounded-xl bg-emerald-50 px-4 py-6 text-center text-sm text-emerald-700">Hər şey qaydasındadır — bu gün görüləsi iş yoxdur ✓</div>
      ) : (
        <ul className="space-y-2">
          {rows.map((r) => (
            <li key={r.key}>
              <button onClick={r.onClick} className="flex w-full items-center gap-3 rounded-xl p-2.5 text-left transition hover:bg-slate-50">
                <span className={cx("flex h-9 w-9 shrink-0 items-center justify-center rounded-xl text-base font-bold", r.tone)}>{r.icon}</span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-navy-800">{r.title}</span>
                  {r.text && <span className="block truncate text-xs text-slate-500">{r.text}</span>}
                </span>
                <span className="rounded-full bg-navy-800 px-2.5 py-0.5 text-xs font-bold tabular-nums text-white">{r.count}</span>
              </button>
            </li>
          ))}
          {notes.map((n) => {
            const phone = n.ownerPhone || n.storePhone;
            const wa = waLink(phone, "Salam! HesabPro dəstək xidmətindən yazırıq.");
            return (
              <li key={n.id} className="flex items-start gap-3 rounded-xl bg-sky-50/60 p-2.5">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-sky-100 text-base">⏰</span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-navy-800">{n.storeName}</span>
                  <span className="block text-xs text-slate-600">{n.body}</span>
                  <span className="mt-1 flex flex-wrap gap-3 text-xs">
                    {wa && <a href={wa} target="_blank" rel="noreferrer" className="font-medium text-emerald-700 hover:underline">WhatsApp</a>}
                    {phone && <a href={`tel:${phone.replace(/[^+\d]/g, "")}`} className="font-medium text-sky-700 hover:underline">Zəng et</a>}
                    <button onClick={() => done(n)} className="font-medium text-navy-800 hover:underline">Edildi ✓</button>
                  </span>
                </span>
                {n.followUpOn && <span className="shrink-0 text-xs text-slate-400">{formatDate(n.followUpOn)}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
