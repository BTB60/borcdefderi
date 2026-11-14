export type DebtType = "CUSTOMER_DEBT" | "COMPANY_RECEIVABLE" | "COMPANY_PAYABLE";
export type DebtStatus = "OPEN" | "PARTIAL" | "PAID";
export type PaymentMethod = "CASH" | "CARD" | "TRANSFER" | "OTHER";
export type Permission = "CUSTOMER_DEBTS" | "COMPANY_RECEIVABLES" | "COMPANY_PAYABLES" | "REPORTS" | "CASH" | "EXPENSES" | "INVENTORY" | "SALES";

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

/** Payment & contact settings managed by the SUPER_ADMIN (card details only when signed in). */
export interface Contact {
  /** WhatsApp number as entered. */
  phone: string | null;
  /** WhatsApp number in international digits for wa.me links. */
  whatsapp: string | null;
  m10Account: string | null;
  m10Recipient: string | null;
  /** M10 payment instructions. */
  paymentNote: string | null;
  cardNumber: string | null;
  cardHolder: string | null;
  cardBank: string | null;
  /** Card payment instructions. */
  cardNote: string | null;
  extraPhone: string | null;
  extraContact: string | null;
  whatsappTemplate: string | null;
  paymentInstructions: string | null;
  afterPaymentText: string | null;
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
  /** Logged in with a temporary password: a new password must be set first. */
  mustChangePassword?: boolean;
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
  /** REVERSAL: a payment taken back (cancelled, or the old version of a corrected payment) — the debt grows again. */
  type: "DEBT_CREATED" | "DEBT_ADJUSTED" | "DEBT_INCREASED" | "PAYMENT_CASH" | "PAYMENT_CARD" | "PAYMENT_TRANSFER" | "PAYMENT_OTHER" | "REVERSAL";
  amount: number;
  balanceAfter: number;
  paymentId: string | null;
  actorId: string;
  actorName: string;
  createdAt: string;
  entryDate?: string | null;
  receiptNumber?: string | null;
  note?: string | null;
  /** This entry cancels another one (a "Borc artırıldı" cancelled by the owner). */
  reversesEntryId?: string | null;
  /** This entry was cancelled by a later one. */
  cancelled?: boolean;
}

/** GET /api/customers/{id}/profile and /api/companies/{id}/profile */
export interface PartyProfile {
  party: Party;
  totals: PartyTotals;
  debts: Debt[];
  payments: Payment[];
  /** Company only: what the company owes us / what we owe the company — never added together. */
  receivable?: PartyTotals | null;
  payable?: PartyTotals | null;
}

export interface PartyTotals { totalDebt: number; paid: number; remaining: number; debtCount: number; openDebtCount: number }

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
  /** Set when the store owner cancelled (ləğv) the payment; it no longer counts anywhere. */
  voidedAt?: string | null;
  voidReason?: string | null;
  /** Last correction by the store owner. */
  editedAt?: string | null;
}

export interface Party {
  id: string;
  name: string;
  phone: string | null;
  note: string | null;
  createdAt: string;
  openBalance: number;
  openDebtCount: number;
  /** "HP-000001" for customers, null for companies */
  code?: string | null;
  lastContactAt?: string | null;
  lastActivityAt?: string | null;
  overdueDebts?: number;
}

// ----- HesabPro 2.0 -----

export interface Figure { count: number; sum: number }

export interface DashboardOverview {
  customerDebts: Figure;
  companyReceivables: Figure;
  companyPayables: Figure;
  activeCustomers: Figure;
  todayDebts: Figure;
  activeDebts: Figure;
  closedDebts: Figure;
  overdueDebts: Figure;
  paymentsOut: Figure;
  paymentsIn: Figure;
  today: string;
}

export type DashboardListKind =
  | "customer-debts" | "company-receivables" | "company-payables" | "active-customers" | "today-debts"
  | "active-debts" | "closed-debts" | "overdue-debts" | "payments-out" | "payments-in";

export interface ListPage<T> { kind: string; items: T[]; total: number; sum: number; page: number; size: number }

export interface PartyRow {
  partyId: string; partyKind: "CUSTOMER" | "COMPANY"; debtType: DebtType; name: string; code: string | null; phone: string | null;
  totalDebt: number; paid: number; remaining: number; lastActivityAt: string | null; lastPaymentAt: string | null; overdueDebts: number;
}

export interface DebtRow {
  debtId: string; debtType: DebtType; partyId: string; partyKind: "CUSTOMER" | "COMPANY"; name: string; code: string | null;
  phone: string | null; debtDate: string; dueDate: string | null; overdueDays: number; amount: number; paid: number;
  remaining: number; status: DebtStatus; lastPaymentAt: string | null; receiptNumber: string | null; createdByName: string | null;
}

export interface EntryRow {
  entryId: string; debtId: string; debtType: DebtType; partyId: string; partyKind: "CUSTOMER" | "COMPANY"; name: string;
  code: string | null; phone: string | null; createdAt: string; entryDate: string; receiptNumber: string | null; note: string | null;
  amount: number; debtPaid: number; debtRemaining: number; actorName: string;
}

