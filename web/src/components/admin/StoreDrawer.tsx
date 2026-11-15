"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Alert, Badge, Button, EmptyState, Modal, Spinner, cx, useConfirm } from "@/components/ui";
import { Countdown, useNow } from "@/components/Countdown";
import { ApiError, api, openProtected } from "@/lib/api";
import { JOB_TITLE_LABEL, formatDate, formatDateTime, formatMoney } from "@/lib/format";
import { periodText } from "@/lib/plans";
import { waLink } from "@/lib/whatsapp";
import type { AdminStore, AuditEntry, JobTitle, SubscriptionRecord } from "@/lib/types";

type StoreCard = {
  id: string; name: string; phone: string | null; address: string | null; status: "ACTIVE" | "SUSPENDED"; createdAt: string;
  ownerId: string | null; ownerEmail: string | null; ownerName: string | null; ownerPhone: string | null;
  lastActivity: string | null; lastLogin: string | null;
  usage: { customers: number; companies: number; employees: number; products: number; openDebts: number; payments: number; sales: number; expenses: number; debtRemaining: number; salesTotal: number };
  employees: { fullName: string; email: string; active: boolean; jobTitle: JobTitle | null }[];
};

type Section = "info" | "subscription" | "activity" | "danger";

const STATUS: Record<SubscriptionRecord["status"], { label: string; tone: "emerald" | "slate" | "amber" | "rose" }> = {
  ACTIVE: { label: "Aktiv", tone: "emerald" },
  EXPIRED: { label: "Bitib", tone: "slate" },
  PENDING: { label: "Gözləyir", tone: "amber" },
  REJECTED: { label: "Rədd edilib", tone: "rose" },
};

function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: string }) {
  return (
    <div className="rounded-xl bg-slate-50 px-3 py-2.5 ring-1 ring-slate-200/70">
      <p className="text-[11px] font-medium uppercase tracking-wider text-slate-500">{label}</p>
      <p className="mt-0.5 font-serif text-xl font-semibold tabular-nums text-navy-800">{value}</p>
      {sub && <p className="text-[11px] text-slate-400">{sub}</p>}
    </div>
  );
}

/**
 * One store in the admin panel: contact and usage, its subscriptions (activate / renew, extend, stop),
 * activity log and the danger zone (suspend, wipe all data).
 */
