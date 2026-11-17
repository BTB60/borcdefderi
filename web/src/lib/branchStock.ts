"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";

export type BranchQty = { storeId: string; storeName: string; qty: number };

/**
 * Digər filiallarda: for every product of this branch, the quantity in the owner's other branches
 * (empty when the store has no branches). Loaded once per page.
 */
export function useBranchStock(reloadKey?: unknown): Record<string, BranchQty[]> {
  const [map, setMap] = useState<Record<string, BranchQty[]>>({});
  useEffect(() => {
    api<Record<string, BranchQty[]>>("/api/products/branch-stock").then(setMap).catch(() => setMap({}));
  }, [reloadKey]);
  return map;
}

/** "Yasamal 4 · Nərimanov 0" — short names, for a tile or a table cell. */
export function shortBranch(name: string): string {
  return name.replace(/\s*filialı?\s*/i, " ").replace(/\s*\(.*?\)\s*/g, " ").trim() || name;
}
