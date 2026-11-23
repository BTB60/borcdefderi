"use client";
import { AnnouncementBanner } from "@/components/AnnouncementBanner";
import { BranchSwitcher } from "@/components/BranchSwitcher";
import Image from "next/image";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type ReactNode } from "react";
import { ADMIN_GROUPS, SUPPORT_GROUPS, adminHref, adminTabFrom } from "@/lib/adminNav";
import { desktop, type UpdateStatus } from "@/lib/desktop";
import { PinPad } from "@/components/sales/PinPad";
import { TillLock, lockNow } from "@/components/sales/TillLock";
import { useAuth } from "@/lib/auth";
import { api, openProtected } from "@/lib/api";
import { JOB_TITLE_LABEL } from "@/lib/format";
import type { Notification, Permission } from "@/lib/types";
import { cx } from "./ui";
import { Countdown } from "./Countdown";
import { Avatar } from "./Avatar";
import { OrderAlerts } from "./OrderAlerts";
import { loadPrintDesign } from "@/lib/printDesign";

const SETTINGS_ICON = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7.4-3a7.4 7.4 0 0 0-.1-1.3l2-1.6-2-3.4-2.4 1a7.6 7.6 0 0 0-2.2-1.3L14.3 3h-4l-.4 2.5a7.6 7.6 0 0 0-2.2 1.3l-2.4-1-2 3.4 2 1.6a7.4 7.4 0 0 0 0 2.6l-2 1.6 2 3.4 2.4-1a7.6 7.6 0 0 0 2.2 1.3l.4 2.5h4l.4-2.5a7.6 7.6 0 0 0 2.2-1.3l2.4 1 2-3.4-2-1.6c.1-.4.1-.9.1-1.3Z";

const DOWNLOAD_ICON = "M12 3v12m0 0-4-4m4 4 4-4M5 21h14";

type NavItem = { href: string; label: string; icon: string; permission?: Permission; anyOf?: Permission[]; ownerOnly?: boolean; module?: string; jobTitle?: string };
const DEBT_PERMISSIONS: Permission[] = ["CUSTOMER_DEBTS", "COMPANY_RECEIVABLES", "COMPANY_PAYABLES"];

