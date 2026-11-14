"use client";
import Image from "next/image";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useAuth } from "@/lib/auth";
import { api } from "@/lib/api";
import type { Notification, Permission } from "@/lib/types";
import { cx } from "./ui";
import { Countdown } from "./Countdown";
import { Avatar } from "./Avatar";

const SETTINGS_ICON = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7.4-3a7.4 7.4 0 0 0-.1-1.3l2-1.6-2-3.4-2.4 1a7.6 7.6 0 0 0-2.2-1.3L14.3 3h-4l-.4 2.5a7.6 7.6 0 0 0-2.2 1.3l-2.4-1-2 3.4 2 1.6a7.4 7.4 0 0 0 0 2.6l-2 1.6 2 3.4 2.4-1a7.6 7.6 0 0 0 2.2 1.3l.4 2.5h4l.4-2.5a7.6 7.6 0 0 0 2.2-1.3l2.4 1 2-3.4-2-1.6c.1-.4.1-.9.1-1.3Z";

type NavItem = { href: string; label: string; icon: string; permission?: Permission; ownerOnly?: boolean };

const STORE_NAV: NavItem[] = [
  { href: "/dashboard", label: "Panel", icon: "M3 13h8V3H3v10Zm10 8h8V11h-8v10ZM3 21h8v-6H3v6Zm10-18v6h8V3h-8Z" },
  { href: "/borclar/musteriler", label: "Müştəri borcları", icon: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0", permission: "CUSTOMER_DEBTS" },
  { href: "/borclar/debitorlar", label: "Şirkətin bizə borcu", icon: "M3 6h18v12H3V6Zm0 4h18M7 15h4", permission: "COMPANY_RECEIVABLES" },
  { href: "/borclar/kreditorlar", label: "Bizim şirkətə borcumuz", icon: "M4 4h16v16H4V4Zm4 4h8M8 12h8M8 16h5", permission: "COMPANY_PAYABLES" },
  { href: "/odenisler", label: "Ödənişlər", icon: "M4 7h16v10H4V7Zm0 3h16" },
  { href: "/satis", label: "Satış", icon: "M3 4h2l2.4 11h11.2L21 8H6.2M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm9 0a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z", permission: "SALES" },
  { href: "/anbar", label: "Anbar", icon: "M3 9l9-6 9 6v11H3V9Zm6 11v-6h6v6", permission: "INVENTORY" },
  { href: "/maliyye", label: "Gəlir və mənfəət", icon: "M4 19h16M6 16l4-5 3 3 5-7", permission: "REPORTS" },
  { href: "/kassa", label: "Kassa və bank", icon: "M3 7h18v12H3V7Zm0 4h18M7 3h10v4H7V3Z", permission: "CASH" },
  { href: "/xercler", label: "Xərclər", icon: "M12 3v18M17 7.5C17 5.6 14.8 4 12 4S7 5.6 7 7.5 9.2 11 12 11.5s5 2 5 4-2.2 3.5-5 3.5-5-1.6-5-3.5", permission: "EXPENSES" },
  { href: "/cib-pulu", label: "Cib pulu", icon: "M3 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1h1a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Zm13 5h3" },
  { href: "/kontragentlar", label: "Müştəri və şirkətlər", icon: "M16 11a4 4 0 1 0-8 0M3 21a9 9 0 0 1 18 0" },
  { href: "/hesabatlar", label: "Hesabatlar", icon: "M7 3h7l5 5v13H7V3Zm7 0v5h5M10 13h6M10 17h6" },
  { href: "/abunelik", label: "Abunəlik", icon: "M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7l8-4Z" },
  { href: "/isciler", label: "İşçilər", icon: "M12 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm-6 8a6 6 0 0 1 12 0", ownerOnly: true },
  { href: "/fealiyyet", label: "İşçi fəaliyyəti", icon: "M4 19h16M7 16V9m5 7V5m5 11v-4", ownerOnly: true },
  { href: "/profil", label: "Parametrlər", icon: SETTINGS_ICON },
];

const ADMIN_NAV: NavItem[] = [
  { href: "/admin", label: "Platforma idarəetməsi", icon: "M12 2l3 7h7l-5.5 4.5L18 21l-6-4-6 4 1.5-7.5L2 9h7l3-7Z" },
  { href: "/profil", label: "Parametrlər", icon: SETTINGS_ICON },
];

function Icon({ d, className }: { d: string; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className={cx("h-5 w-5 shrink-0", className)} aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const { me, can, isOwner, isSuperAdmin, signOut } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [notes, setNotes] = useState<Notification[]>([]);
  const [showNotes, setShowNotes] = useState(false);

  useEffect(() => setOpen(false), [pathname]);

  useEffect(() => {
    if (!me || isSuperAdmin) return;
    api<Notification[]>("/api/notifications").then(setNotes).catch(() => setNotes([]));
  }, [me, isSuperAdmin, pathname]);

  const items = isSuperAdmin
    ? ADMIN_NAV
    : STORE_NAV.filter((i) => (i.ownerOnly ? isOwner : i.permission ? can(i.permission) : true));

  const subscription = me?.subscription;
  const roleLabel = isSuperAdmin ? "Platforma administratoru" : isOwner ? "Mağaza sahibi" : "İşçi";

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
          </div>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-5" aria-label="Əsas naviqasiya">
          {items.map((item) => {
            const active = pathname === item.href || pathname.startsWith(item.href + "/");
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cx(
                  "group flex items-center gap-3 rounded-xl px-3.5 py-2.5 text-sm font-medium transition-all duration-200",
                  active ? "bg-white/10 text-gold-400 shadow-inner" : "text-white/75 hover:bg-white/5 hover:text-white",
                )}
              >
                <span className={cx("h-6 w-1 rounded-full transition-colors", active ? "bg-gold-500" : "bg-transparent group-hover:bg-white/20")} />
                <Icon d={item.icon} className={active ? "text-gold-400" : ""} />
                {item.label}
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

        <main className="mx-auto max-w-7xl px-4 py-6 sm:px-8 sm:py-8">{children}</main>
      </div>
    </div>
  );
}
