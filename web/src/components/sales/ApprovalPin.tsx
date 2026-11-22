"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import { Alert, Button, Field, Input, Modal } from "@/components/ui";
import { ApiError } from "@/lib/api";

/**
 * Sale returns and cancels above the store's limit need a manager's approval. `run` sends the request; when the server
 * answers APPROVAL_REQUIRED it opens the PIN dialog and sends the same request again with the PIN (or the scanned
 * manager card). Any other error is thrown to the caller. Render `dialog` somewhere in the component.
 */
export function useReturnApproval(): { run: <T>(send: (pin: string | null) => Promise<T>, onDone: (result: T) => void) => Promise<void>; dialog: ReactNode } {
  const [ask, setAsk] = useState<{ message: string; send: (pin: string) => Promise<void> } | null>(null);

  async function run<T>(send: (pin: string | null) => Promise<T>, onDone: (result: T) => void) {
    try {
      onDone(await send(null));
    } catch (e) {
      if (e instanceof ApiError && e.code === "APPROVAL_REQUIRED") {
        setAsk({ message: e.message, send: async (pin) => onDone(await send(pin)) });
        return;
      }
      throw e;
    }
  }

  const dialog = ask ? <ApprovalPinModal message={ask.message} send={ask.send} onClose={() => setAsk(null)} /> : null;
  return { run, dialog };
}

/**
 * The manager types their PIN on the cashier's screen, or scans their manager card (a USB scanner types the code and
 * presses Enter). Wrong PIN / card: the error stays here and they try again.
 */
export function ApprovalPinModal({ message, send, onClose }: { message: string; send: (pin: string) => Promise<void>; onClose: () => void }) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [locked, setLocked] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (pin.length < 4) return;
    setBusy(true);
    setError(null);
    try {
      await send(pin);
      onClose();
    } catch (err) {
      setPin("");
      setError(err instanceof ApiError ? err.message : "Təsdiqlənmədi");
      if (err instanceof ApiError && err.code === "APPROVAL_LOCKED") setLocked(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open title="Menecer təsdiqi" onClose={onClose}
      footer={<>
        <Button variant="ghost" onClick={onClose}>İmtina</Button>
        <Button variant="primary" type="submit" form="approval-pin" disabled={busy || locked || pin.length < 4}>{busy ? "Yoxlanılır…" : "Təsdiq et"}</Button>
      </>}>
      <p className="mb-3 text-sm text-slate-600">{message}</p>
      {error && <div className="mb-3"><Alert>{error}</Alert></div>}
      <form id="approval-pin" onSubmit={submit}>
        <Field label="Menecer PIN-i və ya kart" hint="PIN-i yazın və ya menecer kartını skan edin">
          <Input type="password" inputMode="numeric" autoComplete="off" autoFocus maxLength={32} disabled={locked}
            className="text-center text-2xl tracking-[0.5em]" value={pin}
            onChange={(e) => setPin(e.target.value.replace(/[^0-9A-Za-z]/g, "").toUpperCase())} />
        </Field>
      </form>
    </Modal>
  );
}