export function StoreDrawer({ store, onClose, onActivate, onExtend, onChanged, actionLabels }: {
  store: AdminStore;
  onClose: () => void;
  onActivate: (s: AdminStore) => void;
  onExtend: (s: AdminStore) => void;
  onChanged: (notice: string) => void;
  actionLabels: Record<string, string>;
}) {
  const [section, setSection] = useState<Section>("info");
  const [card, setCard] = useState<StoreCard | null>(null);
  const [subs, setSubs] = useState<SubscriptionRecord[] | null>(null);
  const [log, setLog] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, confirmDialog] = useConfirm();
  const [resetName, setResetName] = useState("");
  const now = useNow(1000);

  const load = useCallback(() => {
    api<StoreCard>(`/api/admin/stores/${store.id}`).then(setCard).catch((e) => setError(e instanceof ApiError ? e.message : "Mağaza yüklənmədi"));
    api<SubscriptionRecord[]>(`/api/admin/subscriptions?storeId=${store.id}`).then(setSubs).catch(() => setSubs([]));
  }, [store.id]);
  useEffect(load, [load]);
  useEffect(() => {
    if (section === "activity" && !log) {
      api<AuditEntry[]>(`/api/admin/audit-logs?storeId=${store.id}&limit=300`).then(setLog).catch(() => setLog([]));
    }
  }, [section, log, store.id]);

  async function run(fn: () => Promise<unknown>, notice: string) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      onChanged(notice);
      load();
      setLog(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Əməliyyat alınmadı");
    } finally {
      setBusy(false);
    }
  }

  async function stopSubscription() {
    const ok = await confirm(<>“{store.name}” mağazasının aktiv abunəliyi <b>bu gün dayandırılsın</b>? Mağaza dərhal bağlanacaq, məlumatları qalacaq.</>,
      { title: "Abunəliyi dayandır", confirmLabel: "Bəli, dayandır", danger: true });
    if (ok) await run(() => api(`/api/admin/stores/${store.id}/cancel-subscription`, { method: "POST", json: {} }), "Abunəlik dayandırıldı");
  }

  async function toggleStore() {
    const suspend = (card?.status ?? store.status) === "ACTIVE";
    if (suspend && !(await confirm(`“${store.name}” dayandırılsın? Mağazanın bütün istifadəçiləri sistemə girə bilməyəcək.`, { title: "Mağazanı dayandır", confirmLabel: "Bəli, dayandır", danger: true }))) return;
    await run(() => api(`/api/admin/stores/${store.id}/${suspend ? "suspend" : "activate"}`, { method: "POST" }), suspend ? "Mağaza dayandırıldı" : "Mağaza bərpa edildi");
  }

  async function resetData() {
    const ok = await confirm(
      <>
        <b>“{store.name}”</b> mağazasının <b>bütün məlumatları</b> silinəcək: müştərilər, şirkətlər, borclar, ödənişlər, satışlar, məhsullar, anbar, kassa əməliyyatları, xərclər, cib pulu.
        <span className="mt-2 block font-semibold text-rose-700">Bu əməliyyat geri qaytarılmır.</span>
        <span className="mt-1 block text-xs text-slate-500">Hesab, işçilər və abunəlik qalır — mağaza boş başlayır.</span>
      </>,
      { title: "Məlumatları sıfırla", confirmLabel: "Hamısını sil", danger: true },
    );
    if (!ok) return;
    await run(async () => {
      const r = await api<{ rows: number }>(`/api/admin/stores/${store.id}/reset-data`, { method: "POST", json: { confirmName: resetName } });
      setResetName("");
      return r;
    }, `“${store.name}” mağazasının məlumatları sıfırlandı`);
  }

  const phone = card?.ownerPhone || card?.phone || store.ownerPhone || store.phone;
  const wa = waLink(phone, `Salam${card?.ownerName ? ", " + card.ownerName : ""}! HesabPro dəstək xidmətindən yazırıq.`);
  const status = card?.status ?? store.status;
  const SECTIONS: { key: Section; label: string }[] = [
    { key: "info", label: "Ümumi" },
    { key: "subscription", label: `Abunəlik${subs ? ` (${subs.length})` : ""}` },
    { key: "activity", label: "Fəaliyyət" },
    { key: "danger", label: "Təhlükəli zona" },
  ];

  return (
    <Modal open size="xl" title={store.name} onClose={onClose} footer={<Button variant="ghost" onClick={onClose}>Bağla</Button>}>
      {/* header */}
      <div className="-mx-6 -mt-5 mb-5 flex flex-wrap items-center gap-x-6 gap-y-3 border-b border-slate-100 bg-gradient-to-r from-navy-800 to-navy-600 px-6 py-4 text-white">
        <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-white/10 font-serif text-xl font-semibold text-gold-400">{store.name.slice(0, 1).toUpperCase()}</div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={status === "ACTIVE" ? "emerald" : "rose"}>{status === "ACTIVE" ? "Aktiv" : "Dayandırılıb"}</Badge>
            {store.activePlan ? <Badge tone="gold">{store.activePlan}</Badge> : <Badge tone="slate">Abunə yoxdur</Badge>}
            {store.pendingPlanName && <Badge tone="amber">Sorğu: {store.pendingPlanName}</Badge>}
          </div>
          <p className="mt-1 text-xs text-white/60">Qeydiyyat: {formatDate(store.createdAt)}{card?.lastLogin ? ` · son giriş ${formatDateTime(card.lastLogin)}` : ""}</p>
        </div>
        <div className="text-right text-sm">
          <Countdown expiresAt={store.subscriptionState === "NONE" ? null : store.expiresAt} now={now} emptyText="Abunə olmayıb" />
        </div>
        <div className="flex gap-2">
          <Button variant="gold" onClick={() => onActivate(store)}>{store.activePlan ? "Yenilə" : "Aktiv et"}</Button>
          {store.activePlan && <Button variant="outline" onClick={() => onExtend(store)}>Uzat</Button>}
        </div>
      </div>

      <div className="mb-5 flex gap-1 overflow-x-auto rounded-xl bg-slate-100 p-1">
        {SECTIONS.map((s) => (
          <button key={s.key} onClick={() => setSection(s.key)}
            className={cx("whitespace-nowrap rounded-lg px-4 py-1.5 text-sm font-medium transition", section === s.key ? (s.key === "danger" ? "bg-rose-600 text-white shadow" : "bg-white text-navy-800 shadow") : s.key === "danger" ? "text-rose-600 hover:bg-rose-50" : "text-slate-600 hover:text-navy-800")}>
            {s.label}
          </button>
        ))}
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      {section === "info" && (!card ? <Spinner /> : (
        <div className="grid gap-6 lg:grid-cols-[1fr_1.4fr]">
          <div className="space-y-4">
            <div className="rounded-2xl ring-1 ring-slate-200 p-4">
              <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Sahib</p>
              <p className="mt-1 font-medium text-navy-800">{card.ownerName ?? "—"}</p>
              <p className="text-sm text-slate-500">{card.ownerEmail}</p>
              {phone && <p className="text-sm text-slate-500">{phone}</p>}
              {card.address && <p className="mt-1 text-xs text-slate-400">{card.address}</p>}
              <div className="mt-3 flex flex-wrap gap-2">
                {wa && <a href={wa} target="_blank" rel="noreferrer" className="rounded-lg bg-emerald-50 px-3 py-1.5 text-xs font-medium text-emerald-700 hover:bg-emerald-100">WhatsApp</a>}
                {phone && <a href={`tel:${phone.replace(/[^+\d]/g, "")}`} className="rounded-lg bg-sky-50 px-3 py-1.5 text-xs font-medium text-sky-700 hover:bg-sky-100">Zəng et</a>}
                {card.ownerEmail && <a href={`mailto:${card.ownerEmail}`} className="rounded-lg bg-slate-100 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-200">E-poçt</a>}
              </div>
            </div>
            <div className="rounded-2xl ring-1 ring-slate-200 p-4">
              <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">İşçilər ({card.employees.length})</p>
              {card.employees.length === 0 ? <p className="text-sm text-slate-400">İşçi yoxdur</p> : (
                <ul className="space-y-1.5 text-sm">
                  {card.employees.map((e) => (
                    <li key={e.email} className="flex items-center justify-between gap-2">
                      <span className={e.active ? "text-navy-800" : "text-slate-400 line-through"}>{e.fullName}</span>
                      <span className="text-xs text-slate-400">{e.jobTitle ? JOB_TITLE_LABEL[e.jobTitle] : e.email}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
          <div>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">İstifadə</p>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              <Stat label="Müştəri" value={card.usage.customers} />
              <Stat label="Şirkət" value={card.usage.companies} />
              <Stat label="İşçi" value={card.usage.employees} />
              <Stat label="Məhsul" value={card.usage.products} />
              <Stat label="Açıq borc" value={card.usage.openDebts} sub={`qalıq ${formatMoney(card.usage.debtRemaining)}`} />
              <Stat label="Ödəniş" value={card.usage.payments} />
              <Stat label="Satış" value={card.usage.sales} sub={formatMoney(card.usage.salesTotal)} />
              <Stat label="Xərc" value={card.usage.expenses} />
              <Stat label="Son fəaliyyət" value={<span className="text-sm">{card.lastActivity ? formatDate(card.lastActivity) : "—"}</span>} />
            </div>
          </div>
        </div>
      ))}

      {section === "subscription" && (!subs ? <Spinner /> : (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            <Button variant="gold" onClick={() => onActivate(store)}>{store.activePlan ? "Yenilə / paket dəyiş" : "Abunəliyi aktiv et"}</Button>
            {store.activePlan && <Button variant="outline" onClick={() => onExtend(store)}>Günlə uzat</Button>}
            {store.activePlan && <Button variant="danger" disabled={busy} onClick={stopSubscription}>Abunəliyi dayandır</Button>}
          </div>
          {subs.length === 0 ? <EmptyState title="Abunəlik tarixçəsi yoxdur" /> : (
            <ol className="relative space-y-3 border-l-2 border-slate-200 pl-5">
              {subs.map((s) => (
                <li key={s.id} className="relative">
                  <span className={cx("absolute -left-[27px] top-3 h-3 w-3 rounded-full ring-4 ring-white", s.status === "ACTIVE" ? "bg-emerald-500" : s.status === "PENDING" ? "bg-amber-500" : s.status === "REJECTED" ? "bg-rose-500" : "bg-slate-400")} />
                  <div className="rounded-xl p-3 ring-1 ring-slate-200">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-navy-800">{s.planName}</span>
                        <Badge tone={STATUS[s.status].tone}>{STATUS[s.status].label}</Badge>
                        {(s.periodMonths || s.periodDays) && <span className="text-xs text-slate-500">{periodText({ months: s.periodMonths, days: s.periodDays })}</span>}
                      </div>
                      <span className="font-serif font-semibold tabular-nums text-navy-800">{formatMoney(s.amount)}</span>
                    </div>
                    <p className="mt-1 text-xs text-slate-500">
                      {s.startDate ? `${formatDate(s.startDate)} — ${formatDate(s.endDate)}` : `Sorğu: ${formatDateTime(s.requestedAt)}`}
                      {s.paymentMethod ? ` · ${s.paymentMethod === "M10" ? "M10" : "Kart"}` : ""}
                      {s.payerReference ? ` · ${s.payerReference}` : ""}
                    </p>
                    {s.rejectReason && <p className="mt-1 text-xs text-rose-600">Səbəb: {s.rejectReason}</p>}
                    {s.hasReceipt && <button className="mt-1 text-xs font-medium text-gold-600 hover:underline" onClick={() => openProtected(`/api/admin/subscriptions/${s.id}/receipt`)}>Qəbzə bax →</button>}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>
      ))}

      {section === "activity" && (!log ? <Spinner /> : log.length === 0 ? <EmptyState title="Qeyd yoxdur" /> : (
        <ol className="max-h-[55vh] space-y-2 overflow-y-auto pr-1">
          {log.map((a) => (
            <li key={a.id} className={cx("rounded-xl px-3 py-2 ring-1", a.action === "STORE_DATA_RESET" ? "bg-rose-50 ring-rose-200" : "ring-slate-100")}>
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium text-navy-800">{actionLabels[a.action] ?? a.action}</span>
                <span className="text-xs text-slate-400">{formatDateTime(a.createdAt)}</span>
              </div>
              <p className="text-xs text-slate-500">{a.actorName ?? "—"}{a.actorEmail ? ` · ${a.actorEmail}` : ""}</p>
              {a.details && <p className="mt-0.5 text-xs text-slate-600">{a.details}</p>}
            </li>
          ))}
        </ol>
      ))}

      {section === "danger" && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl p-4 ring-1 ring-slate-200">
            <div>
              <p className="font-medium text-navy-800">{status === "ACTIVE" ? "Mağazanı dayandır" : "Mağazanı bərpa et"}</p>
              <p className="text-sm text-slate-500">{status === "ACTIVE" ? "Bütün istifadəçilərin girişi bağlanır; məlumatlar qalır, istənilən vaxt bərpa olunur." : "Mağaza yenidən açılır, istifadəçilər daxil ola bilir."}</p>
            </div>
            <Button variant={status === "ACTIVE" ? "danger" : "outline"} disabled={busy} onClick={toggleStore}>{status === "ACTIVE" ? "Dayandır" : "Bərpa et"}</Button>
          </div>
          <div className="rounded-2xl border-2 border-rose-200 bg-rose-50/50 p-4">
            <p className="font-semibold text-rose-700">Məlumatları sıfırla</p>
            <p className="mt-1 text-sm text-slate-600">
              Müştərilər, şirkətlər, borclar, ödənişlər, satışlar, məhsullar, anbar, kassa əməliyyatları, xərclər və cib pulu <b>tamamilə silinir</b>.
              Hesab, işçilər, abunəlik və audit jurnalı qalır — mağaza təmiz başlayır. <b>Geri qaytarılmır.</b>
            </p>
            <label className="mt-3 block text-sm text-slate-700">
              Təsdiq üçün mağazanın adını yazın: <b className="select-all">{store.name}</b>
              <input value={resetName} onChange={(e) => setResetName(e.target.value)} placeholder={store.name}
                className="mt-1 w-full rounded-xl border border-rose-300 bg-white px-3 py-2 text-sm outline-none focus:border-rose-500" />
            </label>
            <Button variant="danger" className="mt-3" disabled={busy || resetName.trim().toLowerCase() !== store.name.trim().toLowerCase()} onClick={resetData}>
              {busy ? "Silinir…" : "Bütün məlumatları sil"}
            </Button>
          </div>
        </div>
      )}
      {confirmDialog}
    </Modal>
  );
}
