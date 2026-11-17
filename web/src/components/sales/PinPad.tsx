"use client";

import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { AuthResponse } from "@/lib/types";

/**
 * Kassir PIN pad: a cashier types their 4–6 digit PIN and the till switches to them (also opens the locked
 * screen). Works with the on-screen keys or the keyboard.
 */
export function PinPad({ title, subtitle, onDone, onCancel, fullScreen = false }: {
  title: string; subtitle?: string; onDone: () => void; onCancel?: () => void; fullScreen?: boolean;
}) {
  const { signIn } = useAuth();
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(value = pin) {
    if (value.length < 4 || busy) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api<AuthResponse>("/api/till/pin-switch", { method: "POST", json: { pin: value } });
      signIn(r);
      setPin("");
      onDone();
    } catch (e) {
      setPin("");
      setError(e instanceof ApiError ? e.message : "Alınmadı");
    } finally {
      setBusy(false);
    }
  }

  const press = (k: string) => {
    setError(null);
    if (k === "⌫") setPin((p) => p.slice(0, -1));
    else if (k === "OK") void submit();
    else setPin((p) => (p.length < 6 ? p + k : p));
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (/^\d$/.test(e.key)) { e.preventDefault(); e.stopPropagation(); press(e.key); }
      else if (e.key === "Backspace") { e.preventDefault(); press("⌫"); }
      else if (e.key === "Enter") { e.preventDefault(); void submit(); }
      else if (e.key === "Escape" && onCancel) onCancel();
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  });

  const pad = (
    <div className="w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-2xl">
      <p className="font-serif text-xl font-semibold text-navy-800">{title}</p>
      {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
      <div className="my-5 flex justify-center gap-3" aria-label="PIN">
        {Array.from({ length: Math.max(4, pin.length) }).map((_, i) => (
          <span key={i} className={`h-4 w-4 rounded-full ${i < pin.length ? "bg-navy-800" : "bg-slate-200"}`} />
        ))}
      </div>
      {error && <p className="mb-3 text-sm font-medium text-rose-600">{error}</p>}
      <div className="grid grid-cols-3 gap-2">
        {["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"].map((k) => (
          <button key={k} type="button" disabled={busy} onClick={() => press(k)}
            className={`h-16 rounded-2xl text-2xl font-semibold transition active:scale-95 ${k === "OK" ? "bg-gold-500 text-navy-900 hover:bg-gold-400" : k === "⌫" ? "bg-slate-100 text-slate-600 hover:bg-slate-200" : "bg-slate-50 text-navy-800 ring-1 ring-slate-200 hover:bg-white"}`}>
            {k}
          </button>
        ))}
      </div>
      {onCancel && <button type="button" onClick={onCancel} className="mt-4 text-sm text-slate-500 hover:text-navy-800">Ləğv et</button>}
      <p className="mt-3 text-xs text-slate-400">PIN-i kassanın altındakı “Kassa PIN-im” düyməsi ilə təyin edin</p>
    </div>
  );

  return (
    <div className={`fixed inset-0 z-50 flex items-center justify-center p-4 ${fullScreen ? "bg-gradient-to-br from-navy-900 to-navy-700" : "bg-navy-900/60 backdrop-blur-sm"}`}>
      {pad}
    </div>
  );
}