export interface PaymentRow {
  paymentId: string; debtId: string; debtType: DebtType; partyId: string; partyKind: "CUSTOMER" | "COMPANY"; name: string;
  code: string | null; phone: string | null; paidAt: string; amount: number; paymentMethod: PaymentMethod;
  recordedByName: string; description: string | null;
}

export interface HistoryEntry {
  id: string; debtId: string; debtType: DebtType; type: LedgerEntry["type"]; amount: number; balanceAfter: number;
  paymentId: string | null; paymentMethod: PaymentMethod | null; entryDate: string | null; receiptNumber: string | null;
  note: string | null; actorName: string; createdAt: string;
}

export interface CustomerNote { id: string; body: string; authorName: string; createdAt: string }

export interface PortalLink {
  id: string; createdAt: string; expiresAt: string | null; revokedAt: string | null; lastUsedAt: string | null;
  createdByName: string; active: boolean;
}

export interface PortalView {
  storeName: string; storePhone: string | null; storeAddress: string | null; customerName: string; customerCode: string;
  totalDebt: number; paid: number; remaining: number;
  debts: { debtDate: string; dueDate: string | null; amount: number; paid: number; remaining: number; status: DebtStatus; overdueDays: number }[];
  history: { date: string | null; type: LedgerEntry["type"]; amount: number; balanceAfter: number; paymentMethod: PaymentMethod | null; receiptNumber: string | null; note: string | null }[];
  expiresAt: string | null; generatedAt: string;
}

