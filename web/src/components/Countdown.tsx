"use client";

import { useEffect, useState } from "react";
import { cx } from "./ui";

/** Days left before the subscription counts as "running out" (matches app.subscription.reminder-days). */
export const WARN_DAYS = 7;

/** Current time that re-renders every `intervalMs`. One timer per component that uses it. */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}

export type CountdownState = { expired: boolean; warning: boolean; days: number; hours: number; minutes: number; seconds: number };

export function countdown(expiresAt: string | null | undefined, now: number): CountdownState | null {
  if (!expiresAt) return null;
  const ms = new Date(expiresAt).getTime() - now;
  if (Number.isNaN(ms)) return null;
  if (ms <= 0) return { expired: true, warning: true, days: 0, hours: 0, minutes: 0, seconds: 0 };
  const total = Math.floor(ms / 1000);
  const days = Math.floor(total / 86400);
  return {
    expired: false,
    warning: days < WARN_DAYS,
    days,
    hours: Math.floor((total % 86400) / 3600),
    minutes: Math.floor((total % 3600) / 60),
    seconds: total % 60,
  };
}

const pad = (n: number) => String(n).padStart(2, "0");

export function countdownText(c: CountdownState): string {
  if (c.expired) return "Vaxtı bitib";
  return `${c.days} gün ${pad(c.hours)}:${pad(c.minutes)}:${pad(c.seconds)}`;
}

/**
 * Live subscription counter. Red when the subscription has expired or fewer than WARN_DAYS days remain.
 * Pass `now` when many counters are on one page so they share a single timer.
 */
export function Countdown({ expiresAt, now, className, emptyText = "—" }: { expiresAt: string | null | undefined; now?: number; className?: string; emptyText?: string }) {
  const ownNow = useNow(now === undefined ? 1000 : 3_600_000);
  const c = countdown(expiresAt, now ?? ownNow);
  if (!c) return <span className={cx("text-slate-400", className)}>{emptyText}</span>;
  return (
    <span
      className={cx(
        "inline-flex items-center gap-1.5 font-mono tabular-nums",
        c.warning ? "font-semibold text-rose-600" : "text-emerald-700",
        className,
      )}
      aria-label={c.expired ? "Abunəliyin vaxtı bitib" : `Abunəliyin bitməsinə ${c.days} gün qalıb`}
    >
      <span className={cx("h-2 w-2 rounded-full", c.warning ? "animate-pulse bg-rose-500" : "bg-emerald-500")} />
      {countdownText(c)}
    </span>
  );
}
