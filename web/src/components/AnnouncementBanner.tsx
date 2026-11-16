"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Announcement } from "@/lib/types";

const KEY = "hesabpro.hiddenAnnouncements";

function hidden(): string[] {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? "[]") as string[];
  } catch {
    return [];
  }
}

const STYLE: Record<Announcement["level"], string> = {
  INFO: "border-sky-200 bg-sky-50 text-sky-900",
  WARNING: "border-amber-200 bg-amber-50 text-amber-900",
  SUCCESS: "border-emerald-200 bg-emerald-50 text-emerald-900",
};

/** Announcements from the platform admin, at the top of every page until the store closes them. */
export function AnnouncementBanner() {
  const [items, setItems] = useState<Announcement[]>([]);

  useEffect(() => {
    api<Announcement[]>("/api/announcements")
      .then((list) => {
        const h = hidden();
        setItems(list.filter((a) => !h.includes(a.id)));
      })
      .catch(() => setItems([]));
  }, []);

  function close(id: string) {
    setItems((list) => list.filter((a) => a.id !== id));
    try {
      localStorage.setItem(KEY, JSON.stringify([...hidden(), id].slice(-50)));
    } catch {
      /* private mode: closes for this visit only */
    }
  }

  if (items.length === 0) return null;
  return (
    <div className="mb-5 space-y-2">
      {items.map((a) => (
        <div key={a.id} role="status" className={`flex items-start gap-3 rounded-xl border px-4 py-3 text-sm ${STYLE[a.level]}`}>
          <span aria-hidden="true" className="text-lg leading-none">📢</span>
          <div className="min-w-0 flex-1">
            <p className="font-semibold">{a.title}</p>
            <p className="mt-0.5 whitespace-pre-line opacity-90">{a.body}</p>
          </div>
          <button onClick={() => close(a.id)} aria-label="Elanı bağla" className="rounded-md px-2 text-lg leading-none opacity-60 hover:opacity-100">×</button>
        </div>
      ))}
    </div>
  );
}
