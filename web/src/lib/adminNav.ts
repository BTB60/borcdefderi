/** Platform admin sections, grouped as they appear in the left (blue) menu. d = 24px outline icon path. */
export type AdminTab = "overview" | "stores" | "expiring" | "subscriptions" | "plans" | "revenue" | "costs" | "growth" | "users" | "audit" | "settings";

export const ADMIN_GROUPS: { title: string; items: { key: AdminTab; label: string; d: string }[] }[] = [
  { title: "", items: [{ key: "overview", label: "İcmal", d: "M3 12l9-8 9 8M5 10v10h5v-6h4v6h5V10" }] },
  {
    title: "Mağazalar",
    items: [
      { key: "stores", label: "Bütün mağazalar", d: "M4 7h16l-1 4H5L4 7zm1 4v9h14v-9M9 20v-5h6v5" },
      { key: "expiring", label: "Bitən abunəliklər", d: "M12 7v5l3 3M12 3a9 9 0 100 18 9 9 0 000-18z" },
      { key: "subscriptions", label: "Ödəniş sorğuları", d: "M3 7h18v10H3zM3 11h18M7 15h3" },
    ],
  },
  {
    title: "Pul və satış",
    items: [
      { key: "revenue", label: "Gəlir hesabatı", d: "M4 20V10m6 10V4m6 16v-7m6 7H2" },
      { key: "costs", label: "Platforma xərcləri", d: "M4 6h16v12H4zM4 10h16M8 15h3m5 0h0M12 3v3" },
      { key: "plans", label: "Abunəlik paketləri", d: "M4 6h16v12H4zM8 6v12M4 10h4" },
      { key: "growth", label: "Promo, elan, reklam", d: "M4 10v4l12 5V5L4 10zm12-1a3 3 0 010 6M7 14l1 5h3" },
    ],
  },
  {
    title: "Sistem",
    items: [
      { key: "users", label: "İstifadəçilər", d: "M16 19v-1a4 4 0 00-8 0v1M12 11a3 3 0 100-6 3 3 0 000 6zm7 8v-1a3 3 0 00-2-3M17 5a3 3 0 010 6" },
      { key: "audit", label: "Audit jurnalı", d: "M8 4h8l3 3v13H5V4h3zm0 6h8m-8 4h8m-8 4h5" },
      { key: "settings", label: "Ödəniş və əlaqə", d: "M12 15a3 3 0 100-6 3 3 0 000 6zm7-3l2-1-2-4-2 1-2-2V4h-4v2L9 8 7 7 5 11l2 1-2 1 2 4 2-1 2 2v2h4v-2l2-2 2 1 2-4-2-1z" },
    ],
  },
];

export const ADMIN_TABS = ADMIN_GROUPS.flatMap((g) => g.items);

/** What a köməkçi admin (SUPPORT_ADMIN) may open. */
export const SUPPORT_TABS: AdminTab[] = ["stores", "expiring", "subscriptions"];

/** The groups as a köməkçi admin sees them. */
export const SUPPORT_GROUPS = ADMIN_GROUPS
  .map((g) => ({ ...g, items: g.items.filter((i) => SUPPORT_TABS.includes(i.key)) }))
  .filter((g) => g.items.length > 0);

export const adminHref = (t: AdminTab) => (t === "overview" ? "/admin" : `/admin?tab=${t}`);

export function adminTabFrom(v: string | null): AdminTab {
  return (ADMIN_TABS.find((t) => t.key === v)?.key ?? "overview");
}
