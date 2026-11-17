"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Badge, Button, EmptyState, Input, Spinner, Textarea, cx } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { formatDate, formatDateTime, todayBaku } from "@/lib/format";

export type AdminNote = {
  id: string; storeId: string; storeName: string; storePhone: string | null; ownerPhone: string | null; body: string;
  followUpOn: string | null; doneAt: string | null; createdAt: string; createdByName: string | null;
};

/** Ready-made tags; the admin can also type their own. */
export const PRESET_TAGS = ["VIP", "Zəng et", "Problemli", "Maraqlanmır", "Endirim istəyir", "Yeni"];

export function tagTone(t: string): "rose" | "gold" | "sky" | "amber" | "slate" | "emerald" {
  switch (t) {
    case "VIP": return "gold";
    case "Problemli": return "rose";
    case "Zəng et": return "sky";
    case "Endirim istəyir": return "amber";
    case "Yeni": return "emerald";
    default: return "slate";
  }
}

const addDays = (d: string, n: number) => {
  const x = new Date(`${d}T12:00:00`);
  x.setDate(x.getDate() + n);
  return x.toISOString().slice(0, 10);
};

/** Admin's notes about one store (with "remind me on" dates) and the store's tags. */
export function StoreNotes({ storeId, tags: initialTags, onTags }: { storeId: string; tags: string[]; onTags?: (t: string[]) => void }) {
  const [notes, setNotes] = useState<AdminNote[] | null>(null);
  const [tags, setTags] = useState<string[]>(initialTags);
  const [custom, setCustom] = useState("");
  const [body, setBody] = useState("");
  const [followUp, setFollowUp] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const today = todayBaku();

  const load = () => api<AdminNote[]>(`/api/admin/stores/${storeId}/notes`).then(setNotes).catch(() => setNotes([]));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [storeId]);

  async function saveTags(next: string[]) {
    setError(null);
    try {
      const r = await api<{ tags: string[] }>(`/api/admin/stores/${storeId}/tags`, { method: "PUT", json: { tags: next } });
      setTags(r.tags);
      onTags?.(r.tags);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Etiket saxlanmadı");
    }
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    if (!body.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await api(`/api/admin/stores/${storeId}/notes`, { method: "POST", json: { body: body.trim(), followUpOn: followUp || null } });
      setBody("");
      setFollowUp("");
      await load();
    } catch (e2) {
      setError(e2 instanceof ApiError ? e2.message : "Qeyd saxlanmadı");
    } finally {
      setBusy(false);
    }
  }

  async function done(n: AdminNote, value: boolean) {
    await api(`/api/admin/notes/${n.id}/done`, { method: "POST", json: { done: value } }).catch(() => undefined);
    load();
  }

  async function remove(n: AdminNote) {
    await api(`/api/admin/notes/${n.id}`, { method: "DELETE" }).catch(() => undefined);
    load();
  }

  const all = [...new Set([...PRESET_TAGS, ...tags])];

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_1.4fr]">
      <div className="space-y-3">
        <div className="rounded-2xl p-4 ring-1 ring-slate-200">
          <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-slate-500">Etiketlər</p>
          <div className="flex flex-wrap gap-1.5">
            {all.map((t) => {
              const on = tags.includes(t);
              return (
                <button key={t} type="button" onClick={() => saveTags(on ? tags.filter((x) => x !== t) : [...tags, t])} aria-pressed={on}
                  className={cx("rounded-full border px-3 py-1 text-xs font-medium transition", on ? "border-navy-800 bg-navy-800 text-white" : "border-slate-200 text-slate-600 hover:border-slate-400")}>
                  {on ? "✓ " : ""}{t}
                </button>
              );
            })}
          </div>
          <form className="mt-3 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (custom.trim()) { saveTags([...tags, custom.trim()]); setCustom(""); } }}>
            <Input value={custom} onChange={(e) => setCustom(e.target.value)} maxLength={30} placeholder="Öz etiketiniz" aria-label="Yeni etiket" />
            <Button type="submit" variant="outline">Əlavə et</Button>
          </form>
        </div>

        <form onSubmit={add} className="space-y-3 rounded-2xl p-4 ring-1 ring-slate-200">
          <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Yeni qeyd</p>
          <Textarea value={body} onChange={(e) => setBody(e.target.value)} maxLength={1000} placeholder="Məs.: Zəng etdim, ayın 20-də ödəyəcəyini dedi" />
          <div>
            <p className="mb-1.5 text-xs text-slate-500">Xatırlat (“Bu gün” siyahısına düşür)</p>
            <div className="flex flex-wrap items-center gap-1.5">
              {[["Sabah", 1], ["3 gün", 3], ["1 həftə", 7]].map(([l, n]) => (
                <button key={l} type="button" onClick={() => setFollowUp(addDays(today, n as number))}
                  className={cx("rounded-full border px-3 py-1 text-xs", followUp === addDays(today, n as number) ? "border-gold-500 bg-gold-500/10 font-semibold" : "border-slate-200 text-slate-600")}>{l}</button>
              ))}
              <Input type="date" value={followUp} min={today} onChange={(e) => setFollowUp(e.target.value)} className="w-auto" aria-label="Xatırlatma tarixi" />
              {followUp && <button type="button" onClick={() => setFollowUp("")} className="text-xs text-slate-400 hover:text-rose-600">təmizlə</button>}
            </div>
          </div>
          {error && <p className="text-sm text-rose-600">{error}</p>}
          <Button type="submit" disabled={busy || !body.trim()}>Qeydi saxla</Button>
        </form>
      </div>

      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Qeydlər</p>
        {!notes ? <Spinner /> : notes.length === 0 ? <EmptyState title="Hələ qeyd yoxdur" text="Zənglər, vədlər, şikayətlər — hamısını burada saxlayın." /> : (
          <ol className="relative space-y-3 border-l-2 border-slate-100 pl-5">
            {notes.map((n) => {
              const overdue = !n.doneAt && n.followUpOn && n.followUpOn <= today;
              return (
                <li key={n.id} className="relative">
                  <span className={cx("absolute -left-[27px] top-1.5 h-3 w-3 rounded-full ring-4 ring-white", n.doneAt ? "bg-emerald-500" : overdue ? "bg-amber-500" : "bg-slate-300")} />
                  <div className={cx("rounded-xl p-3 ring-1", n.doneAt ? "bg-slate-50 ring-slate-100" : overdue ? "bg-amber-50 ring-amber-200" : "bg-white ring-slate-200")}>
                    <p className={cx("whitespace-pre-line text-sm", n.doneAt ? "text-slate-400 line-through" : "text-navy-800")}>{n.body}</p>
                    <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400">
                      <span>{formatDateTime(n.createdAt)}{n.createdByName ? ` · ${n.createdByName}` : ""}</span>
                      {n.followUpOn && <Badge tone={n.doneAt ? "slate" : overdue ? "amber" : "sky"}>⏰ {formatDate(n.followUpOn)}</Badge>}
                      <span className="flex-1" />
                      {n.followUpOn && <button onClick={() => done(n, !n.doneAt)} className="font-medium text-emerald-700 hover:underline">{n.doneAt ? "Geri aç" : "Edildi ✓"}</button>}
                      <button onClick={() => remove(n)} className="hover:text-rose-600">Sil</button>
                    </div>
                  </div>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </div>
  );
}
