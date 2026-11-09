"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, Modal, PageHeader, Spinner, StatCard, Table, Td, Th, useConfirm } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { Countdown, WARN_DAYS, countdown, useNow } from "@/components/Countdown";
import { TemporaryPasswordModal, type TemporaryPassword } from "@/components/PasswordReset";
import { PlansTab } from "@/components/admin/PlansTab";
import { PaymentSettingsTab } from "@/components/admin/PaymentSettingsTab";
import { PaymentRequestsTab } from "@/components/admin/PaymentRequestsTab";
import { optionLabel } from "@/lib/plans";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import type { AdminRevenue, AdminStore, AuditEntry, Plan } from "@/lib/types";

type Tab = "stores" | "subscriptions" | "plans" | "revenue" | "users" | "audit" | "settings";


const TABS: { key: Tab; label: string }[] = [
  { key: "stores", label: "Mağazalar" },
  { key: "subscriptions", label: "Ödəniş sorğuları" },
  { key: "plans", label: "Abunəlik paketləri" },
  { key: "revenue", label: "Gəlir" },
  { key: "users", label: "İstifadəçilər" },
  { key: "audit", label: "Audit jurnalı" },
  { key: "settings", label: "Ödəniş və əlaqə" },
];

export default function AdminPage() {
  const [tab, setTab] = useState<Tab>("stores");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <>
      <PageHeader title="Platforma idarəetməsi" subtitle="Mağazalar, abunəliklər, ödəniş təsdiqləri və gəlir" />
      <div className="mb-6 flex gap-1 overflow-x-auto rounded-2xl bg-white p-1.5 shadow-card ring-1 ring-slate-200/70" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.key}
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
            className={`whitespace-nowrap rounded-xl px-4 py-2 text-sm font-medium transition-all ${tab === t.key ? "bg-navy-800 text-white shadow" : "text-slate-600 hover:bg-slate-100 hover:text-navy-800"}`}
          >
            {t.label}
          </button>
        ))}
      </div>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {notice && <div className="mb-4"><Alert kind="success">{notice}</Alert></div>}

      {tab === "stores" && <StoresTab onError={setError} onNotice={setNotice} />}
      {tab === "subscriptions" && <PaymentRequestsTab onError={setError} onNotice={setNotice} />}
      {tab === "plans" && <PlansTab onError={setError} onNotice={setNotice} />}
      {tab === "revenue" && <RevenueTab onError={setError} />}
      {tab === "users" && <UsersTab onError={setError} />}
      {tab === "audit" && <AuditTab onError={setError} />}
      {tab === "settings" && <PaymentSettingsTab onError={setError} onNotice={setNotice} />}
    </>
  );
}

type TabProps = { onError: (m: string | null) => void; onNotice?: (m: string | null) => void };

function errorText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback;
}

type StoreFilter = "attention" | "new" | "all" | "active" | "expired" | "none";

/** Human-readable names for audit actions (store activity history). */
const ACTION_LABEL: Record<string, string> = {
  STORE_REGISTERED: "Mağaza qeydiyyatı",
  LOGIN: "Sistemə giriş",
  CUSTOMER_CREATED: "Müştəri əlavə edildi",
  CUSTOMER_UPDATED: "Müştəri dəyişdirildi",
  COMPANY_CREATED: "Şirkət əlavə edildi",
  COMPANY_UPDATED: "Şirkət dəyişdirildi",
  DEBT_CREATED: "Borc əlavə edildi",
  DEBT_UPDATED: "Borc dəyişdirildi",
  PAYMENT_RECORDED: "Ödəniş qəbul edildi",
  EMPLOYEE_CREATED: "İşçi əlavə edildi",
  EMPLOYEE_UPDATED: "İşçi dəyişdirildi",
  SUBSCRIPTION_REQUESTED: "Abunəlik sorğusu",
  SUBSCRIPTION_SUBMITTED: "Abunəlik sorğusu (qəbz)",
  SUBSCRIPTION_APPROVED: "Abunəlik təsdiqləndi",
  SUBSCRIPTION_ACTIVATED: "Abunəlik aktiv edildi",
  SUBSCRIPTION_REJECTED: "Abunəlik rədd edildi",
  SUBSCRIPTION_EXTENDED: "Abunəlik uzadıldı",
  STORE_SUSPENDED: "Mağaza dayandırıldı",
  STORE_ACTIVATED: "Mağaza bərpa edildi",
  PLAN_UPDATED: "Paket dəyişdirildi",
  CONTACT_UPDATED: "Əlaqə məlumatı dəyişdirildi",
  PAYMENT_SETTINGS_UPDATED: "Ödəniş ayarları dəyişdirildi",
};

