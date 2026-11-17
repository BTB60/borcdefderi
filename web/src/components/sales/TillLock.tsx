"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { PinPad } from "@/components/sales/PinPad";

const KEY = "hp_lock_minutes";
const EVENT = "hp-lock-settings";

export function lockMinutes(): number {
  try {
    const v = Number(localStorage.getItem(KEY) ?? "5");
    return Number.isFinite(v) ? v : 5;
  } catch {
    return 5;
  }
}

export function setLockMinutes(m: number) {
  try { localStorage.setItem(KEY, String(m)); } catch { /* ignore */ }
  window.dispatchEvent(new Event(EVENT));
}

export function lockNow() {
  window.dispatchEvent(new Event("hp-lock-now"));
}

/**
 * Ekran kilidi (Windows kassa): after N idle minutes (or the 🔒 button) the till is covered; a cashier opens it
 * with their PIN — the same or another cashier (then the till switches to them). Off when the cashier has no PIN.
 */
export function TillLock() {
  const { me } = useAuth();
  const [locked, setLocked] = useState(false);
  const [hasPin, setHasPin] = useState(false);
  const [minutes, setMinutes] = useState(5);
  const last = useRef(Date.now());
  const hasPinRef = useRef(false);
  hasPinRef.current = hasPin;

  useEffect(() => {
    api<{ hasPin: boolean }>("/api/profile/pin").then((r) => setHasPin(r.hasPin)).catch(() => setHasPin(false));
  }, [me?.userId]);

  useEffect(() => {
    const read = () => setMinutes(lockMinutes());
    read();
    const now = () => {
      if (hasPinRef.current) setLocked(true);
      else window.alert("Ekranı kilidləmək üçün əvvəl kassanın altındakı “Kassa PIN-im” düyməsi ilə PIN təyin edin.");
    };
    window.addEventListener(EVENT, read);
    window.addEventListener("hp-lock-now", now);
    return () => { window.removeEventListener(EVENT, read); window.removeEventListener("hp-lock-now", now); };
  }, []);

  useEffect(() => {
    const touch = () => { last.current = Date.now(); };
    const events = ["mousemove", "mousedown", "keydown", "touchstart", "wheel"];
    events.forEach((e) => window.addEventListener(e, touch, { passive: true }));
    const t = setInterval(() => {
      if (hasPin && minutes > 0 && Date.now() - last.current > minutes * 60_000) setLocked(true);
    }, 10_000);
    return () => { events.forEach((e) => window.removeEventListener(e, touch)); clearInterval(t); };
  }, [hasPin, minutes]);

  if (!locked || !hasPin) return null;
  return (
    <PinPad fullScreen title="Kassa kilidlidir" subtitle={`${me?.fullName ?? ""} · davam etmək üçün PIN yazın`}
      onDone={() => { last.current = Date.now(); setLocked(false); }} />
  );
}
