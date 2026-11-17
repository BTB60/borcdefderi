"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";

export type Branch = { id: string; name: string; address: string | null; phone: string | null; current: boolean; main: boolean; subscriptionActive: boolean; subscriptionEnds: string | null };

/** Switch the whole app to another branch (owner with more than one branch). */
export async function switchBranch(id: string) {
  await api(`/api/branches/${id}/switch`, { method: "POST" });
  // every page and cache belongs to one branch: start clean
  try { localStorage.removeItem("pos_cache_products"); localStorage.removeItem("pos_cache_customers"); localStorage.removeItem("hp_free_item"); } catch { /* ignore */ }
  window.location.assign("/dashboard");
}

/** Under the store name in the sidebar: the branch list for the owner (shown once there are two or more). */
export function BranchSwitcher() {
  const [branches, setBranches] = useState<Branch[]>([]);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api<Branch[]>("/api/branches").then(setBranches).catch(() => undefined); }, []);
  if (branches.length < 2) return null;
  const current = branches.find((b) => b.current);
  return (
    <label className="mt-1.5 block">
      <span className="sr-only">Filial</span>
      <select value={current?.id ?? ""} disabled={busy}
        onChange={(e) => { setBusy(true); void switchBranch(e.target.value).catch(() => setBusy(false)); }}
        className="w-full max-w-[11rem] truncate rounded-lg border border-white/15 bg-white/10 px-2 py-1 text-xs text-white outline-none hover:bg-white/15 focus:border-gold-400">
        {branches.map((b) => <option key={b.id} value={b.id} className="text-navy-800">{b.main ? "🏠 " : "📍 "}{b.name}</option>)}
      </select>
    </label>
  );
}
