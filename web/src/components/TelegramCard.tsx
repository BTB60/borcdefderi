"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Card, Select } from "@/components/ui";
import { ApiError, api } from "@/lib/api";

type Status = { configured: boolean; botUsername: string; linked: boolean; hour: number; lastSent: string | null };

/**
 * Telegram hesabatı: every evening at the chosen hour the day of the store (sales, cash / card, profit, expenses,
 * till differences, accounts, best sellers, goods running out) goes to the owner's Telegram.
 */
export function TelegramCard() {
  const [st, setSt] = useState<Status | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api<Status>("/api/telegram").then(setSt).catch(() => undefined);
  useEffect(() => { void load(); }, []);
  // while the owner is linking in Telegram, look every few seconds
  useEffect(() => {
    if (!link || st?.linked) return;
    const t = setInterval(() => void load(), 4000);
    return () => clearInterval(t);
  }, [link, st?.linked]);

  if (!st) return null;

  async function run(fn: () => Promise<unknown>, ok?: string) {
    setBusy(true);
    setMsg(null);
    try {
      await fn();
      if (ok) setMsg({ kind: "success", text: ok });
    } catch (e) {
      setMsg({ kind: "error", text: e instanceof ApiError ? e.message : "Alınmadı" });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="flex items-center gap-2 font-serif text-lg font-semibold text-navy-800"><span aria-hidden="true">✈️</span>Telegram hesabatı</h2>
      <p className="mt-1 text-sm text-slate-500">
        Hər axşam günün hesabatı telefonunuza gəlir: satış, nağd və kart, mənfəət, xərclər, kassa fərqləri, hesablar,
        ən çox satılan və bitən mallar.
      </p>
      {msg && <div className="mt-3"><Alert kind={msg.kind}>{msg.text}</Alert></div>}
      {!st.configured ? (
        <p className="mt-4 rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-600">Telegram botu hələ qoşulmayıb. Tezliklə aktiv olacaq.</p>
      ) : st.linked ? (
        <div className="mt-4 space-y-3">
          <p className="flex items-center gap-2 text-sm font-medium text-emerald-700">✓ Telegram qoşulub{st.lastSent ? ` · son hesabat ${st.lastSent.split("-").reverse().join(".")}` : ""}</p>
          <div className="flex flex-wrap items-center gap-3">
            <label className="text-sm text-slate-700">Göndərmə saatı</label>
            <Select value={String(st.hour)} className="w-28" disabled={busy}
              onChange={(e) => void run(async () => setSt(await api<Status>("/api/telegram", { method: "PUT", body: JSON.stringify({ hour: Number(e.target.value) }) })), "Saat yadda saxlandı")}>
              {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{String(h).padStart(2, "0")}:00</option>)}
            </Select>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" disabled={busy} onClick={() => void run(async () => {
              const r = await api<{ sent: boolean }>("/api/telegram/test", { method: "POST" });
              if (!r.sent) throw new ApiError(500, "SEND_FAILED", "Göndərilmədi — Telegram-da botu bloklamamısınız?");
            }, "Bugünkü hesabat Telegram-a göndərildi")}>İndi göndər</Button>
            <Button variant="ghost" disabled={busy} onClick={() => void run(async () => { setSt(await api<Status>("/api/telegram", { method: "DELETE" })); setLink(null); }, "Telegram ayrıldı")}>Ayır</Button>
          </div>
        </div>
      ) : (
        <div className="mt-4 space-y-3">
          <ol className="list-decimal space-y-1 pl-5 text-sm text-slate-600">
            <li>“Telegram-ı qoş” basın — Telegram açılacaq.</li>
            <li>Orada <b>Start</b> (Başla) düyməsinə basın.</li>
            <li>Bu səhifə özü “qoşulub” göstərəcək.</li>
          </ol>
          <div className="flex flex-wrap gap-2">
            <Button variant="gold" disabled={busy} onClick={() => void run(async () => {
              const r = await api<{ url: string }>("/api/telegram/link", { method: "POST" });
              setLink(r.url);
              window.open(r.url, "_blank", "noopener");
            })}>Telegram-ı qoş</Button>
            {link && <a href={link} target="_blank" rel="noopener noreferrer" className="self-center text-sm font-medium text-navy-800 underline">Telegram açılmadı? Bura basın</a>}
          </div>
          {link && <p className="text-xs text-slate-500">Telegram-da Start basandan sonra bir neçə saniyə gözləyin…</p>}
        </div>
      )}
    </Card>
  );
}
