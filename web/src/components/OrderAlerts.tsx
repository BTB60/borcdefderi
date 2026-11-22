"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Notification } from "@/lib/types";

const POLL_MS = 30_000;
const SOUND = "/sounds/hesabpro-alert.wav"; // the same sound as the phone app

/**
 * New online orders while the site (or the Windows till) is open: the HesabPro sound, a card in the corner that
 * stays until it is closed, and a system notification when the tab is in the background. Checks every 30 seconds;
 * the notifications it reads also refresh the bell.
 */
export function OrderAlerts({ storeId, onNotes }: { storeId: string; onNotes: (n: Notification[]) => void }) {
  const [fresh, setFresh] = useState<Notification[]>([]);
  const onNotesRef = useRef(onNotes);
  onNotesRef.current = onNotes;

  useEffect(() => {
    const key = `hp_seen_orders:${storeId}`;
    let stopped = false;

    const read = (): string[] | null => {
      try {
        const v = window.localStorage.getItem(key);
        return v ? (JSON.parse(v) as string[]) : null;
      } catch {
        return null;
      }
    };
    const write = (v: string[]) => {
      try { window.localStorage.setItem(key, JSON.stringify(v)); } catch { /* storage blocked */ }
    };

    async function check() {
      let list: Notification[];
      try {
        list = await api<Notification[]>("/api/notifications");
      } catch {
        return; // offline or server asleep: next tick
      }
      if (stopped) return;
      onNotesRef.current(list);
      const orders = list.filter((n) => n.type === "ONLINE_ORDER");
      const titles = orders.map((n) => n.title);
      const seen = read();
      write(titles); // only what the server still reports, so it never grows
      // first time in this browser: the orders already there are not news
      if (seen === null) return;
      const news = orders.filter((n) => !seen.includes(n.title));
      if (news.length === 0) return;
      setFresh((f) => [...news, ...f].slice(0, 5));
      new Audio(SOUND).play().catch(() => undefined); // blocked until the page was clicked once: the card still shows
      if (document.hidden && "Notification" in window && window.Notification.permission === "granted") {
        for (const n of news) new window.Notification(n.title, { body: n.message, icon: "/icons/app-192.png", tag: n.title });
      }
    }

    void check();
    const t = window.setInterval(check, POLL_MS);
    return () => { stopped = true; window.clearInterval(t); };
  }, [storeId]);

  // ask once for system notifications, so an order is seen with the tab in the background too
  useEffect(() => {
    if (fresh.length > 0 && "Notification" in window && window.Notification.permission === "default") {
      void window.Notification.requestPermission().catch(() => undefined);
    }
  }, [fresh.length]);

  if (fresh.length === 0) return null;
  return (
    <div className="fixed bottom-4 right-4 z-50 w-[min(22rem,calc(100vw-2rem))] space-y-2" role="status" aria-live="assertive">
      {fresh.map((n) => (
        <div key={n.title} className="rounded-2xl border border-gold-500 bg-white p-4 shadow-lg">
          <div className="flex items-start gap-3">
            <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gold-500 text-navy-900" aria-hidden>
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                <path d="M4 6h16l-1.5 9h-13L4 6Zm3 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm10 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2ZM8 3h8" />
              </svg>
            </span>
            <div className="min-w-0 flex-1">
              <p className="font-semibold text-navy-800">{n.title}</p>
              <p className="mt-0.5 text-sm text-slate-600">{n.message}</p>
              <Link href="/sifarisler" onClick={() => setFresh((f) => f.filter((x) => x.title !== n.title))}
                className="mt-2 inline-block rounded-lg bg-navy-800 px-3 py-1.5 text-sm font-medium text-white hover:bg-navy-900">
                Sifarişə bax
              </Link>
            </div>
            <button type="button" onClick={() => setFresh((f) => f.filter((x) => x.title !== n.title))}
              className="text-slate-400 hover:text-slate-600" aria-label="Bağla">✕</button>
          </div>
        </div>
      ))}
    </div>
  );
}
