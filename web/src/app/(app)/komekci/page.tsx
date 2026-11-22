"use client";

import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Alert, Button, Card, PageHeader, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";

type Turn = { role: "user" | "assistant"; text: string };
type Status = { enabled: boolean; usedToday: number; dailyLimit: number };
type Reply = { reply: string; toolsUsed: string[]; usedToday: number; dailyLimit: number };

const STORE_KEY = "hp_assistant_chat";
const SUGGESTIONS = [
  "Bu gün nə qədər satış olub?",
  "Ən çox borcu olan 5 müştəri kimdir?",
  "Hansı mallar az qalıb?",
  "Bu ayın mənfəəti nə qədərdir?",
  "Kassada nə qədər pul var?",
];

/** Köməkçi (AI): ask about the store's own data in plain words. Read-only: it never changes anything. */
export default function AssistantPage() {
  const [status, setStatus] = useState<Status | null>(null);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api<Status>("/api/assistant").then(setStatus).catch((e) => setError(e instanceof ApiError ? e.message : "Yüklənmədi"));
    try {
      const saved = sessionStorage.getItem(STORE_KEY);
      if (saved) setTurns(JSON.parse(saved) as Turn[]);
    } catch { /* storage blocked */ }
  }, []);

  useEffect(() => {
    try { sessionStorage.setItem(STORE_KEY, JSON.stringify(turns.slice(-40))); } catch { /* storage blocked */ }
    bottom.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [turns, busy]);

  async function send(question: string) {
    const q = question.trim();
    if (!q || busy) return;
    const next: Turn[] = [...turns, { role: "user", text: q }];
    setTurns(next);
    setText("");
    setBusy(true);
    setError(null);
    try {
      const r = await api<Reply>("/api/assistant/chat", { method: "POST", json: { messages: next.slice(-20) } });
      setTurns([...next, { role: "assistant", text: r.reply }]);
      setStatus((s) => (s ? { ...s, usedToday: r.usedToday, dailyLimit: r.dailyLimit } : s));
    } catch (e) {
      // the question stays out of the history so the next one starts cleanly
      setTurns(turns);
      setText(q);
      setError(e instanceof ApiError ? e.message : "Köməkçi cavab vermədi");
    } finally {
      setBusy(false);
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    send(text);
  }

  function onKey(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send(text);
    }
  }

  function clear() {
    setTurns([]);
    setError(null);
  }

  if (!status) return error ? <Alert>{error}</Alert> : <Spinner />;

  return (
    <>
      <PageHeader title="Köməkçi" subtitle="Mağazanız haqqında adi dillə soruşun: borclar, satışlar, qalıq, kassa" />
      {!status.enabled ? (
        <Card>
          <p className="text-sm text-slate-600">Köməkçi hələ qoşulmayıb. Platforma administratoru qoşandan sonra burada işləyəcək.</p>
        </Card>
      ) : (
        <Card>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500">
            <span>Köməkçi yalnız baxır və hesablayır, heç nəyi dəyişmir. Rəqəmləri yoxlamaq üçün müvafiq bölməyə baxın.</span>
            <span>Bu gün: {status.usedToday} / {status.dailyLimit} sual</span>
          </div>

          <div className="mb-4 max-h-[60vh] min-h-[240px] space-y-3 overflow-y-auto rounded-xl bg-slate-50 p-3">
            {turns.length === 0 && (
              <div className="py-6 text-center">
                <p className="mb-3 text-sm text-slate-500">Məsələn, bunları soruşa bilərsiniz:</p>
                <div className="flex flex-wrap justify-center gap-2">
                  {SUGGESTIONS.map((s) => (
                    <button key={s} type="button" onClick={() => send(s)}
                      className="rounded-full border border-slate-200 bg-white px-3 py-1.5 text-sm text-navy-800 hover:border-slate-300">
                      {s}
                    </button>
                  ))}
                </div>
              </div>
            )}
            {turns.map((t, i) => (
              <div key={i} className={t.role === "user" ? "flex justify-end" : "flex justify-start"}>
                <div className={`max-w-[85%] whitespace-pre-wrap rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed ${
                  t.role === "user" ? "bg-navy-800 text-white" : "border border-slate-200 bg-white text-slate-800"}`}>
                  {t.text}
                </div>
              </div>
            ))}
            {busy && <div className="flex justify-start"><div className="rounded-2xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm text-slate-500">Yazır…</div></div>}
            <div ref={bottom} />
          </div>

          {error && <div className="mb-3"><Alert>{error}</Alert></div>}
          <form onSubmit={submit} className="flex items-end gap-2">
            <textarea value={text} onChange={(e) => setText(e.target.value)} onKeyDown={onKey} maxLength={2000} rows={2}
              placeholder="Sualınızı yazın… (Enter — göndər, Shift+Enter — yeni sətir)"
              className="min-h-[44px] flex-1 resize-none rounded-xl border border-slate-200 px-3 py-2 text-sm focus:border-navy-800 focus:outline-none" />
            <Button type="submit" disabled={busy || !text.trim()}>{busy ? "…" : "Göndər"}</Button>
            {turns.length > 0 && <Button type="button" variant="ghost" onClick={clear}>Təmizlə</Button>}
          </form>
        </Card>
      )}
    </>
  );
}
