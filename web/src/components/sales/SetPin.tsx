"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Button, Field, Input, Modal } from "@/components/ui";
import { ApiError, api } from "@/lib/api";

/** Kassa PIN-i: set / change / remove your own 4–6 digit PIN (cashier switch and screen lock on the till). */
export function SetPinModal({ onClose }: { onClose: () => void }) {
  const [hasPin, setHasPin] = useState<boolean | null>(null);
  const [pin, setPin] = useState("");
  const [pin2, setPin2] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<{ hasPin: boolean }>("/api/profile/pin").then((r) => setHasPin(r.hasPin)).catch(() => setHasPin(false));
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!/^\d{4,6}$/.test(pin)) { setError("PIN 4-6 rəqəm olmalıdır"); return; }
    if (pin !== pin2) { setError("PIN-lər eyni deyil"); return; }
    setBusy(true);
    setError(null);
    try {
      await api("/api/profile/pin", { method: "PUT", json: { pin, currentPassword: password } });
      setDone("PIN yadda saxlanıldı. Artıq kassada “Kassir dəyiş” və ekran kilidi bu PIN ilə açılır.");
      setHasPin(true);
      setPin(""); setPin2(""); setPassword("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    await api("/api/profile/pin", { method: "DELETE" }).catch(() => undefined);
    setHasPin(false);
    setDone("PIN silindi");
  }

  return (
    <Modal open title="Kassa PIN-i" onClose={onClose}
      footer={<>
        {hasPin && <Button variant="ghost" onClick={remove}>PIN-i sil</Button>}
        <Button variant="ghost" onClick={onClose}>Bağla</Button>
        <Button type="submit" form="pin-form" disabled={busy}>{hasPin ? "PIN-i dəyiş" : "PIN təyin et"}</Button>
      </>}>
      <p className="mb-4 text-sm text-slate-600">
        4–6 rəqəmli PIN ilə ortaq kassada <b>“Kassir dəyiş”</b> edirsiniz və kilidlənmiş ekranı açırsınız. PIN mağazada hər işçidə fərqli olmalıdır.
        {hasPin ? <span className="mt-1 block font-medium text-emerald-700">Sizin PIN-iniz var.</span> : null}
      </p>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      {done && <div className="mb-3"><Alert kind="success">{done}</Alert></div>}
      <form id="pin-form" onSubmit={save} className="grid gap-3 sm:grid-cols-2">
        <Field label="Yeni PIN"><Input type="password" inputMode="numeric" maxLength={6} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} autoComplete="off" /></Field>
        <Field label="PIN-i təkrarla"><Input type="password" inputMode="numeric" maxLength={6} value={pin2} onChange={(e) => setPin2(e.target.value.replace(/\D/g, ""))} autoComplete="off" /></Field>
        <div className="sm:col-span-2"><Field label="Hesab şifrəniz (təsdiq üçün)"><Input type="password" required value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></Field></div>
      </form>
    </Modal>
  );
}
