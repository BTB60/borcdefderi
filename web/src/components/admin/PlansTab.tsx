"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, Spinner, Textarea, useConfirm } from "@/components/ui";
import { PlanCard } from "@/components/PlanCard";
import { ApiError, api } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { HEX_RE, PALETTES, optionLabel, type PlanColors } from "@/lib/plans";
import type { PeriodCode, PeriodOption, Plan } from "@/lib/types";

type Props = { onError: (m: string | null) => void; onNotice?: (m: string | null) => void };

function errorText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback;
}

/** SUPER_ADMIN: subscription packages — create, edit, deactivate, order, archive / delete. */
export function PlansTab({ onError, onNotice }: Props) {
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const [editing, setEditing] = useState<Plan | "new" | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, confirmDialog] = useConfirm();

  const load = () =>
    api<Plan[]>(`/api/admin/plans${showArchived ? "?archived=true" : ""}`)
      .then(setPlans)
      .catch((e) => onError(errorText(e, "Paketlər yüklənmədi")));

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showArchived]);

  const current = plans?.filter((p) => !p.archived) ?? [];
  const archived = plans?.filter((p) => p.archived) ?? [];

  async function move(index: number, dir: -1 | 1) {
    const ids = current.map((p) => p.id);
    const j = index + dir;
    if (j < 0 || j >= ids.length) return;
    [ids[index], ids[j]] = [ids[j], ids[index]];
    setBusy(true);
    try {
      await api("/api/admin/plans/order", { method: "PUT", json: { ids } });
      await load();
    } catch (e) {
      onError(errorText(e, "Sıra saxlanılmadı"));
    } finally {
      setBusy(false);
    }
  }

  async function toggleActive(p: Plan) {
    setBusy(true);
    try {
      await api(`/api/admin/plans/${p.id}`, { method: "PUT", json: { ...toPayload(toForm(p)), active: !p.active } });
      onNotice?.(`“${p.name}” ${p.active ? "deaktiv edildi" : "aktiv edildi"}`);
      await load();
    } catch (e) {
      onError(errorText(e, "Status dəyişmədi"));
    } finally {
      setBusy(false);
    }
  }

  async function remove(p: Plan) {
    const ok = await confirm(
      p.inUse ? (
        <>“{p.name}” paketini mağazalar artıq istifadə edib. Paket <strong>arxivə</strong> köçürüləcək: yeni sorğular üçün görünməyəcək, keçmiş ödənişlər və tarixçə saxlanılacaq.</>
      ) : (
        <>“{p.name}” paketi heç istifadə olunmayıb və <strong>tam silinəcək</strong>.</>
      ),
      { title: p.inUse ? "Paketi arxivləşdir" : "Paketi sil", confirmLabel: p.inUse ? "Arxivə köçür" : "Sil", danger: true },
    );
    if (!ok) return;
    try {
      const r = await api<{ result: string }>(`/api/admin/plans/${p.id}`, { method: "DELETE" });
      onNotice?.(r.result === "ARCHIVED" ? `“${p.name}” arxivə köçürüldü` : `“${p.name}” silindi`);
      await load();
    } catch (e) {
      onError(errorText(e, "Paket silinmədi"));
    }
  }

  async function restore(p: Plan) {
    try {
      await api(`/api/admin/plans/${p.id}/restore`, { method: "POST" });
      onNotice?.(`“${p.name}” arxivdən çıxarıldı (deaktiv vəziyyətdə)`);
      await load();
    } catch (e) {
      onError(errorText(e, "Bərpa alınmadı"));
    }
  }

  if (!plans) return <Spinner />;
  return (
    <>
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-2xl text-sm text-slate-500">
          Mağazalar yalnız <strong>aktiv</strong> paketləri görür. Qiymət dəyişikliyi yalnız yeni sorğulara təsir edir — artıq ödənilmiş abunəliklər öz məbləğini və müddətini saxlayır.
        </p>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => setShowArchived((v) => !v)}>{showArchived ? "Arxivi gizlət" : "Arxivə bax"}</Button>
          <Button variant="gold" onClick={() => setEditing("new")}>+ Yeni paket</Button>
        </div>
      </div>

      {current.length === 0 ? (
        <Card><EmptyState title="Paket yoxdur" text="“Yeni paket” düyməsi ilə ilk paketi yaradın." /></Card>
      ) : (
        <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
          {current.map((p, i) => (
            <div key={p.id} className="flex flex-col gap-2">
              <div className={p.active ? "" : "opacity-60"}>
                <PlanCard plan={p} period={p.options[0]?.period ?? null} />
              </div>
              <Card className="!p-3">
                <div className="mb-2 flex flex-wrap items-center gap-1.5 text-xs">
                  <Badge tone={p.active ? "emerald" : "slate"}>{p.active ? "Aktiv" : "Deaktiv"}</Badge>
                  {p.recommended && <Badge tone="gold">Tövsiyə olunan</Badge>}
                  <span className="text-slate-400">#{i + 1}</span>
                  <span className="ml-auto text-slate-500">{p.options.map((o) => `${optionLabel(o)}: ${formatMoney(o.price)}`).join(" · ")}</span>
                </div>
                <div className="flex flex-wrap gap-1.5">
                  <Button variant="primary" onClick={() => setEditing(p)}>Redaktə et</Button>
                  <Button variant="outline" disabled={busy} onClick={() => toggleActive(p)}>{p.active ? "Deaktiv et" : "Aktiv et"}</Button>
                  <Button variant="ghost" disabled={busy || i === 0} onClick={() => move(i, -1)} aria-label="Yuxarı">↑</Button>
                  <Button variant="ghost" disabled={busy || i === current.length - 1} onClick={() => move(i, 1)} aria-label="Aşağı">↓</Button>
                  <Button variant="ghost" className="ml-auto !text-rose-600" onClick={() => remove(p)}>{p.inUse ? "Arxivə köçür" : "Sil"}</Button>
                </div>
              </Card>
            </div>
          ))}
        </div>
      )}

      {showArchived && (
        <Card className="mt-6">
          <h3 className="mb-3 font-serif text-lg font-semibold text-navy-800">Arxiv</h3>
          {archived.length === 0 ? <p className="text-sm text-slate-500">Arxivdə paket yoxdur.</p> : (
            <ul className="divide-y divide-slate-100">
              {archived.map((p) => (
                <li key={p.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                  <span className="text-slate-700">{p.name}</span>
                  <Button variant="outline" onClick={() => restore(p)}>Bərpa et</Button>
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}

      {confirmDialog}
      {editing && (
        <PlanEditor
          plan={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => {
            setEditing(null);
            onNotice?.(created ? `“${saved.name}” paketi yaradıldı` : `“${saved.name}” yadda saxlanıldı`);
            load();
          }}
        />
      )}
    </>
  );
}

interface PlanForm extends PlanColors {
  name: string;
  description: string;
  features: string;
  priceMonthly: string;
  priceQuarterly: string;
  priceSemiAnnual: string;
  priceYearly: string;
  customDays: string;
  customPrice: string;
  maxEmployees: string;
  maxCustomers: string;
  active: boolean;
  recommended: boolean;
}

const EMPTY: PlanForm = {
  name: "", description: "", features: "",
  priceMonthly: "", priceQuarterly: "", priceSemiAnnual: "", priceYearly: "", customDays: "", customPrice: "",
  maxEmployees: "", maxCustomers: "", active: true, recommended: false,
  ...PALETTES[0].colors,
};

function priceOf(p: Plan, period: PeriodCode): string {
  const o = p.options.find((x) => x.period === period);
  return o ? String(o.price) : "";
}

function toForm(p: Plan): PlanForm {
  return {
    name: p.name,
    description: p.description ?? "",
    features: p.features.join("\n"),
    priceMonthly: priceOf(p, "MONTHLY"),
    priceQuarterly: priceOf(p, "QUARTERLY"),
    priceSemiAnnual: priceOf(p, "SEMI_ANNUAL"),
    priceYearly: priceOf(p, "YEARLY"),
    customDays: String(p.options.find((o) => o.period === "CUSTOM")?.days ?? ""),
    customPrice: priceOf(p, "CUSTOM"),
    maxEmployees: p.maxEmployees == null ? "" : String(p.maxEmployees),
    maxCustomers: p.maxCustomers == null ? "" : String(p.maxCustomers),
    active: p.active,
    recommended: p.recommended,
    primaryColor: p.primaryColor,
    secondaryColor: p.secondaryColor,
    backgroundColor: p.backgroundColor,
    textColor: p.textColor,
    buttonColor: p.buttonColor,
  };
}

const num = (s: string) => (s.trim() === "" ? null : Number(s.replace(",", ".")));
const int = (s: string) => (s.trim() === "" ? null : parseInt(s, 10));

function toPayload(f: PlanForm) {
  return {
    name: f.name.trim(),
    description: f.description.trim() || null,
    features: f.features.split("\n").map((x) => x.trim()).filter(Boolean),
    priceMonthly: num(f.priceMonthly),
    priceQuarterly: num(f.priceQuarterly),
    priceSemiAnnual: num(f.priceSemiAnnual),
    priceYearly: num(f.priceYearly),
    customDays: int(f.customDays),
    customPrice: num(f.customPrice),
    maxEmployees: int(f.maxEmployees),
    maxCustomers: int(f.maxCustomers),
    active: f.active,
    recommended: f.recommended,
    primaryColor: f.primaryColor,
    secondaryColor: f.secondaryColor,
    backgroundColor: f.backgroundColor,
    textColor: f.textColor,
    buttonColor: f.buttonColor,
  };
}

/** Options as the store will see them, built from the form for the live preview. */
function previewOptions(f: PlanForm): PeriodOption[] {
  const out: PeriodOption[] = [];
  const add = (period: PeriodCode, months: number | null, days: number | null, price: string) => {
    const v = num(price);
    if (v != null && !Number.isNaN(v)) out.push({ period, months, days, price: v });
  };
  add("MONTHLY", 1, null, f.priceMonthly);
  add("QUARTERLY", 3, null, f.priceQuarterly);
  add("SEMI_ANNUAL", 6, null, f.priceSemiAnnual);
  add("YEARLY", 12, null, f.priceYearly);
  const d = int(f.customDays);
  if (d) add("CUSTOM", null, d, f.customPrice);
  return out;
}

function pickColors(c: PlanColors): PlanColors {
  return { primaryColor: c.primaryColor, secondaryColor: c.secondaryColor, backgroundColor: c.backgroundColor, textColor: c.textColor, buttonColor: c.buttonColor };
}

const COLOR_FIELDS: { key: keyof PlanColors; label: string }[] = [
  { key: "primaryColor", label: "Əsas rəng" },
  { key: "secondaryColor", label: "İkinci rəng" },
  { key: "backgroundColor", label: "Kartın fonu" },
  { key: "textColor", label: "Mətn rəngi" },
  { key: "buttonColor", label: "Düymənin rəngi" },
];

function PlanEditor({ plan, onClose, onSaved }: { plan: Plan | null; onClose: () => void; onSaved: (p: Plan, created: boolean) => void }) {
  const [f, setF] = useState<PlanForm>(plan ? toForm(plan) : EMPTY);
  const [period, setPeriod] = useState<PeriodCode | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const options = useMemo(() => previewOptions(f), [f]);
  const set = <K extends keyof PlanForm>(k: K, v: PlanForm[K]) => setF((x) => ({ ...x, [k]: v }));
  const badColor = COLOR_FIELDS.find((c) => !HEX_RE.test(f[c.key]));

  async function save(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (badColor) {
      setError(`${badColor.label} HEX formatında olmalıdır (məs. #1E40AF)`);
      return;
    }
    if (f.active && options.length === 0) {
      setError("Aktiv paket üçün ən azı bir müddətin qiymətini yazın");
      return;
    }
    setBusy(true);
    try {
      const saved = plan
        ? await api<Plan>(`/api/admin/plans/${plan.id}`, { method: "PUT", json: toPayload(f) })
        : await api<Plan>("/api/admin/plans", { method: "POST", json: toPayload(f) });
      onSaved(saved, !plan);
    } catch (err) {
      setError(errorText(err, "Paket yadda saxlanılmadı"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      size="xl"
      title={plan ? `Paketi redaktə et — ${plan.name}` : "Yeni paket yarat"}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="plan-form" variant="gold" disabled={busy}>{busy ? "Saxlanılır…" : "Yadda saxla"}</Button></>}
    >
      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        <form id="plan-form" onSubmit={save} className="space-y-5">
          {error && <Alert>{error}</Alert>}
          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-navy-800">Əsas məlumat</h3>
            <Field label="Paket adı"><Input required maxLength={100} value={f.name} onChange={(e) => set("name", e.target.value)} placeholder="Məs: Premium" /></Field>
            <Field label="Təsvir"><Input maxLength={500} value={f.description} onChange={(e) => set("description", e.target.value)} placeholder="Qısa izah" /></Field>
            <Field label="Üstünlüklər" hint="Hər sətirdə bir üstünlük (ən çox 20)">
              <Textarea value={f.features} onChange={(e) => set("features", e.target.value)} placeholder={"Limitsiz borc qeydi\nPDF və Excel hesabatlar\nWhatsApp xatırlatmaları"} />
            </Field>
          </section>

          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-navy-800">Qiymətlər və müddət <span className="font-normal text-slate-500">— boş qalan müddət satılmır</span></h3>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Aylıq qiymət (30 gün / 1 ay) ₼"><Input type="number" min="0" step="0.01" value={f.priceMonthly} onChange={(e) => set("priceMonthly", e.target.value)} /></Field>
              <Field label="3 aylıq qiymət (90 gün) ₼"><Input type="number" min="0" step="0.01" value={f.priceQuarterly} onChange={(e) => set("priceQuarterly", e.target.value)} /></Field>
              <Field label="6 aylıq qiymət (180 gün) ₼"><Input type="number" min="0" step="0.01" value={f.priceSemiAnnual} onChange={(e) => set("priceSemiAnnual", e.target.value)} /></Field>
              <Field label="İllik qiymət (365 gün) ₼"><Input type="number" min="0" step="0.01" value={f.priceYearly} onChange={(e) => set("priceYearly", e.target.value)} /></Field>
              <Field label="Xüsusi müddət (gün)" hint="İstəyə bağlı, 1–3660"><Input type="number" min="1" max="3660" value={f.customDays} onChange={(e) => set("customDays", e.target.value)} /></Field>
              <Field label="Xüsusi müddətin qiyməti ₼"><Input type="number" min="0" step="0.01" value={f.customPrice} onChange={(e) => set("customPrice", e.target.value)} /></Field>
            </div>
            <p className="text-xs text-slate-500">Standart müddətlər təqvim ayı ilə hesablanır (məs. 1 ay = 9 noyabr – 8 dekabr).</p>
          </section>

          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-navy-800">Limitlər</h3>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="İşçi sayı" hint="Boş = limitsiz"><Input type="number" min="0" value={f.maxEmployees} onChange={(e) => set("maxEmployees", e.target.value)} /></Field>
              <Field label="Müştəri sayı" hint="Boş = limitsiz"><Input type="number" min="0" value={f.maxCustomers} onChange={(e) => set("maxCustomers", e.target.value)} /></Field>
            </div>
            <div className="flex flex-wrap gap-5 text-sm text-slate-700">
              <label className="flex items-center gap-2"><input type="checkbox" className="h-4 w-4 accent-navy-800" checked={f.active} onChange={(e) => set("active", e.target.checked)} /> Aktiv (satışda)</label>
              <label className="flex items-center gap-2"><input type="checkbox" className="h-4 w-4 accent-navy-800" checked={f.recommended} onChange={(e) => set("recommended", e.target.checked)} /> Tövsiyə olunan / populyar</label>
            </div>
          </section>

          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-navy-800">Rəng və dizayn <span className="font-normal text-slate-500">— yalnız görünüşə təsir edir</span></h3>
            <div className="flex flex-wrap gap-2">
              {PALETTES.map((p) => (
                <button
                  key={p.name}
                  type="button"
                  onClick={() => setF((x) => ({ ...x, ...p.colors }))}
                  className="flex items-center gap-2 rounded-full border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 transition hover:border-slate-400"
                >
                  <span className="h-4 w-4 rounded-full ring-1 ring-black/10" style={{ background: `linear-gradient(135deg, ${p.colors.primaryColor} 50%, ${p.colors.secondaryColor} 50%)` }} />
                  {p.name}
                </button>
              ))}
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              {COLOR_FIELDS.map((c) => (
                <Field key={c.key} label={c.label} error={HEX_RE.test(f[c.key]) ? undefined : "HEX formatı: #RRGGBB"}>
                  <div className="flex items-center gap-2">
                    <input
                      type="color"
                      aria-label={`${c.label} — rəng seçici`}
                      value={HEX_RE.test(f[c.key]) ? f[c.key] : "#000000"}
                      onChange={(e) => set(c.key, e.target.value.toUpperCase())}
                      className="h-10 w-12 cursor-pointer rounded-lg border border-slate-300 bg-white p-1"
                    />
                    <Input value={f[c.key]} maxLength={7} onChange={(e) => set(c.key, e.target.value.trim().toUpperCase())} className="font-mono uppercase" />
                  </div>
                </Field>
              ))}
            </div>
          </section>
        </form>

        <aside className="lg:sticky lg:top-0 lg:self-start">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Canlı önizləmə</p>
          <PlanCard
            plan={{
              name: f.name,
              description: f.description.trim() || null,
              features: f.features.split("\n").map((x) => x.trim()).filter(Boolean),
              options,
              recommended: f.recommended,
              maxEmployees: int(f.maxEmployees),
              maxCustomers: int(f.maxCustomers),
              ...pickColors(badColor ? (plan ? toForm(plan) : EMPTY) : f),
            } as Plan}
            period={period}
            onPeriod={setPeriod}
            selected={false}
            action={{ label: "Paketi seç" }}
          />
          <p className="mt-2 text-xs text-slate-500">Mağaza istifadəçisi kartı belə görəcək.</p>
        </aside>
      </div>
    </Modal>
  );
}