const STORE_NAV: NavItem[] = [
  { href: "/dashboard", label: "Panel", icon: "M3 13h8V3H3v10Zm10 8h8V11h-8v10ZM3 21h8v-6H3v6Zm10-18v6h8V3h-8Z", permission: "REPORTS" },
  { href: "/komekci", label: "Köməkçi (AI)", icon: "M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8L12 3Zm6 11 .9 2.1 2.1.9-2.1.9-.9 2.1-.9-2.1-2.1-.9 2.1-.9.9-2.1Z", ownerOnly: true },
  { href: "/borclar/musteriler", label: "Müştəri borcları", icon: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0", permission: "CUSTOMER_DEBTS" },
  { href: "/borclar/debitorlar", label: "Tədarükçünün bizə borcu", icon: "M3 6h18v12H3V6Zm0 4h18M7 15h4", permission: "COMPANY_RECEIVABLES" },
  { href: "/borclar/kreditorlar", label: "Bizim tədarükçüyə borcumuz", icon: "M4 4h16v16H4V4Zm4 4h8M8 12h8M8 16h5", permission: "COMPANY_PAYABLES" },
  { href: "/odenisler", label: "Ödənişlər", icon: "M4 7h16v10H4V7Zm0 3h16", anyOf: DEBT_PERMISSIONS },
  { href: "/zemanet", label: "Zəmanət (IMEI)", icon: "M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6l-8-3Zm-3 9 2 2 4-4", permission: "SALES" },
  { href: "/taksitler", label: "Taksitlər", icon: "M8 2v4m8-4v4M3 10h18M5 6h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Zm3 9h2m4 0h2m-8 3h2", permission: "CUSTOMER_DEBTS" },
  { href: "/xatirlatmalar", label: "Xatırlatmalar", icon: "M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9Zm4.3 13a1.94 1.94 0 0 0 3.4 0", permission: "CUSTOMER_DEBTS" },
  { href: "/satis", label: "Satış", icon: "M3 4h2l2.4 11h11.2L21 8H6.2M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm9 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z", permission: "SALES" },
  { href: "/sifarisler", label: "Onlayn sifarişlər", icon: "M4 6h16l-1.5 9h-13L4 6Zm3 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm10 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2ZM8 3h8", permission: "SALES" },
  { href: "/catdirilma", label: "Çatdırılma", icon: "M3 7h11v9H3V7Zm11 3h4l3 3v3h-7v-6ZM7 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm10 0a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z", permission: "SALES", jobTitle: "COURIER" },
  { href: "/anbar", label: "Anbar", icon: "M3 9l9-6 9 6v11H3V9Zm6 11v-6h6v6", permission: "INVENTORY" },
  { href: "/maliyye", label: "Gəlir və mənfəət", icon: "M4 19h16M6 16l4-5 3 3 5-7", permission: "REPORTS" },
  { href: "/kassa", label: "Kassa və bank", icon: "M3 7h18v12H3V7Zm0 4h18M7 3h10v4H7V3Z", permission: "CASH" },
  { href: "/xercler", label: "Xərclər", icon: "M12 3v18M17 7.5C17 5.6 14.8 4 12 4S7 5.6 7 7.5 9.2 11 12 11.5s5 2 5 4-2.2 3.5-5 3.5-5-1.6-5-3.5", permission: "EXPENSES" },
  { href: "/cib-pulu", label: "Cib pulu", icon: "M3 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1h1a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Zm13 5h3", module: "POCKET", ownerOnly: true },
  { href: "/kontragentlar", label: "Müştəri və tədarükçülər", icon: "M16 11a4 4 0 1 0-8 0M3 21a9 9 0 0 1 18 0", anyOf: DEBT_PERMISSIONS },
  { href: "/hesabatlar", label: "Hesabatlar", icon: "M7 3h7l5 5v13H7V3Zm7 0v5h5M10 13h6M10 17h6", anyOf: ["REPORTS", ...DEBT_PERMISSIONS] },
  { href: "/filiallar", label: "Filiallar", icon: "M3 21h18M5 21V8l7-5 7 5v13M9 21v-6h6v6", ownerOnly: true },
  { href: "/abunelik", label: "Abunəlik", icon: "M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7l8-4Z", ownerOnly: true },
  { href: "/isciler", label: "İşçilər", icon: "M12 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm-6 8a6 6 0 0 1 12 0", ownerOnly: true, module: "EMPLOYEES" },
  { href: "/maas", label: "İşçi maaşı", icon: "M3 7h18v10H3V7Zm9 2a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM6 10v4m12-4v4", ownerOnly: true, module: "EMPLOYEES" },
  { href: "/davamiyyet", label: "Davamiyyət", icon: "M12 7v5l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z" },
  { href: "/fealiyyet", label: "İşçi fəaliyyəti", icon: "M4 19h16M7 16V9m5 7V5m5 11v-4", ownerOnly: true, module: "EMPLOYEES" },
  { href: "/tetbiqler", label: "Tətbiqi yüklə", icon: DOWNLOAD_ICON, ownerOnly: true },
  { href: "/profil", label: "Parametrlər", icon: SETTINGS_ICON },
];

const ADMIN_NAV: NavItem[] = [
  { href: "/admin", label: "Platforma idarəetməsi", icon: "M12 2l3 7h7l-5.5 4.5L18 21l-6-4-6 4 1.5-7.5L2 9h7l3-7Z" },
  { href: "/tetbiqler", label: "Tətbiqi yüklə", icon: DOWNLOAD_ICON },
  { href: "/profil", label: "Parametrlər", icon: SETTINGS_ICON },
];

function Icon({ d, className }: { d: string; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className={cx("h-5 w-5 shrink-0", className)} aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

/** The platform admin's sections, grouped, in the blue sidebar (the admin page shows the one in ?tab=). */
function AdminNav({ pending, onExport, exporting, limited }: { pending: number; onExport: () => void; exporting: boolean; limited: boolean }) {
  const pathname = usePathname();
  const params = useSearchParams();
  const current = pathname === "/admin" ? adminTabFrom(params.get("tab")) : null;
  return (
    <>
      {(limited ? SUPPORT_GROUPS : ADMIN_GROUPS).map((g) => (
        <div key={g.title || "top"} className={g.title ? "pt-4" : ""}>
          {g.title && <p className="px-4 pb-1.5 text-[11px] font-semibold uppercase tracking-widest text-white/35">{g.title}</p>}
          {g.items.map((t) => {
            const active = current === t.key;
            const badge = t.key === "subscriptions" ? pending : 0;
            return (
              <Link
                key={t.key}
                href={adminHref(t.key)}
                aria-current={active ? "page" : undefined}
                className={cx(
                  "group flex items-center gap-3 rounded-xl px-3.5 py-2 text-sm font-medium transition-all duration-200",
                  active ? "bg-white/10 text-gold-400 shadow-inner" : "text-white/75 hover:bg-white/5 hover:text-white",
                )}
              >
                <span className={cx("h-5 w-1 rounded-full transition-colors", active ? "bg-gold-500" : "bg-transparent group-hover:bg-white/20")} />
                <Icon d={t.d} className={active ? "text-gold-400" : ""} />
                <span className="flex-1">{t.label}</span>
                {badge > 0 && <span className="rounded-full bg-amber-500 px-1.5 py-0.5 text-[10px] font-bold leading-none text-white">{badge}</span>}
              </Link>
            );
          })}
        </div>
      ))}
      <div className="pt-4">
        <p className="px-4 pb-1.5 text-[11px] font-semibold uppercase tracking-widest text-white/35">Hesab</p>
        <Link href="/profil" className={cx("group flex items-center gap-3 rounded-xl px-3.5 py-2 text-sm font-medium transition-all",
          pathname === "/profil" ? "bg-white/10 text-gold-400" : "text-white/75 hover:bg-white/5 hover:text-white")}>
          <span className={cx("h-5 w-1 rounded-full", pathname === "/profil" ? "bg-gold-500" : "bg-transparent")} />
          <Icon d={SETTINGS_ICON} className={pathname === "/profil" ? "text-gold-400" : ""} />
          Parametrlər
        </Link>
        {!limited && <button onClick={onExport} disabled={exporting}
          className="group flex w-full items-center gap-3 rounded-xl px-3.5 py-2 text-left text-sm font-medium text-white/75 transition hover:bg-white/5 hover:text-white disabled:opacity-50">
          <span className="h-5 w-1 rounded-full bg-transparent group-hover:bg-white/20" />
          <Icon d="M12 4v11m0 0l-4-4m4 4l4-4M5 20h14" />
          {exporting ? "Hazırlanır…" : "Excel-ə ixrac"}
        </button>}
      </div>
    </>
  );
}

/** Top bar of the Windows kassa: store, live clock, internet state, cashier and sign-out. */
function KioskHeader({ store, name, role, onSignOut }: { store: string; name: string; role: string; onSignOut: () => void }) {
  const [now, setNow] = useState(() => new Date());
  const [switching, setSwitching] = useState(false);
  const [online, setOnline] = useState(true);
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    const on = () => setOnline(true), off = () => setOnline(false);
    setOnline(navigator.onLine);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => { clearInterval(t); window.removeEventListener("online", on); window.removeEventListener("offline", off); };
  }, []);
  // written out by hand: many Windows / Chromium builds have no Azerbaijani date names
  const WEEKDAYS = ["Bazar", "Bazar ertəsi", "Çərşənbə axşamı", "Çərşənbə", "Cümə axşamı", "Cümə", "Şənbə"];
  const MONTHS = ["yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avqust", "sentyabr", "oktyabr", "noyabr", "dekabr"];
  const time = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
  const date = `${WEEKDAYS[now.getDay()]}, ${now.getDate()} ${MONTHS[now.getMonth()]}`;
  // a new version of the Windows app downloads by itself; when ready a "Yenilə" button appears
  const [update, setUpdate] = useState<UpdateStatus | null>(null);
  useEffect(() => {
    const app = desktop();
    if (!app?.onUpdate || !app.updateStatus) return;
    app.updateStatus().then(setUpdate).catch(() => undefined);
    return app.onUpdate(setUpdate);
  }, []);
  const initials = name.split(" ").map((w) => w[0]).filter(Boolean).slice(0, 2).join("").toUpperCase();
  return (
    <header className="relative flex h-16 shrink-0 items-center justify-between gap-4 bg-gradient-to-r from-navy-900 via-navy-800 to-navy-700 px-5 text-white shadow-lg">
      <div className="flex min-w-0 items-center gap-3">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white p-1.5 shadow"><Image src="/logo-mark.png" alt="" width={48} height={48} className="h-full w-full object-contain" /></div>
        <div className="min-w-0">
          <p className="font-serif text-lg font-semibold leading-tight">HesabPro <span className="text-gold-400">Kassa</span></p>
          <p className="truncate text-xs text-white/55">{store}</p>
        </div>
      </div>
      <div className={cx("absolute left-1/2 hidden -translate-x-1/2 text-center", update && update.state !== "idle" ? "" : "xl:block")}>
        <p className="font-serif text-2xl font-semibold tabular-nums leading-none tracking-wide">{time}</p>
        <p className="mt-1 text-[11px] text-white/55">{date}</p>
      </div>
      <div className="flex items-center gap-3">
        {update?.state === "downloading" && (
          <span className="hidden items-center gap-2 rounded-full bg-white/10 px-3 py-1 text-xs text-white/80 sm:inline-flex" title="Yeni versiya yüklənir">
            <span className="h-3 w-3 animate-spin rounded-full border-2 border-white/30 border-t-gold-400" />
            Yeniləmə yüklənir{update.percent ? ` ${update.percent}%` : "…"}
          </span>
        )}
        {update?.state === "installing" && (
          <span className="inline-flex items-center gap-2 whitespace-nowrap rounded-xl bg-gold-500 px-4 py-2 text-sm font-semibold text-navy-900"
            title="Proqram bağlanıb yeni versiya quraşdırılacaq və özü yenidən açılacaq">
            <span className="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-navy-900/30 border-t-navy-900" />
            Yenilənir…<span className="hidden xl:inline">&nbsp;proqram bağlanıb yenidən açılacaq</span>
          </span>
        )}
        {update?.state === "ready" && (
          <button onClick={() => {
            // older app versions install silently and do not say anything: show it here first
            setUpdate({ ...update, state: "installing" });
            setTimeout(() => { void desktop()?.installUpdate?.(); }, 1500);
          }}
            title={`Yeni versiya ${update.version ?? ""} hazırdır — proqram bağlanıb yenidən açılacaq`}
            className="flex h-10 items-center gap-2 rounded-xl bg-gold-500 px-4 text-sm font-semibold text-navy-900 shadow-glow transition hover:bg-gold-400">
            <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2.2} aria-hidden="true"><path d="M21 12a9 9 0 1 1-3-6.7M21 4v5h-5" /></svg>
            Yenilə{update.version ? ` (v${update.version})` : ""}
          </button>
        )}
        <span className={cx("hidden items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium sm:inline-flex",
          online ? "bg-emerald-400/15 text-emerald-300" : "bg-rose-500/20 text-rose-200")}>
          <span className={cx("h-2 w-2 rounded-full", online ? "bg-emerald-400" : "animate-pulse bg-rose-400")} />
          {online ? "Onlayn" : "İnternet yoxdur — satış yadda saxlanır"}
        </span>
        <div className="flex items-center gap-2.5 rounded-xl bg-white/5 py-1 pl-1 pr-3 ring-1 ring-white/10">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gold-500 text-xs font-bold text-navy-900">{initials || "K"}</span>
          <span className="hidden leading-tight lg:block">
            <span className="block text-sm font-medium">{name}</span>
            <span className="block text-[11px] text-white/55">{role}</span>
          </span>
        </div>
        <button onClick={() => setSwitching(true)} title="Başqa kassir PIN ilə keçsin"
          className="flex h-10 items-center gap-2 rounded-xl border border-white/15 px-3 text-sm text-white/80 transition hover:border-gold-500 hover:text-gold-400">
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M16 3h5v5M21 3l-7 7M8 21H3v-5M3 21l7-7" /></svg>
          <span className="hidden xl:inline">Kassir dəyiş</span>
        </button>
        <button onClick={lockNow} title="Ekranı kilidlə (PIN ilə açılır)" aria-label="Ekranı kilidlə"
          className="flex h-10 w-10 items-center justify-center rounded-xl border border-white/15 text-white/80 transition hover:border-gold-500 hover:text-gold-400">
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M6 11h12v10H6V11Zm3 0V8a3 3 0 0 1 6 0v3" /></svg>
        </button>
        {switching && <PinPad title="Kassir dəyiş" subtitle="Növbəti kassir PIN-ini yazsın" onDone={() => setSwitching(false)} onCancel={() => setSwitching(false)} />}
        <button onClick={onSignOut} title="Çıxış" aria-label="Çıxış"
          className="flex h-10 items-center gap-2 rounded-xl border border-white/15 px-3 text-sm text-white/80 transition hover:border-gold-500 hover:text-gold-400">
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3" /></svg>
          <span className="hidden sm:inline">Çıxış</span>
        </button>
      </div>
    </header>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const { me, can, isLocked, isOwner, isPlatformAdmin: isSuperAdmin, isSupportAdmin, signOut } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [notes, setNotes] = useState<Notification[]>([]);
  const [showNotes, setShowNotes] = useState(false);

  useEffect(() => setOpen(false), [pathname]);

  // admin: payment requests waiting for approval (badge in the menu) and the Excel export
  const [pending, setPending] = useState(0);
  const [exporting, setExporting] = useState(false);
  useEffect(() => {
    if (!isSuperAdmin) return;
    api<unknown[]>("/api/admin/subscriptions?status=PENDING").then((l) => setPending(l.length)).catch(() => setPending(0));
  }, [isSuperAdmin, pathname]);
  async function exportExcel() {
    setExporting(true);
    try {
      await openProtected("/api/admin/export");
    } catch {
      /* the button simply stops spinning; the admin can try again */
    } finally {
      setExporting(false);
    }
  }

  // the store's receipt and label design, so every till and browser prints the same
  const storeId = me?.storeId;
  useEffect(() => {
    if (storeId && !isSuperAdmin) void loadPrintDesign();
  }, [storeId, isSuperAdmin]);

  useEffect(() => {
    if (!me || isSuperAdmin) return;
    api<Notification[]>("/api/notifications").then(setNotes).catch(() => setNotes([]));
  }, [me, isSuperAdmin, pathname]);

  const items = isSuperAdmin
    ? ADMIN_NAV
    : STORE_NAV.filter((i) => {
        if (i.jobTitle && me?.jobTitle === i.jobTitle) return true;
        if (i.module && isLocked(i.module)) return isOwner;
        if (i.ownerOnly) return isOwner;
        if (i.permission) return can(i.permission) || (isOwner && isLocked(i.permission));
        if (i.anyOf) return i.anyOf.some(can) || (isOwner && i.anyOf.every((p) => isLocked(p)));
        return true;
      });
  // a section the package does not include: the owner sees it with a lock that leads to the packages
  const sectionLocked = (i: NavItem) =>
    (!!i.module && isLocked(i.module)) || (!!i.permission && isLocked(i.permission)) || (!!i.anyOf && i.anyOf.every((p) => isLocked(p)));
  const lockedHref = (i: NavItem) => (isOwner && sectionLocked(i) ? "/abunelik" : i.href);

  // an employee without "Hesabatlar" lands on the first section their job opens (e.g. Anbar for an anbardar)
  useEffect(() => {
    if (!me || isSuperAdmin || can("REPORTS") || pathname !== "/dashboard") return;
    const first = items.find((i) => i.href !== "/dashboard" && !sectionLocked(i));
    if (first) router.replace(first.href);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [me, isSuperAdmin, can, pathname, items, router]);

  // HesabPro Kassa (Windows app): only the till, full screen, no menu
  const kiosk = typeof window !== "undefined" && !!window.hesabDesktop;
  useEffect(() => {
    if (kiosk && pathname !== "/satis") router.replace("/satis");
  }, [kiosk, pathname, router]);

  const subscription = me?.subscription;
  const roleLabel = isSupportAdmin ? "Köməkçi admin" : isSuperAdmin ? "Platforma administratoru" : isOwner ? "Mağaza sahibi" : me?.jobTitle ? JOB_TITLE_LABEL[me.jobTitle] : "İşçi";

  if (kiosk) {
    // HesabPro Kassa (Windows): one full-screen till, no page scroll; the till panels scroll inside
    return (
      <div className="flex h-screen flex-col overflow-hidden bg-[#EEF1F7]">
        <KioskHeader store={me?.storeName ?? ""} name={me?.fullName ?? ""} role={roleLabel} onSignOut={signOut} />
        <TillLock />
        <main className="min-h-0 flex-1 p-3 [&>*]:h-full">{pathname === "/satis" ? children : null}</main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-canvas">
      {open && <div className="fixed inset-0 z-30 bg-navy-900/40 backdrop-blur-sm lg:hidden" onClick={() => setOpen(false)} />}

      <aside
        className={cx(
          "fixed inset-y-0 left-0 z-40 flex w-72 flex-col bg-navy-800 text-white shadow-2xl transition-transform duration-300 lg:translate-x-0",
          open ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex items-center gap-3 border-b border-white/10 px-6 py-6">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-white p-1.5"><Image src="/logo-mark.png" alt="" width={64} height={64} priority className="h-full w-full object-contain" /></div>
          <div>
            <p className="font-serif text-lg font-semibold tracking-wide">HesabPro</p>
            <p className="text-xs text-white/50">{me?.storeName ?? "Platforma"}</p>
            {isOwner && !isSuperAdmin && <BranchSwitcher />}
          </div>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-5" aria-label="Əsas naviqasiya">
          {isSuperAdmin && (
            <Suspense fallback={null}>
              <AdminNav pending={pending} onExport={exportExcel} exporting={exporting} limited={isSupportAdmin} />
            </Suspense>
          )}
          {!isSuperAdmin && items.map((item) => {
            const active = pathname === item.href || pathname.startsWith(item.href + "/");
            return (
              <Link
                key={item.href}
                href={lockedHref(item)}
                title={lockedHref(item) !== item.href ? "Bu bölmə paketinizə daxil deyil — paketi yüksəldin" : undefined}
                className={cx(
                  "group flex items-center gap-3 rounded-xl px-3.5 py-2.5 text-sm font-medium transition-all duration-200",
                  active ? "bg-white/10 text-gold-400 shadow-inner" : "text-white/75 hover:bg-white/5 hover:text-white",
                )}
              >
                <span className={cx("h-6 w-1 rounded-full transition-colors", active ? "bg-gold-500" : "bg-transparent group-hover:bg-white/20")} />
                <Icon d={item.icon} className={active ? "text-gold-400" : ""} />
                {item.label}
                {lockedHref(item) !== item.href && (
                  <svg viewBox="0 0 24 24" className="ml-auto h-4 w-4 text-gold-400/80" fill="none" stroke="currentColor" strokeWidth={2} aria-label="Paketdə yoxdur"><path d="M6 11h12v10H6V11Zm3 0V8a3 3 0 0 1 6 0v3" /></svg>
                )}
              </Link>
            );
          })}
        </nav>

        <div className="space-y-3 border-t border-white/10 p-5">
          {subscription && !isSuperAdmin && (
            <div className="rounded-xl bg-white/5 p-3 text-xs">
              <p className="text-white/50">Abunəlik</p>
              <p className="mt-0.5 font-medium text-white">{subscription.active ? subscription.planName : "Aktiv deyil"}</p>
              {subscription.expiresAt && (
                <Link href="/abunelik" className="mt-1 block rounded-lg bg-white/90 px-2 py-1 text-[11px]">
                  <Countdown expiresAt={subscription.expiresAt} />
                </Link>
              )}
            </div>
          )}
          <Link href="/profil" className="-mx-2 flex items-center gap-3 rounded-xl px-2 py-1.5 transition hover:bg-white/5" title="Parametrlər">
            <Avatar name={me?.fullName} version={me?.hasAvatar ? me?.avatarVersion : null} size={36} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{me?.fullName}</p>
              <p className="truncate text-xs text-white/50">{roleLabel}</p>
            </div>
          </Link>
          <button onClick={signOut} className="w-full rounded-xl border border-white/15 px-3 py-2 text-sm text-white/80 transition hover:border-gold-500 hover:text-gold-400">
            Çıxış
          </button>
        </div>
      </aside>

      <div className="lg:pl-72">
        <header className="sticky top-0 z-20 flex items-center justify-between gap-4 border-b border-slate-200/70 bg-white/80 px-4 py-3 backdrop-blur-md sm:px-8">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setOpen(true)}
              className="rounded-lg p-2 text-navy-800 hover:bg-slate-100 lg:hidden"
              aria-label="Menyunu aç"
            >
              <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth={2}><path d="M4 7h16M4 12h16M4 17h16" /></svg>
            </button>
            {!isSuperAdmin && subscription && !subscription.active && (
              <button onClick={() => router.push("/abunelik")} className="rounded-full bg-amber-50 px-3 py-1 text-xs font-medium text-amber-700 ring-1 ring-amber-600/20">
                Abunəliyi aktivləşdirin →
              </button>
            )}
          </div>
          <div className="relative">
            <button
              onClick={() => setShowNotes((v) => !v)}
              className="relative rounded-xl p-2 text-navy-800 transition hover:bg-slate-100"
              aria-label="Bildirişlər"
            >
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8}><path d="M6 8a6 6 0 1 1 12 0c0 7 3 7 3 9H3c0-2 3-2 3-9Zm4.3 12a1.8 1.8 0 0 0 3.4 0" /></svg>
              {notes.length > 0 && <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-gold-500 ring-2 ring-white" />}
            </button>
            {showNotes && (
              <div className="absolute right-0 mt-2 w-80 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl">
                <div className="border-b border-slate-100 px-4 py-3 text-sm font-semibold text-navy-800">Bildirişlər</div>
                <ul className="max-h-80 divide-y divide-slate-100 overflow-y-auto">
                  {notes.length === 0 && <li className="px-4 py-6 text-center text-sm text-slate-500">Yeni bildiriş yoxdur</li>}
                  {notes.map((n, i) => (
                    <li key={i} className="px-4 py-3">
                      <p className="flex items-center gap-2 text-sm font-medium text-navy-800">
                        <span className={cx("h-2 w-2 rounded-full", n.severity === "DANGER" ? "bg-rose-500" : n.severity === "WARNING" ? "bg-amber-500" : "bg-sky-500")} />
                        {n.title}
                      </p>
                      <p className="mt-0.5 text-xs text-slate-500">{n.message}</p>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </header>

        <main className="mx-auto max-w-7xl px-4 py-6 sm:px-8 sm:py-8">{!isSuperAdmin && <AnnouncementBanner />}{children}</main>
        {/* online orders: sound + card for whoever takes them (owner, SALES) */}
        {me?.storeId && !isSuperAdmin && (isOwner || can("SALES")) && <OrderAlerts storeId={me.storeId} onNotes={setNotes} />}
      </div>
    </div>
  );
}
