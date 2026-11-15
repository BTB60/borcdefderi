package az.borcdefteri.admin;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.AppProperties;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Platform admin: dashboard numbers, one store's card, stopping a subscription and wiping a store's data. */
@Service
public class AdminStoreService {

    /**
     * Business tables wiped by a data reset, children before parents. Kept: the store, its owner and employees
     * with their permissions, cash accounts (balances are sums of transactions), subscriptions and their receipts,
     * and the audit log.
     */
    static final List<String> RESET_ORDER = List.of(
            "customer_bonus_entries",
            "ledger_hidden",
            "cash_transactions",
            "financial_transactions",
            "inventory_movements",
            "stock_count_lines",
            "stock_counts",
            "purchase_items",
            "purchases",
            "sale_returns",
            "sale_items",
            "sales",
            "payments",
            "expense_attachments",
            "expenses",
            "recurring_expenses",
            "budgets",
            "expense_categories",
            "debts",
            "customer_notes",
            "customer_portal_tokens",
            "products",
            "product_categories",
            "customers",
            "companies",
            "pocket_entries",
            "store_notifications");

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final AppProperties props;

    public AdminStoreService(JdbcClient jdbc, TenantService tenant, AuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.props = props;
    }

    // ---------- overview ----------

    public Map<String, Object> overview() {
        tenant.requireSuperAdmin();
        LocalDate today = tenant.today();
        int reminderDays = props.subscription().reminderDays();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> stores = jdbc.sql("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE s.status <> 'ACTIVE') AS suspended,
                       COUNT(*) FILTER (WHERE s.created_at >= now() - interval '7 days') AS new7,
                       COUNT(*) FILTER (WHERE s.created_at >= now() - interval '30 days') AS new30,
                       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM subscriptions sb WHERE sb.store_id = s.id
                                         AND sb.status = 'ACTIVE' AND sb.end_date >= :today)) AS active,
                       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM subscriptions sb WHERE sb.store_id = s.id
                                         AND sb.status = 'ACTIVE' AND sb.end_date >= :today AND sb.end_date <= :soon)) AS expiring,
                       COUNT(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM subscriptions sb WHERE sb.store_id = s.id
                                         AND sb.status IN ('ACTIVE', 'EXPIRED'))) AS never_paid
                FROM stores s
                """).param("today", today).param("soon", today.plusDays(reminderDays))
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String k : List.of("total", "suspended", "new7", "new30", "active", "expiring", "never_paid")) {
                        m.put(k.equals("never_paid") ? "neverPaid" : k, rs.getLong(k));
                    }
                    return m;
                }).single();
        long total = (Long) stores.get("total");
        stores.put("expired", total - (Long) stores.get("active") - (Long) stores.get("neverPaid"));
        out.put("stores", stores);
        out.put("pendingRequests", jdbc.sql("SELECT COUNT(*) FROM subscriptions WHERE status = 'PENDING'")
                .query(Long.class).single());
        out.put("monthRevenue", sum("reviewed_at >= (date_trunc('month', now() AT TIME ZONE 'Asia/Baku') AT TIME ZONE 'Asia/Baku')"));
        out.put("totalRevenue", sum("1=1"));
        // what active subscriptions bring per month (price spread over their length)
        out.put("monthlyRecurring", Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(sb.amount * 30.0 / GREATEST(sb.end_date - sb.start_date + 1, 1)), 0)
                FROM subscriptions sb WHERE sb.status = 'ACTIVE' AND sb.end_date >= :today
                """).param("today", today).query(BigDecimal.class).single()));
        out.put("signups", jdbc.sql("""
                SELECT d::date AS day, (SELECT COUNT(*) FROM stores s
                                        WHERE (s.created_at AT TIME ZONE 'Asia/Baku')::date = d::date) AS n
                FROM generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day') d ORDER BY d
                """).param("from", today.minusDays(29)).param("to", today)
                .query((rs, n) -> Map.<String, Object>of("date", rs.getObject("day", LocalDate.class), "count", rs.getLong("n")))
                .list());
        out.put("revenueByMonth", jdbc.sql("""
                SELECT to_char(m, 'YYYY-MM') AS month,
                       (SELECT COALESCE(SUM(sb.amount), 0) FROM subscriptions sb
                        WHERE sb.status IN ('ACTIVE', 'EXPIRED') AND sb.reviewed_at IS NOT NULL
                          AND date_trunc('month', sb.reviewed_at AT TIME ZONE 'Asia/Baku') = m) AS amount
                FROM generate_series(date_trunc('month', CAST(:today AS timestamp)) - interval '5 months',
                                     date_trunc('month', CAST(:today AS timestamp)), interval '1 month') m
                ORDER BY m
                """).param("today", today)
                .query((rs, n) -> Map.<String, Object>of("month", rs.getString("month"), "amount", Money.of(rs.getBigDecimal("amount"))))
                .list());
        out.put("planMix", jdbc.sql("""
                SELECT p.name, COUNT(DISTINCT sb.store_id) AS stores FROM subscriptions sb
                JOIN subscription_plans p ON p.id = sb.plan_id
                WHERE sb.status = 'ACTIVE' AND sb.end_date >= :today
                GROUP BY p.name ORDER BY stores DESC
                """).param("today", today)
                .query((rs, n) -> Map.<String, Object>of("name", rs.getString("name"), "stores", rs.getLong("stores")))
                .list());
        return out;
    }

    // ---------- one store ----------

    public Map<String, Object> store(UUID storeId) {
        tenant.requireSuperAdmin();
        Map<String, Object> out = jdbc.sql("""
                SELECT s.id, s.name, s.phone, s.address, s.status, s.created_at,
                       u.id AS owner_id, u.email AS owner_email, u.full_name AS owner_name, u.phone AS owner_phone,
                       (SELECT MAX(a.created_at) FROM audit_logs a WHERE a.store_id = s.id) AS last_activity,
                       (SELECT MAX(a.created_at) FROM audit_logs a WHERE a.store_id = s.id AND a.action = 'LOGIN') AS last_login
                FROM stores s
                LEFT JOIN store_members m ON m.store_id = s.id AND m.role = 'STORE_OWNER'
                LEFT JOIN users u ON u.id = m.user_id
                WHERE s.id = :id
                """).param("id", storeId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("name", rs.getString("name"));
                    m.put("phone", rs.getString("phone"));
                    m.put("address", rs.getString("address"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    m.put("ownerId", rs.getObject("owner_id", UUID.class));
                    m.put("ownerEmail", rs.getString("owner_email"));
                    m.put("ownerName", rs.getString("owner_name"));
                    m.put("ownerPhone", rs.getString("owner_phone"));
                    m.put("lastActivity", rs.getObject("last_activity", OffsetDateTime.class));
                    m.put("lastLogin", rs.getObject("last_login", OffsetDateTime.class));
                    return m;
                }).optional().orElseThrow(() -> ApiException.notFound("Mağaza tapılmadı"));

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("customers", count("SELECT COUNT(*) FROM customers WHERE store_id = :s", storeId));
        usage.put("companies", count("SELECT COUNT(*) FROM companies WHERE store_id = :s", storeId));
        usage.put("employees", count("SELECT COUNT(*) FROM store_members WHERE store_id = :s AND role = 'EMPLOYEE' AND active", storeId));
        usage.put("products", count("SELECT COUNT(*) FROM products WHERE store_id = :s AND active", storeId));
        usage.put("openDebts", count("SELECT COUNT(*) FROM debts WHERE store_id = :s AND status <> 'PAID'", storeId));
        usage.put("payments", count("SELECT COUNT(*) FROM payments WHERE store_id = :s AND voided_at IS NULL", storeId));
        usage.put("sales", count("SELECT COUNT(*) FROM sales WHERE store_id = :s AND status = 'COMPLETED'", storeId));
        usage.put("expenses", count("SELECT COUNT(*) FROM expenses WHERE store_id = :s", storeId));
        usage.put("debtRemaining", Money.of(jdbc.sql("SELECT COALESCE(SUM(remaining_amount), 0) FROM debts WHERE store_id = :s AND debt_type = 'CUSTOMER_DEBT'")
                .param("s", storeId).query(BigDecimal.class).single()));
        usage.put("salesTotal", Money.of(jdbc.sql("SELECT COALESCE(SUM(total - returned_amount), 0) FROM sales WHERE store_id = :s AND status = 'COMPLETED'")
                .param("s", storeId).query(BigDecimal.class).single()));
        out.put("usage", usage);
        out.put("employees", jdbc.sql("""
                SELECT u.full_name, u.email, m.active, m.job_title FROM store_members m JOIN users u ON u.id = m.user_id
                WHERE m.store_id = :s AND m.role = 'EMPLOYEE' ORDER BY u.full_name
                """).param("s", storeId).query((rs, n) -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("fullName", rs.getString("full_name"));
                    e.put("email", rs.getString("email"));
                    e.put("active", rs.getBoolean("active"));
                    e.put("jobTitle", rs.getString("job_title"));
                    return e;
                }).list());
        return out;
    }

    /** Ends the store's running subscription today (e.g. a refund or a mistaken activation). */
    @Transactional
    public Map<String, Object> cancelSubscription(UUID storeId, String reason) {
        tenant.requireSuperAdmin();
        UUID subId = jdbc.sql("""
                SELECT id FROM subscriptions WHERE store_id = :s AND status = 'ACTIVE' AND end_date >= :today
                ORDER BY end_date DESC LIMIT 1
                """).param("s", storeId).param("today", tenant.today()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_SUBSCRIPTION", "Mağazanın aktiv abunəliyi yoxdur"));
        // every running period ends yesterday, so the store is closed from now on
        int n = jdbc.sql("""
                UPDATE subscriptions SET status = 'EXPIRED', end_date = LEAST(end_date, CAST(:yesterday AS date))
                WHERE store_id = :s AND status = 'ACTIVE'
                """).param("s", storeId).param("yesterday", tenant.today().minusDays(1)).update();
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        audit.log(storeId, tenant.currentUserId(), "SUBSCRIPTION_CANCELLED", "SUBSCRIPTION", subId.toString(),
                "Abunəlik admin tərəfindən dayandırıldı" + (why == null ? "" : ": " + why));
        return Map.of("id", storeId, "stopped", n);
    }

    /**
     * Wipes all business data of a store: debts, payments, customers, companies, sales, stock, cash movements,
     * expenses, pocket money. The account, employees and subscription stay. The admin must type the store's name.
     */
    @Transactional
    public Map<String, Object> resetData(UUID storeId, String confirmName) {
        tenant.requireSuperAdmin();
        String name = jdbc.sql("SELECT name FROM stores WHERE id = :id FOR UPDATE").param("id", storeId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Mağaza tapılmadı"));
        if (confirmName == null || !name.trim().toLowerCase(Locale.ROOT).equals(confirmName.trim().toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("CONFIRM_NAME_MISMATCH", "Təsdiq üçün mağazanın adını dəqiq yazın: " + name);
        }
        jdbc.sql("SELECT set_config('app.store_purge', :id, true)").param("id", storeId.toString()).query(String.class).single();
        Map<String, Object> deleted = new LinkedHashMap<>();
        long rows = 0;
        for (String table : RESET_ORDER) {
            int n = jdbc.sql("DELETE FROM " + table + " WHERE store_id = :s").param("s", storeId).update();
            deleted.put(table, n);
            rows += n;
        }
        jdbc.sql("SELECT set_config('app.store_purge', '', true)").query(String.class).single();
        audit.log(storeId, tenant.currentUserId(), "STORE_DATA_RESET", "STORE", storeId.toString(),
                "Mağazanın bütün məlumatları sıfırlandı (" + rows + " sətir)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", storeId);
        out.put("rows", rows);
        out.put("deleted", deleted);
        return out;
    }

    private long count(String sql, UUID storeId) {
        return jdbc.sql(sql).param("s", storeId).query(Long.class).single();
    }

    private BigDecimal sum(String condition) {
        return Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM subscriptions
                WHERE status IN ('ACTIVE', 'EXPIRED') AND reviewed_at IS NOT NULL AND\s""" + condition)
                .query(BigDecimal.class).single());
    }
}