const NEW_DAYS = 7;


/** Lower = needs attention sooner: pending requests, expired, running out, then by time left. */
function urgency(s: AdminStore, now: number): number {
  const left = s.expiresAt ? new Date(s.expiresAt).getTime() - now : Number.POSITIVE_INFINITY;
  if (s.pendingPlanCode) return -2e15 + (Number.isFinite(left) ? left : 0);
  if (s.subscriptionState === "EXPIRED") return -1e15 + left;
  return left;
}

function StoresTab({ onError, onNotice }: TabProps) {
  const [q, setQ] = useState("");
  const [applied, setApplied] = useState("");
  const [items, setItems] = useState<AdminStore[] | null>(null);
  const [extending, setExtending] = useState<AdminStore | null>(null);
  const [activating, setActivating] = useState<AdminStore | null>(null);
  const [historyOf, setHistoryOf] = useState<AdminStore | null>(null);
  const [confirm, confirmDialog] = useConfirm();
  const [filter, setFilter] = useState<StoreFilter>("attention");
  const [reload, setReload] = useState(0);
  const now = useNow(1000);

  useEffect(() => {
    setItems(null);
    api<AdminStore[]>(`/api/admin/stores${applied ? `?q=${encodeURIComponent(applied)}` : ""}`)
      .then(setItems)
      .catch((e) => onError(errorText(e, "Mağazalar yüklənmədi")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applied, reload]);

  async function toggle(s: AdminStore) {
    const action = s.status === "ACTIVE" ? "suspend" : "activate";
    if (action === "suspend" && !(await confirm(`“${s.name}” mağazasını dayandırmaq istədiyinizə əminsiniz? Mağazanın bütün istifadəçiləri sistemə giriş edə bilməyəcək.`, { title: "Mağazanı dayandır", confirmLabel: "Bəli, dayandır", danger: true }))) return;
    try {
      await api(`/api/admin/stores/${s.id}/${action}`, { method: "POST" });
      onNotice?.(action === "suspend" ? "Mağaza dayandırıldı" : "Mağaza aktivləşdirildi");
      setReload((r) => r + 1);
    } catch (e) {
      onError(errorText(e, "Əməliyyat alınmadı"));
    }
  }

  const all = items ?? [];
  const isRed = (s: AdminStore) => {
    const c = countdown(s.expiresAt, now);
    return s.subscriptionState !== "NONE" && (c?.warning ?? false);
  };
  const counts = {
    active: all.filter((s) => s.subscriptionState === "ACTIVE" && !isRed(s)).length,
    expiring: all.filter((s) => s.subscriptionState === "ACTIVE" && isRed(s)).length,
    expired: all.filter((s) => s.subscriptionState === "EXPIRED" || (s.subscriptionState === "ACTIVE" && countdown(s.expiresAt, now)?.expired)).length,
    pending: all.filter((s) => s.pendingPlanCode).length,
  };
  const visible = all
    .filter((s) => {
      switch (filter) {
        case "attention": return !!s.pendingPlanCode || isRed(s);
        case "active": return s.subscriptionState === "ACTIVE";
        case "expired": return s.subscriptionState === "EXPIRED";
        case "none": return s.subscriptionState === "NONE";
        case "new": return now - new Date(s.createdAt).getTime() < NEW_DAYS * 86_400_000;
        default: return true;
      }
    })
    .sort((a, b) => urgency(a, now) - urgency(b, now));

  const FILTERS: { key: StoreFilter; label: string }[] = [
    { key: "attention", label: "Diqqət tələb edənlər" },
    { key: "active", label: "Aktiv" },
    { key: "expired", label: "Vaxtı bitib" },
    { key: "new", label: `Yeni qeydiyyatlar (${NEW_DAYS} gün)` },
    { key: "none", label: "Gözləmədə (abunəsiz)" },
    { key: "all", label: "Hamısı" },
  ];

  return (
    <>
      <section className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Aktiv abunəlik" value={String(counts.active)} tone="emerald" />
        <StatCard label={`Az qalıb (< ${WARN_DAYS} gün)`} value={String(counts.expiring)} tone="rose" />
        <StatCard label="Vaxtı bitib" value={String(counts.expired)} tone="rose" />
        <StatCard label="Gözləyən sorğu" value={String(counts.pending)} tone="gold" hint="“Abunə ol” basıb çek göndərməli olanlar" />
      </section>
      <Card>
        <div className="mb-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filtr">
            {FILTERS.map((f) => (
              <button
                key={f.key}
                onClick={() => setFilter(f.key)}
                aria-pressed={filter === f.key}
                className={`rounded-full px-3 py-1.5 text-xs font-medium transition ${filter === f.key ? "bg-navy-800 text-white" : "bg-slate-100 text-slate-600 hover:bg-slate-200"}`}
              >
                {f.label}
              </button>
            ))}
          </div>
          <form onSubmit={(e: FormEvent) => { e.preventDefault(); setApplied(q.trim()); }} className="flex gap-2">
            <Input placeholder="Mağaza adı və ya sahibin e-poçtu" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Axtarış" />
            <Button type="submit" variant="outline">Axtar</Button>
          </form>
        </div>
        {!items ? <Spinner /> : visible.length === 0 ? <EmptyState title={filter === "attention" ? "Diqqət tələb edən mağaza yoxdur" : "Mağaza tapılmadı"} /> : (
          <Table>
            <thead className="bg-slate-50/70">
              <tr><Th>Mağaza</Th><Th>Sahib</Th><Th>Paket</Th><Th>Sayğac</Th><Th>Sorğu</Th><Th>Status</Th><Th className="text-right">Əməliyyat</Th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {visible.map((s) => {
                const red = isRed(s);
                return (
                  <tr key={s.id} className={red ? "bg-rose-50 transition-colors hover:bg-rose-100/70" : "transition-colors hover:bg-gold-500/5"}>
                    <Td>
                      <div className={`font-medium ${red ? "text-rose-700" : "text-navy-800"}`}>{s.name}</div>
                      <div className="text-xs text-slate-400">{formatDate(s.createdAt)}</div>
                    </Td>
                    <Td>
                      <div>{s.ownerName ?? "—"}</div>
                      <div className="text-xs text-slate-400">{s.ownerEmail}</div>
                      {(s.ownerPhone || s.phone) && <a href={`tel:${(s.ownerPhone || s.phone || "").replace(/[^+\d]/g, "")}`} className="text-xs text-gold-600 hover:underline">{s.ownerPhone || s.phone}</a>}
                    </Td>
                    <Td>
                      {s.activePlan ?? <span className="text-slate-400">Yoxdur</span>}
                      {s.subscriptionEnd && <div className="text-xs text-slate-400">Bitmə: {formatDate(s.subscriptionEnd)}</div>}
                    </Td>
                    <Td><Countdown expiresAt={s.subscriptionState === "NONE" ? null : s.expiresAt} now={now} emptyText="Abunə olmayıb" /></Td>
                    <Td>
                      {s.pendingPlanCode ? (
                        <div><Badge tone="amber">{s.pendingPlanName}</Badge><div className="mt-1 text-xs text-slate-400">{formatDateTime(s.pendingAt)}</div></div>
                      ) : <span className="text-slate-300">—</span>}
                    </Td>
                    <Td><Badge tone={s.status === "ACTIVE" ? "emerald" : "rose"}>{s.status === "ACTIVE" ? "Aktiv" : "Dayandırılıb"}</Badge></Td>
                    <Td className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="gold" onClick={() => setActivating(s)}>{s.activePlan ? "Yenilə" : "Aktiv et"}</Button>
                        <Button variant="ghost" onClick={() => setHistoryOf(s)}>Tarixçə</Button>
                        <Button variant="ghost" onClick={() => setExtending(s)} disabled={!s.activePlan}>Uzat</Button>
                        <Button variant={s.status === "ACTIVE" ? "danger" : "outline"} onClick={() => toggle(s)}>
                          {s.status === "ACTIVE" ? "Dayandır" : "Bərpa et"}
                        </Button>
                      </div>
                    </Td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card>
      {confirmDialog}
      {historyOf && <StoreHistoryModal store={historyOf} onClose={() => setHistoryOf(null)} />}
      {activating && (
        <ActivateModal
          store={activating}
          onClose={() => setActivating(null)}
          onDone={(planName) => {
            setActivating(null);
            onNotice?.(`“${activating.name}” üçün ${planName} abunəliyi aktiv edildi`);
            setReload((r) => r + 1);
          }}
          onError={(m) => onError(m)}
        />
      )}
      {extending && (
        <ExtendModal
          store={extending}
          onClose={() => setExtending(null)}
          onDone={() => {
            setExtending(null);
            onNotice?.("Abunəlik uzadıldı");
            setReload((r) => r + 1);
          }}
          onError={(m) => onError(m)}
        />
      )}
    </>
  );
}

function ActivateModal({ store, onClose, onDone, onError }: { store: AdminStore; onClose: () => void; onDone: (planName: string) => void; onError: (m: string) => void }) {
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [code, setCode] = useState(store.pendingPlanCode ?? "");
  const [period, setPeriod] = useState<string>("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Plan[]>("/api/public/plans")
      .then((p) => {
        setPlans(p);
        setCode((c) => c || p[0]?.code || "");
      })
      .catch((e) => onError(errorText(e, "Paketlər yüklənmədi")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const plan = plans?.find((p) => p.code === code);
  const option = plan?.options.find((o) => o.period === period) ?? plan?.options[0];

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!plan) return;
    setBusy(true);
    try {
      await api(`/api/admin/stores/${store.id}/subscription`, { method: "POST", json: { planCode: plan.code, period: option?.period } });
      onDone(plan.name);
    } catch (err) {
      onError(errorText(err, "Aktivləşdirmə alınmadı"));
      onClose();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Abunəliyi aktiv et — ${store.name}`}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" variant="gold" form="activate-form" disabled={busy || !plan}>{busy ? "Aktiv edilir…" : "Ödəniş yoxlanıldı — aktiv et"}</Button></>}
    >
      {!plans ? <Spinner /> : (
        <form id="activate-form" onSubmit={submit} className="space-y-4">
          {store.pendingPlanName && <Alert kind="info">Mağaza “{store.pendingPlanName}” paketini seçib.</Alert>}
          <div className="grid gap-2 sm:grid-cols-3" role="radiogroup" aria-label="Paket">
            {plans.map((p) => (
              <button
                type="button"
                key={p.id}
                role="radio"
                aria-checked={code === p.code}
                onClick={() => { setCode(p.code); setPeriod(""); }}
                className={`rounded-xl border px-3 py-3 text-left transition ${code === p.code ? "border-gold-500 bg-gold-500/10 shadow-glow" : "border-slate-300 hover:border-slate-400"}`}
              >
                <p className="text-sm font-semibold text-navy-800">{p.name}</p>
                <p className="text-xs text-slate-500">{p.options.length > 1 ? `${p.options.length} müddət` : p.options[0] ? `${optionLabel(p.options[0])} · ${formatMoney(p.options[0].price)}` : "—"}</p>
              </button>
            ))}
          </div>
          {plan && plan.options.length > 1 && (
            <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Müddət">
              {plan.options.map((o) => (
                <button
                  type="button"
                  key={o.period}
                  role="radio"
                  aria-checked={option?.period === o.period}
                  onClick={() => setPeriod(o.period)}
                  className={`rounded-full border px-3 py-1.5 text-xs font-medium transition ${option?.period === o.period ? "border-navy-800 bg-navy-800 text-white" : "border-slate-300 text-slate-700 hover:border-slate-400"}`}
                >
                  {optionLabel(o)} · {formatMoney(o.price)}
                </button>
              ))}
            </div>
          )}
          <p className="text-xs text-slate-500">
            {store.activePlan
              ? `Mağazanın aktiv abunəliyi var — yeni dövr cari bitmə tarixindən (${formatDate(store.subscriptionEnd)}) sonra başlayacaq.`
              : "Abunəlik bu gündən başlayacaq və sayğac dərhal işə düşəcək."}
          </p>
        </form>
      )}
    </Modal>
  );
}

function StoreHistoryModal({ store, onClose }: { store: AdminStore; onClose: () => void }) {
  const [items, setItems] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api<AuditEntry[]>(`/api/admin/audit-logs?storeId=${store.id}&limit=200`)
      .then(setItems)
      .catch((e) => setError(errorText(e, "Tarixçə yüklənmədi")));
  }, [store.id]);
  return (
    <Modal open title={`Fəaliyyət tarixçəsi — ${store.name}`} onClose={onClose} footer={<Button variant="ghost" onClick={onClose}>Bağla</Button>}>
      {error && <Alert>{error}</Alert>}
      {!items && !error && <Spinner />}
      {items && items.length === 0 && <EmptyState title="Qeyd yoxdur" />}
      {items && items.length > 0 && (
        <ol className="max-h-[60vh] space-y-3 overflow-y-auto pr-1">
          {items.map((a) => (
            <li key={a.id} className="rounded-xl border border-slate-100 px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium text-navy-800">{ACTION_LABEL[a.action] ?? a.action}</span>
                <span className="text-xs text-slate-400">{formatDateTime(a.createdAt)}</span>
              </div>
              <p className="text-xs text-slate-500">{a.actorName ?? "—"}{a.actorEmail ? ` · ${a.actorEmail}` : ""}</p>
              {a.details && <p className="mt-0.5 text-xs text-slate-600">{a.details}</p>}
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}

function ExtendModal({ store, onClose, onDone, onError }: { store: AdminStore; onClose: () => void; onDone: () => void; onError: (m: string) => void }) {
  const [days, setDays] = useState("30");
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api(`/api/admin/stores/${store.id}/extend`, { method: "POST", json: { days: Number(days) } });
      onDone();
    } catch (err) {
      onError(errorText(err, "Uzatma alınmadı"));
      onClose();
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal open title={`Abunəliyi uzat — ${store.name}`} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Ləğv et</Button><Button type="submit" form="extend-form" disabled={busy}>Uzat</Button></>}>
      <form id="extend-form" onSubmit={submit}>
        <Field label="Gün sayı" hint="1–366 gün arası">
          <Input type="number" min={1} max={366} required value={days} onChange={(e) => setDays(e.target.value)} />
        </Field>
      </form>
    </Modal>
  );
}

function RevenueTab({ onError }: { onError: (m: string | null) => void }) {
  const [data, setData] = useState<AdminRevenue | null>(null);
  useEffect(() => {
    api<AdminRevenue>("/api/admin/revenue").then(setData).catch((e) => onError(errorText(e, "Gəlir məlumatı yüklənmədi")));
  }, [onError]);
  if (!data) return <Spinner />;
  return (
    <>
      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Ümumi gəlir" value={formatMoney(data.totalRevenue)} tone="gold" />
        <StatCard label="Bu ay" value={formatMoney(data.monthRevenue)} tone="emerald" />
        <StatCard label="Bu il" value={formatMoney(data.yearRevenue)} tone="navy" />
        <StatCard label="Aktiv mağaza" value={`${data.activeStores} / ${data.totalStores}`} tone="sky" hint={`Gözləyən sorğu: ${data.pendingRequests}`} />
      </section>
      <Card className="mt-6">
        <h2 className="mb-4 font-serif text-lg font-semibold text-navy-800">Plan üzrə</h2>
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Plan</Th><Th className="text-right">Təsdiqlənmiş abunə</Th><Th className="text-right">Gəlir</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {data.perPlan.map((p) => (
              <tr key={p.code} className="hover:bg-gold-500/5">
                <Td className="font-medium text-navy-800">{p.name}</Td>
                <Td className="text-right tabular-nums">{p.approvedCount}</Td>
                <Td className="text-right tabular-nums">{formatMoney(p.revenue)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      </Card>
    </>
  );
}

type AdminUser = { id: string; email: string; fullName: string; phone: string | null; platformRole: string; storeRole: string | null; storeName: string | null; enabled: boolean };
type ResetRequest = { id: string; userId: string; email: string; fullName: string; phone: string | null; storeName: string | null; role: string | null; createdAt: string };

function UsersTab({ onError }: { onError: (m: string | null) => void }) {
  const [q, setQ] = useState("");
  const [applied, setApplied] = useState("");
  const [items, setItems] = useState<AdminUser[] | null>(null);
  const [requests, setRequests] = useState<ResetRequest[]>([]);
  const [temp, setTemp] = useState<TemporaryPassword | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [confirm, confirmDialog] = useConfirm();

  useEffect(() => {
    setItems(null);
    api<AdminUser[]>(`/api/admin/users${applied ? `?q=${encodeURIComponent(applied)}` : ""}`).then(setItems).catch((e) => onError(errorText(e, "İstifadəçilər yüklənmədi")));
    api<ResetRequest[]>("/api/admin/password-requests").then(setRequests).catch(() => setRequests([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applied, reload]);

  async function reset(userId: string, name: string) {
    const ok = await confirm(
      <>
        <strong>{name}</strong> üçün yeni müvəqqəti şifrə yaradılsın?
        <span className="mt-2 block text-xs text-slate-500">Köhnə şifrə və bütün açıq girişlər dərhal etibarsız olacaq. İstifadəçi daxil olanda yeni şifrə təyin etməlidir.</span>
      </>,
      { title: "Şifrəni sıfırla", confirmLabel: "Bəli, sıfırla" },
    );
    if (!ok) return;
    setBusyId(userId);
    onError(null);
    try {
      setTemp(await api<TemporaryPassword>(`/api/admin/users/${userId}/reset-password`, { method: "POST" }));
      setReload((r) => r + 1);
    } catch (e) {
      onError(errorText(e, "Şifrə sıfırlanmadı"));
    } finally {
      setBusyId(null);
    }
  }

  async function dismiss(id: string) {
    try {
      await api(`/api/admin/password-requests/${id}/dismiss`, { method: "POST" });
      setReload((r) => r + 1);
    } catch (e) {
      onError(errorText(e, "Sorğu bağlanmadı"));
    }
  }

  return (
    <>
      {requests.length > 0 && (
        <Card className="mb-6 border-amber-300">
          <h2 className="mb-1 font-serif text-lg font-semibold text-navy-800">Şifrə bərpası sorğuları ({requests.length})</h2>
          <p className="mb-4 text-sm text-slate-500">“Şifrəni unutdum” basan istifadəçilər. Kimliyi telefonla təsdiqləyin, sonra müvəqqəti şifrə verin.</p>
          <ul className="divide-y divide-slate-100">
            {requests.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div>
                  <p className="font-medium text-navy-800">{r.fullName} <span className="text-xs font-normal text-slate-400">· {r.storeName ?? "—"}</span></p>
                  <p className="text-xs text-slate-500">{r.email}{r.phone ? ` · ${r.phone}` : ""} · {formatDateTime(r.createdAt)}</p>
                </div>
                <div className="flex gap-1">
                  <Button variant="ghost" onClick={() => dismiss(r.id)}>Bağla</Button>
                  <Button variant="gold" disabled={busyId === r.userId} onClick={() => reset(r.userId, r.fullName)}>Müvəqqəti şifrə ver</Button>
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}
      <Card>
        <form onSubmit={(e: FormEvent) => { e.preventDefault(); setApplied(q.trim()); }} className="mb-5 flex gap-2">
          <Input placeholder="Ad və ya e-poçt" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Axtarış" />
          <Button type="submit" variant="outline">Axtar</Button>
        </form>
        {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="İstifadəçi tapılmadı" /> : (
          <Table>
            <thead className="bg-slate-50/70"><tr><Th>Ad</Th><Th>E-poçt</Th><Th>Rol</Th><Th>Mağaza</Th><Th>Status</Th><Th className="text-right">Şifrə</Th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((u) => (
                <tr key={u.id} className="hover:bg-gold-500/5">
                  <Td className="font-medium text-navy-800">{u.fullName}{u.phone && <div className="text-xs font-normal text-slate-400">{u.phone}</div>}</Td>
                  <Td>{u.email}</Td>
                  <Td>{u.platformRole === "SUPER_ADMIN" ? <Badge tone="gold">Administrator</Badge> : <Badge tone="navy">{u.storeRole === "EMPLOYEE" ? "İşçi" : u.storeRole === "STORE_OWNER" ? "Sahib" : "—"}</Badge>}</Td>
                  <Td className="text-slate-500">{u.storeName ?? "—"}</Td>
                  <Td><Badge tone={u.enabled ? "emerald" : "rose"}>{u.enabled ? "Aktiv" : "Deaktiv"}</Badge></Td>
                  <Td className="text-right">
                    {u.platformRole !== "SUPER_ADMIN" && (
                      <Button variant="ghost" disabled={busyId === u.id} onClick={() => reset(u.id, u.fullName)}>Şifrəni sıfırla</Button>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      {confirmDialog}
      {temp && <TemporaryPasswordModal result={temp} onClose={() => setTemp(null)} />}
    </>
  );
}

function AuditTab({ onError }: { onError: (m: string | null) => void }) {
  const [items, setItems] = useState<AuditEntry[] | null>(null);
  useEffect(() => {
    api<AuditEntry[]>("/api/admin/audit-logs?limit=300").then(setItems).catch((e) => onError(errorText(e, "Audit jurnalı yüklənmədi")));
  }, [onError]);
  return (
    <Card>
      {!items ? <Spinner /> : items.length === 0 ? <EmptyState title="Qeyd yoxdur" /> : (
        <Table>
          <thead className="bg-slate-50/70"><tr><Th>Vaxt</Th><Th>Mağaza</Th><Th>Kim</Th><Th>Əməliyyat</Th><Th>Təfərrüat</Th><Th>IP</Th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((a) => (
              <tr key={a.id} className="hover:bg-gold-500/5">
                <Td className="text-slate-500">{formatDateTime(a.createdAt)}</Td>
                <Td className="text-slate-500">{a.storeName ?? "Platforma"}</Td>
                <Td className="text-slate-500">{a.actorName ?? "—"}<div className="text-xs text-slate-400">{a.actorEmail}</div></Td>
                <Td><span className="text-xs font-medium text-navy-800">{ACTION_LABEL[a.action] ?? a.action}</span></Td>
                <Td className="max-w-[24rem] truncate">{a.details ?? "—"}</Td>
                <Td className="font-mono text-xs text-slate-400">{a.ipAddress ?? "—"}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}