export interface Activity {
  from: string; to: string;
  employees: { userId: string; fullName: string; role: string; debtEntries: number; debtAmount: number; paymentsReceived: number; paymentsAmount: number; actions: number }[];
  entries: { id: number; at: string; actorId: string | null; actorName: string | null; action: string; entityType: string | null; entityId: string | null; details: string | null }[];
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

export type PeriodCode = "MONTHLY" | "QUARTERLY" | "SEMI_ANNUAL" | "YEARLY" | "CUSTOM";

/** One purchasable period of a package: calendar months, or a number of days for CUSTOM. */
export interface PeriodOption {
  period: PeriodCode;
  months: number | null;
  days: number | null;
  price: number;
}

export interface Plan {
  id: string;
  code: string;
  name: string;
  /** Price of the first offered period (kept for older screens). */
  price: number;
  durationMonths: number;
  active: boolean;
  description: string | null;
  features: string[];
  maxEmployees: number | null;
  maxCustomers: number | null;
  sortOrder: number;
  recommended: boolean;
  archived: boolean;
  options: PeriodOption[];
  primaryColor: string;
  secondaryColor: string;
  backgroundColor: string;
  textColor: string;
  buttonColor: string;
  /** A store already requested or paid for it: deleting archives it instead. */
  inUse: boolean;
}

export interface SubscriptionRecord {
  id: string;
  storeId: string;
  storeName: string;
  planCode: string;
  planName: string;
  status: "PENDING" | "ACTIVE" | "EXPIRED" | "REJECTED";
  /** Payment request status: ACTIVE and EXPIRED were approved. */
  requestStatus: "PENDING" | "APPROVED" | "REJECTED";
  amount: number;
  startDate: string | null;
  endDate: string | null;
  payerReference: string | null;
  rejectReason: string | null;
  requestedAt: string;
  reviewedAt: string | null;
  hasReceipt: boolean;
  periodCode: PeriodCode | null;
  periodMonths: number | null;
  periodDays: number | null;
  paymentMethod: "M10" | "CARD" | null;
  payerName: string | null;
  declaredAmount: number | null;
  paymentDate: string | null;
  note: string | null;
  ownerName: string | null;
  ownerEmail: string | null;
  ownerPhone: string | null;
  storePhone: string | null;
  reviewedByName: string | null;
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
  /** When the admin last sent a renewal reminder (WhatsApp / call / e-mail); absent when never. */
  lastRemindedAt?: string | null;
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

// ----- ERP: cash, expenses, budgets -----

export type CashKind = "CASH" | "BANK" | "CARD";

export interface CashAccount { id: string; name: string; kind: CashKind; isDefault: boolean; active: boolean; balance: number; createdAt: string }

export type MovementKind =
  | "OPENING" | "DEPOSIT" | "WITHDRAWAL" | "TRANSFER_IN" | "TRANSFER_OUT" | "DEBT_COLLECTION" | "SUPPLIER_PAYMENT"
  | "EXPENSE" | "EXPENSE_REVERSAL" | "SALE" | "SALE_REFUND" | "COUNT_ADJUSTMENT" | "PAYMENT_REVERSAL";

export interface CashMovement {
  id: string; accountId: string; accountName: string; direction: "IN" | "OUT"; amount: number; kind: MovementKind;
  occurredOn: string; transferId: string | null; paymentId: string | null; expenseId: string | null; note: string | null;
  actorName: string; createdAt: string;
}

export interface DayReport {
  date: string; totalOpening: number; totalIn: number; totalOut: number; totalClosing: number;
  accounts: { accountId: string; accountName: string; kind: CashKind; opening: number; in: number; out: number; closing: number; byKind: Record<string, number> }[];
}

export interface FinanceOverview {
  today: string; cashBalance?: number; bankBalance?: number; cardBalance?: number; todayIn?: number; todayOut?: number;
  monthIn?: number; monthOut?: number; todayExpenses?: number; monthExpenses?: number; previousMonthExpenses?: number;
  budgetsNear?: number; budgetsOver?: number; recurringDue?: number; recurringDueAmount?: number;
}

export interface ExpenseCategory { id: string; code: string | null; name: string; custom: boolean }

export interface Expense {
  id: string; title: string; categoryId: string; categoryName: string; amount: number; expenseDate: string;
  paymentMethod: PaymentMethod; accountId: string; accountName: string; payee: string | null; note: string | null;
  recurringId: string | null; periodDate: string | null; createdByName: string; createdAt: string; version: number; attachmentCount: number;
}

export interface ExpensePage { items: Expense[]; total: number; sum: number; page: number; size: number }

export interface ExpenseSummary { from: string; to: string; groupBy: string; total: number; count: number; rows: { key: string; label: string; count: number; total: number }[] }

export interface RecurringExpense {
  id: string; title: string; categoryId: string; categoryName: string; amount: number; frequency: "DAILY" | "WEEKLY" | "MONTHLY" | "YEARLY";
  startDate: string; endDate: string | null; nextDueDate: string; accountId: string | null; payee: string | null; active: boolean; due: boolean; version: number;
}

export interface Budget { id: string; categoryId: string; categoryName: string; month: string; limit: number; actual: number; remaining: number; percent: number; state: "OK" | "NEAR" | "OVER" }

// ----- ERP: inventory, sales, profit -----

export interface ProductCategory { id: string; name: string; productCount: number }

export interface Product {
  id: string; code: string; barcode: string | null; name: string; categoryId: string | null; categoryName: string | null;
  supplierId: string | null; supplierName: string | null; unit: string; purchasePrice: number; salePrice: number;
  stockQty: number; minStock: number; active: boolean; lowStock: boolean; outOfStock: boolean; hasImage: boolean;
  stockCostValue: number; stockSaleValue: number; version: number;
  /** Price before a discount; shown struck through on labels. */
  oldPrice?: number | null;
}

export interface StockMovement {
  id: string; productId: string; productName: string; kind: "RECEIPT" | "ISSUE" | "SALE" | "SALE_RETURN" | "ADJUSTMENT" | "COUNT";
  quantity: number; unitCost: number | null; balanceAfter: number; saleId: string | null; note: string | null; actorName: string; createdAt: string;
}

export interface StockCountTotals {
  totalItems: number; countedItems: number; matchedItems: number; surplusItems: number; shortageItems: number;
  surplusQty: number; shortageQty: number; surplusValue: number; shortageValue: number; netValue: number;
}

export interface StockCount {
  id: string; number: string; status: "IN_PROGRESS" | "COMPLETED"; categoryId: string | null; categoryName: string | null; note: string | null;
  createdByName: string; createdAt: string; completedByName: string | null; completedAt: string | null; totals: StockCountTotals; version: number;
}

export interface StockCountLine {
  productId: string; code: string; barcode: string | null; name: string; unit: string; hasImage: boolean;
  systemQty: number; countedQty: number | null; difference: number | null; unitCost: number; differenceValue: number | null;
  countedByName: string | null; countedAt: string | null;
}

export interface StockCountDetail { count: StockCount; lines: StockCountLine[] }

export interface InventorySummary { productCount: number; activeCount: number; lowStockCount: number; outOfStockCount: number; costValue: number; saleValue: number }

export interface SaleItem { id: string; productId: string; name: string; quantity: number; unitPrice: number; discount: number; lineTotal: number; unitCost: number; returnedQty: number }

export interface Sale {
  id: string; number: string; customerId: string | null; customerName: string | null; customerCode: string | null; status: "COMPLETED" | "CANCELLED";
  subtotal: number; discount: number; total: number; paidCash: number; paidCard: number; creditAmount: number; returnedAmount: number;
  costTotal: number; debtId: string | null; soldOn: string; note: string | null; createdByName: string; createdAt: string; items: SaleItem[];
  returns: { id: string; amount: number; debtReduced: number; refunded: number; returnedOn: string; reason: string | null; createdByName: string; createdAt: string }[];
}

export interface SalePage { items: Sale[]; total: number; sum: number; page: number; size: number }

export interface Pnl {
  from: string; to: string; sales: number; returns: number; netSales: number; costOfGoods: number; grossProfit: number; expenses: number;
  netResult: number; saleCount: number; salesPaidNow: number; salesOnCredit: number; debtCollections: number; supplierPayments: number;
  previous: { from: string; to: string; netSales: number; expenses: number; netResult: number };
}

export interface MonthPoint { month: string; netSales: number; costOfGoods: number; expenses: number; netResult: number }
