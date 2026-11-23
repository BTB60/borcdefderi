import { api } from "@/lib/api";

/**
 * Çek və barkod etiketi dizaynı are kept by the server per store (GET/PUT /api/settings/print-design) and mirrored in
 * this browser's storage, which the printing code reads synchronously. A change here is sent to the server shortly
 * after, so every till and browser of the store prints the same design.
 */
export const RECEIPT_DESIGN_KEY = "hp_receipt_design";
export const LABEL_DESIGN_KEY = "hesabpro.labelDesign.v1";

type Part = "receipt" | "label";
const KEYS: Record<Part, string> = { receipt: RECEIPT_DESIGN_KEY, label: LABEL_DESIGN_KEY };

let pending: Partial<Record<Part, true>> = {};
let timer: ReturnType<typeof setTimeout> | null = null;
let listeners: ((ok: boolean) => void)[] = [];

function read(part: Part): unknown {
  try {
    const raw = localStorage.getItem(KEYS[part]);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/** Loads the store's design into this browser (called once after sign-in). A part the store never saved is left as is. */
export async function loadPrintDesign(): Promise<void> {
  try {
    const d = await api<Partial<Record<Part, unknown>>>("/api/settings/print-design");
    for (const part of ["receipt", "label"] as const) {
      if (d?.[part]) localStorage.setItem(KEYS[part], JSON.stringify(d[part]));
    }
    // the paper width and footer are also read under their old keys
    const r = d?.receipt as { paper?: string; footer?: string } | undefined;
    if (r?.paper) localStorage.setItem("hp_paper", r.paper);
    if (r?.footer !== undefined) localStorage.setItem("hp_receipt_footer", r.footer);
  } catch { /* offline, signed out, or no permission: keep what this browser has */ }
}

/** Sends this browser's design of [part] to the store a moment later (changes in a row are sent once). */
export function pushPrintDesign(part: Part) {
  pending[part] = true;
  if (timer) clearTimeout(timer);
  timer = setTimeout(async () => {
    const parts = pending;
    pending = {};
    timer = null;
    const body: Partial<Record<Part, unknown>> = {};
    for (const p of Object.keys(parts) as Part[]) body[p] = read(p);
    let ok = true;
    try {
      await api("/api/settings/print-design", { method: "PUT", json: body });
    } catch {
      ok = false; // no permission (a plain seller) or offline: the design still works on this computer
    }
    listeners.forEach((l) => l(ok));
  }, 800);
}

/** Tells a settings screen whether the last change reached the server. */
export function onPrintDesignSaved(listener: (ok: boolean) => void): () => void {
  listeners.push(listener);
  return () => { listeners = listeners.filter((l) => l !== listener); };
}
