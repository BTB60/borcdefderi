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
  COMPANY_RECEIVABLE: "Şirkət alacaqları",
  COMPANY_PAYABLE: "Şirkət borcları",
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
};

export const PERMISSION_LABEL: Record<Permission, string> = {
  CUSTOMER_DEBTS: "Müştəri borcları",
  COMPANY_RECEIVABLES: "Şirkət alacaqları",
  COMPANY_PAYABLES: "Şirkət borcları",
  REPORTS: "Hesabatlar və panel",
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
