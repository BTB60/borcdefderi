"use client";

import { useState, type FormEvent } from "react";
import { Alert, Button, Modal } from "@/components/ui";
import { API_URL, ApiError, download, getToken } from "@/lib/api";

type Result = { applied: boolean; created: number; skipped: number; withBalance: number; errors: { row: number; message: string }[] };

/** Excel import of customers or companies (e.g. moving over from another program), with opening balances. */
export function PartyImportModal({ tab, onClose, onDone }: { tab: "customers" | "companies"; onClose: () => void; onDone: (m: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const customers = tab === "customers";

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const token = getToken();
      const res = await fetch(`${API_URL}/api/${tab}/import`, { method: "POST", body: fd, headers: token ? { Authorization: `Bearer ${token}` } : {} });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new ApiError(res.status, body?.error ?? "IMPORT", body?.message ?? "Import alınmadı");
      const r = body as Result;
      if (r.applied) {
        onDone(`Import tamamlandı: ${r.created} yeni ${customers ? "müştəri" : "şirkət"}` +
          (r.withBalance ? `, ${r.withBalance} nəfərə başlanğıc borc yazıldı` : "") +
          (r.skipped ? `, ${r.skipped} artıq mövcud olduğu üçün keçildi` : ""));
      } else setResult(r);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Import alınmadı");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open size="xl" title={customers ? "Müştəriləri Excel-dən import et" : "Şirkətləri Excel-dən import et"} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Bağla</Button><Button type="submit" form="party-import-form" variant="gold" disabled={!file || busy}>{busy ? "Yüklənir…" : "Import et"}</Button></>}>
      <div className="mb-4 space-y-2 text-sm text-slate-600">
        <p>Sütunlar: <strong>{customers ? "Ad, Telefon, Qeyd, Borc" : "Ad, Telefon, Qeyd, Bizə borcu, Bizim borcumuz"}</strong>. Yalnız “Ad” mütləqdir.</p>
        <p>{customers ? "“Borc”" : "Borc sütunları"} doldurulsa, həmin məbləğ başlanğıc borc kimi yazılır. Telefonu və ya adı artıq olan sətirlər keçilir, ona görə eyni faylı iki dəfə yükləsəniz heç nə təkrarlanmır. Səhv sətir olsa, heç nə saxlanmır.</p>
        <p>
          <button type="button" className="font-medium text-navy-800 underline"
            onClick={() => download(`/api/${tab}/import-template`, `${customers ? "musteri" : "sirket"}-import-sablonu.xlsx`)}>Boş şablonu yüklə</button>
        </p>
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      <form id="party-import-form" onSubmit={submit}>
        <input type="file" accept=".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} className="text-sm" />
      </form>
      {result && !result.applied && (
        <div className="mt-4">
          <Alert>Faylda {result.errors.length} səhv var, heç nə saxlanmadı. Düzəldib yenidən yükləyin.</Alert>
          <ul className="mt-3 max-h-64 space-y-1 overflow-y-auto text-sm">
            {result.errors.map((er, i) => <li key={i}><span className="font-semibold text-rose-600">{er.row ? `Sətir ${er.row}` : "Fayl"}:</span> {er.message}</li>)}
          </ul>
        </div>
      )}
    </Modal>
  );
}
