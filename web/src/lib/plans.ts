import { formatMoney } from "./format";
import type { PeriodCode, PeriodOption, Plan } from "./types";

export const PERIOD_LABEL: Record<PeriodCode, string> = {
  MONTHLY: "1 aylıq",
  QUARTERLY: "3 aylıq",
  SEMI_ANNUAL: "6 aylıq",
  YEARLY: "İllik",
  CUSTOM: "Xüsusi",
};

/** "3 ay (90 gün)", "45 gün" … */
export function periodText(o: Pick<PeriodOption, "months" | "days"> | { months: number | null; days: number | null }): string {
  if (o.days) return `${o.days} gün`;
  const m = o.months ?? 1;
  if (m === 12) return "12 ay (1 il)";
  return `${m} ay`;
}

export function optionLabel(o: PeriodOption): string {
  return o.period === "CUSTOM" ? `${o.days} gün` : PERIOD_LABEL[o.period];
}

/** Price per month, to compare periods. */
export function monthlyEquivalent(o: PeriodOption): number {
  if (o.days) return (o.price / o.days) * 30;
  return o.price / (o.months ?? 1);
}

/** Saving of a period against paying the monthly price every month (0 when there is no monthly price). */
export function savingPercent(plan: Plan, o: PeriodOption): number {
  const monthly = plan.options.find((x) => x.period === "MONTHLY");
  if (!monthly || o.period === "MONTHLY" || monthly.price <= 0) return 0;
  const p = Math.round((1 - monthlyEquivalent(o) / monthly.price) * 100);
  return p > 0 ? p : 0;
}

export function findOption(plan: Plan, period: string | null | undefined): PeriodOption | undefined {
  return plan.options.find((o) => o.period === period) ?? plan.options[0];
}

export const DEFAULT_WHATSAPP_TEMPLATE =
  "Salam. BORCDEFTERI platformasında abunəlik paketi almaq istəyirəm.\n" +
  "Mağaza adı: [Mağaza adı]\n" +
  "Seçilmiş paket: [Paket adı]\n" +
  "Müddət: [Müddət]\n" +
  "Ödəniş məbləği: [Məbləğ]\n" +
  "Zəhmət olmasa, ödəniş və aktivləşdirmə barədə məlumat verin.";

/** Fills the admin's WhatsApp template; the user still presses "send" in WhatsApp themselves. */
export function fillWhatsappTemplate(template: string | null | undefined, v: { store: string; plan: string; period: string; amount: number }): string {
  return (template?.trim() || DEFAULT_WHATSAPP_TEMPLATE)
    .replaceAll("[Mağaza adı]", v.store || "—")
    .replaceAll("[Paket adı]", v.plan)
    .replaceAll("[Müddət]", v.period)
    .replaceAll("[Məbləğ]", formatMoney(v.amount));
}

/** "4169738812345678" → "4169 7388 1234 5678" */
export function formatCard(n: string | null | undefined): string {
  if (!n) return "";
  const d = n.replace(/\D/g, "");
  return d.replace(/(.{4})/g, "$1 ").trim();
}

/** Ready colour palettes (primary / secondary / background / text / button). */
export const PALETTES: { name: string; colors: PlanColors }[] = [
  { name: "Mavi", colors: { primaryColor: "#1D4ED8", secondaryColor: "#60A5FA", backgroundColor: "#FFFFFF", textColor: "#0F172A", buttonColor: "#1D4ED8" } },
  { name: "Bənövşəyi", colors: { primaryColor: "#7C3AED", secondaryColor: "#C4B5FD", backgroundColor: "#FFFFFF", textColor: "#1E1B4B", buttonColor: "#7C3AED" } },
  { name: "Yaşıl", colors: { primaryColor: "#059669", secondaryColor: "#6EE7B7", backgroundColor: "#FFFFFF", textColor: "#064E3B", buttonColor: "#059669" } },
  { name: "Qızılı", colors: { primaryColor: "#B8903F", secondaryColor: "#D6AD60", backgroundColor: "#101C35", textColor: "#FFFFFF", buttonColor: "#D6AD60" } },
  { name: "Narıncı", colors: { primaryColor: "#EA580C", secondaryColor: "#FDBA74", backgroundColor: "#FFFFFF", textColor: "#431407", buttonColor: "#EA580C" } },
  { name: "Qırmızı", colors: { primaryColor: "#DC2626", secondaryColor: "#FCA5A5", backgroundColor: "#FFFFFF", textColor: "#450A0A", buttonColor: "#DC2626" } },
  { name: "Tünd boz", colors: { primaryColor: "#334155", secondaryColor: "#94A3B8", backgroundColor: "#F8FAFC", textColor: "#0F172A", buttonColor: "#334155" } },
  { name: "Qara", colors: { primaryColor: "#111827", secondaryColor: "#D6AD60", backgroundColor: "#0B0F19", textColor: "#F9FAFB", buttonColor: "#F9FAFB" } },
];

export interface PlanColors {
  primaryColor: string;
  secondaryColor: string;
  backgroundColor: string;
  textColor: string;
  buttonColor: string;
}

/** Readable text colour (black/white) on top of a background colour. */
export function contrastText(hex: string): string {
  const h = hex.replace("#", "");
  if (h.length !== 6) return "#FFFFFF";
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return lum > 0.6 ? "#0F172A" : "#FFFFFF";
}

export const HEX_RE = /^#[0-9A-Fa-f]{6}$/;
