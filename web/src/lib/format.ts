import type { DebtStatus, DebtType, PaymentMethod, Permission } from "./types";

const money = new Intl.NumberFormat("az-AZ", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function formatMoney(value: number | null | undefined): string {
  return `${money.format(value ?? 0)} ₼`;
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const [y, m, d] = value.slice(0, 10).split("-");
  return `${d}.${m}.${y}`;
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  return date.toLocaleString("az-AZ", { timeZone: "Asia/Baku", dateStyle: "short", timeStyle: "short" });
}

export const DEBT_TYPE_LABEL: Record<DebtType, string> = {
  CUSTOMER_DEBT: "Müştəri borcları",
  COMPANY_RECEIVABLE: "Tədarükçünün bizə borcu",
  COMPANY_PAYABLE: "Bizim tədarükçüyə borcumuz",
};

export const DEBT_SECTION_SLUG: Record<string, DebtType> = {
  musteriler: "CUSTOMER_DEBT",
  debitorlar: "COMPANY_RECEIVABLE",
  kreditorlar: "COMPANY_PAYABLE",
};

export const DEBT_STATUS_LABEL: Record<DebtStatus, string> = {
  OPEN: "Açıq",
  PARTIAL: "Qismən",
  PAID: "Ödənilib",
};

export const METHOD_LABEL: Record<PaymentMethod, string> = {
  CASH: "Nağd",
  CARD: "Kart",
  TRANSFER: "Köçürmə",
  OTHER: "Digər",
};

export const METHOD_TONE: Record<PaymentMethod, "emerald" | "sky" | "gold" | "slate"> = {
  CASH: "emerald",
  CARD: "sky",
  TRANSFER: "gold",
  OTHER: "slate",
};

/** Today's date (YYYY-MM-DD) in the business time zone — not UTC, which is "yesterday" in Baku before 04:00. */
export function todayBaku(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Baku", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

export const PERMISSION_LABEL: Record<Permission, string> = {
  CUSTOMER_DEBTS: "Müştəri borcları",
  COMPANY_RECEIVABLES: "Tədarükçünün bizə borcu",
  COMPANY_PAYABLES: "Bizim tədarükçüyə borcumuz",
  REPORTS: "Hesabatlar və panel",
  CASH: "Kassa və bank",
  EXPENSES: "Xərclər və büdcə",
  INVENTORY: "Anbar",
  SALES: "Satış",
  TILL_SETTINGS: "Kassa parametrləri",
  RETURN_APPROVE: "Qaytarmanı təsdiq (menecer)",
};

export const SUBSCRIPTION_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "Aktiv",
  PENDING: "Təsdiq gözləyir",
  EXPIRED: "Müddəti bitib",
  REJECTED: "Rədd edilib",
  NONE: "Abunəlik yoxdur",
};

/** "Aylıq", "6 aylıq", "İllik" … from a plan's duration. */
export function planPeriodLabel(months: number): string {
  if (months === 1) return "Aylıq";
  if (months === 12) return "İllik";
  if (months % 12 === 0) return `${months / 12} illik`;
  return `${months} aylıq`;
}

export const ERROR_HINTS: Record<string, string> = {
  SUBSCRIPTION_REQUIRED: "Aktiv abunəlik tələb olunur",
  PERMISSION_DENIED: "Bu modul üçün icazəniz yoxdur",
};

/** Employee's job (vəzifə). */
export const JOB_TITLE_LABEL: Record<import("./types").JobTitle, string> = {
  CASHIER: "Kassir",
  STOREKEEPER: "Anbardar",
  ACCOUNTANT: "Mühasib",
  SELLER: "Satıcı",
  COURIER: "Kuryer",
  OTHER: "Digər",
};

/** Permissions a job starts with; the owner can still change each one. */
export const JOB_TITLE_PERMISSIONS: Record<import("./types").JobTitle, import("./types").Permission[]> = {
  CASHIER: ["SALES", "CASH", "CUSTOMER_DEBTS"],
  STOREKEEPER: ["INVENTORY"],
  ACCOUNTANT: ["REPORTS", "CASH", "EXPENSES", "CUSTOMER_DEBTS", "COMPANY_RECEIVABLES", "COMPANY_PAYABLES"],
  SELLER: ["SALES", "CUSTOMER_DEBTS"],
  // a courier sees only the deliveries given to them (Çatdırılma), no other section
  COURIER: [],
  OTHER: [],
};

/** Sections a package can include, in menu order; names as on the package card. */
export const PLAN_MODULE_LABEL: Record<import("./types").PlanModule, string> = {
  CUSTOMER_DEBTS: "Müştəri borcları",
  COMPANY_RECEIVABLES: "Tədarükçünün bizə borcu",
  COMPANY_PAYABLES: "Bizim tədarükçüyə borcumuz",
  REPORTS: "Panel, gəlir və mənfəət, hesabatlar",
  SALES: "Satış və kassa terminalı",
  INVENTORY: "Anbar, sayım, barkod",
  CASH: "Kassa və bank",
  EXPENSES: "Xərclər və büdcə",
  POCKET: "Cib pulu",
  EMPLOYEES: "İşçilər və işçi fəaliyyəti",
};
