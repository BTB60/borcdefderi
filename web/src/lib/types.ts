export type DebtType = "CUSTOMER_DEBT" | "COMPANY_RECEIVABLE" | "COMPANY_PAYABLE";
export type DebtStatus = "OPEN" | "PARTIAL" | "PAID";
export type PaymentMethod = "CASH" | "CARD" | "TRANSFER" | "OTHER";
export type Permission = "CUSTOMER_DEBTS" | "COMPANY_RECEIVABLES" | "COMPANY_PAYABLES" | "REPORTS";

export interface SubscriptionSummary {
  active: boolean;
  status: "ACTIVE" | "PENDING" | "EXPIRED" | "REJECTED" | "NONE";
  planCode: string | null;
  planName: string | null;
  startDate: string | null;
  endDate: string | null;
  daysRemaining: number;
  renewalReminder: boolean;
  expiresAt?: string | null;
}

export interface Contact {
  phone: string | null;
  whatsapp: string | null;
  m10Account: string | null;
  m10Recipient: string | null;
  paymentNote: string | null;
}

export interface Me {
  userId: string;
  email: string;
  fullName: string;
  platformRole: "SUPER_ADMIN" | "USER";
  role: "STORE_OWNER" | "EMPLOYEE" | null;
  storeId: string | null;
  storeName: string | null;
  permissions: Permission[] | null;
  subscription: SubscriptionSummary | null;
  phone?: string | null;
  hasAvatar?: boolean;
  /** Changes whenever the profile picture changes. */
  avatarVersion?: number | null;
}

export interface Profile {
  userId: string;
  email: string;
  fullName: string;
  phone: string | null;
  platformRole: "SUPER_ADMIN" | "USER";
  role: "STORE_OWNER" | "EMPLOYEE" | null;
  storeId: string | null;
  storeName: string | null;
  storePhone: string | null;
  storeAddress: string | null;
  hasAvatar: boolean;
  avatarVersion: number | null;
}

export interface AuthResponse {
  token: string;
  expiresInSeconds: number;
  me: Me;
}

export interface Debt {
  id: string;
  debtType: DebtType;
  partyId: string;
  partyName: string;
  partyPhone: string | null;
  amount: number;
  paidAmount: number;
  remainingAmount: number;
  status: DebtStatus;
  debtDate: string;
  dueDate: string | null;
  description: string | null;
  note: string | null;
  createdAt: string;
  version: number;
}

export interface DebtPage {
  items: Debt[];
  total: number;
  page: number;
  size: number;
  totals: { amount: number; paid: number; remaining: number };
}

export interface LedgerEntry {
  id: string;
  type: "DEBT_CREATED" | "DEBT_ADJUSTED" | "PAYMENT_CASH" | "PAYMENT_CARD" | "PAYMENT_TRANSFER" | "PAYMENT_OTHER";
  amount: number;
  balanceAfter: number;
  paymentId: string | null;
  actorId: string;
  actorName: string;
  createdAt: string;
}

export interface Payment {
  id: string;
  debtId: string;
  debtType: DebtType;
  partyName: string;
  partyPhone?: string | null;
  amount: number;
  paymentMethod: PaymentMethod;
  paidAt: string;
  description: string | null;
  note: string | null;
  recordedBy: string;
  recordedByName: string | null;
  createdAt: string;
  remainingAfter?: number | null;
  debtStatusAfter?: DebtStatus | null;
  /** Public, unguessable link to the PDF receipt — sent to the customer over WhatsApp. */
  receiptUrl?: string | null;
}

export interface Party {
  id: string;
  name: string;
  phone: string | null;
  note: string | null;
  createdAt: string;
  openBalance: number;
  openDebtCount: number;
}

export interface DashboardSummary {
  customerDebts: number;
  companyReceivables: number;
  companyPayables: number;
  cashReceived: number;
  cardReceived: number;
  otherReceived: number;
  todayTotal: number;
  todayCash: number;
  todayCard: number;
  todayOther: number;
  monthTotal: number;
  monthCash: number;
  monthCard: number;
  monthOther: number;
}

export interface Dashboard {
  summary: DashboardSummary;
  daily: { date: string; cash: number; card: number; other: number; total: number }[];
  monthly: { month: string; cash: number; card: number; other: number; total: number }[];
  recentTransactions: Payment[];
  outstanding: { id: string; debtType: DebtType; partyName: string; remaining: number; dueDate: string | null; overdue: boolean }[];
}

export interface Notification {
  type: string;
  severity: "INFO" | "WARNING" | "DANGER";
  title: string;
  message: string;
  date: string;
}

export interface Plan {
  id: string;
  code: string;
  name: string;
  price: number;
  durationMonths: number;
  active: boolean;
}

export interface SubscriptionRecord {
  id: string;
  storeId: string;
  storeName: string;
  planCode: string;
  planName: string;
  status: "PENDING" | "ACTIVE" | "EXPIRED" | "REJECTED";
  amount: number;
  startDate: string | null;
  endDate: string | null;
  payerReference: string | null;
  rejectReason: string | null;
  requestedAt: string;
  reviewedAt: string | null;
  hasReceipt: boolean;
}

export interface Employee {
  memberId: string;
  userId: string;
  email: string;
  fullName: string;
  phone: string | null;
  active: boolean;
  permissions: Permission[];
}

export interface AdminStore {
  id: string;
  name: string;
  phone: string | null;
  address: string | null;
  status: "ACTIVE" | "SUSPENDED";
  ownerEmail: string | null;
  ownerName: string | null;
  ownerPhone: string | null;
  activePlan: string | null;
  subscriptionState: "ACTIVE" | "EXPIRED" | "NONE";
  subscriptionEnd: string | null;
  expiresAt: string | null;
  daysRemaining: number | null;
  expiring: boolean;
  pendingPlanCode: string | null;
  pendingPlanName: string | null;
  pendingAt: string | null;
  createdAt: string;
}

export interface AdminRevenue {
  totalRevenue: number;
  monthRevenue: number;
  yearRevenue: number;
  activeStores: number;
  totalStores: number;
  pendingRequests: number;
  expiredSubscriptions: number;
  perPlan: { code: string; name: string; approvedCount: number; revenue: number }[];
}

export interface AuditEntry {
  id: number;
  storeId: string | null;
  actorId: string | null;
  actorEmail?: string | null;
  actorName?: string | null;
  storeName?: string | null;
  action: string;
  entityType: string | null;
  entityId: string | null;
  details: string | null;
  ipAddress: string | null;
  createdAt: string;
}
