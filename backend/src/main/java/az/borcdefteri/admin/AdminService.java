package az.borcdefteri.admin;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.TenantService;
import az.borcdefteri.audit.AuditService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import az.borcdefteri.common.AppProperties;
import az.borcdefteri.subscriptions.SubscriptionService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminService {

    /** Days without any activity after which a paying store counts as "not using". */
    static final int IDLE_DAYS = 14;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final SubscriptionService subscriptions;
    private final AppProperties props;

    public AdminService(JdbcClient jdbc, TenantService tenant, AuditService audit,
                        SubscriptionService subscriptions, AppProperties props) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.subscriptions = subscriptions;
        this.props = props;
    }

    /**
     * Stores with their subscription countdown. subscriptionState: ACTIVE, EXPIRED or NONE.
     * expiring = active with at most `reminder-days` left, or already expired (shown in red in the admin panel).
     */
    public List<Map<String, Object>> stores(String q) {
        tenant.requirePlatformAdmin();
        String like = az.borcdefteri.common.Search.contains(q);
        LocalDate today = tenant.today();
        int reminderDays = props.subscription().reminderDays();
        return jdbc.sql("""
                SELECT s.id, s.name, s.phone, s.address, s.status, s.created_at,
                       u.email AS owner_email, u.full_name AS owner_name, u.phone AS owner_phone,
                       (SELECT p.name FROM subscriptions sb JOIN subscription_plans p ON p.id = sb.plan_id
                         WHERE sb.store_id = s.id AND sb.status = 'ACTIVE' AND sb.end_date >= :today
                         ORDER BY sb.end_date DESC LIMIT 1) AS active_plan,
                       (SELECT MAX(sb.end_date) FROM subscriptions sb
                         WHERE sb.store_id = s.id AND sb.status IN ('ACTIVE', 'EXPIRED')) AS subscription_end,
                       pend.code AS pending_plan_code, pend.name AS pending_plan_name, pend.requested_at AS pending_at,
                       (SELECT MAX(a.created_at) FROM audit_logs a
                         WHERE a.store_id = s.id AND a.action = 'SUBSCRIPTION_REMINDED') AS last_reminded_at,
                       (SELECT MAX(a.created_at) FROM audit_logs a
                         WHERE a.store_id = s.id AND a.action = 'LOGIN') AS last_login_at,
                       (SELECT MAX(a.created_at) FROM audit_logs a JOIN store_members am
                           ON am.store_id = a.store_id AND am.user_id = a.actor_id
                         WHERE a.store_id = s.id) AS last_activity_at,
                       (SELECT COUNT(*) FROM sales sa WHERE sa.store_id = s.id AND sa.status = 'COMPLETED'
                           AND sa.sold_on >= :monthStart) AS sales_month,
                       (SELECT COALESCE(SUM(sa.total), 0) FROM sales sa WHERE sa.store_id = s.id
                           AND sa.status = 'COMPLETED' AND sa.sold_on >= :monthStart) AS sales_month_total,
                       (SELECT sb.trial FROM subscriptions sb WHERE sb.store_id = s.id AND sb.status = 'ACTIVE'
                           AND sb.start_date <= :today AND sb.end_date >= :today
                         ORDER BY sb.start_date LIMIT 1) AS on_trial,
                       s.admin_tags,
                       (SELECT MIN(n.follow_up_on) FROM admin_store_notes n
                         WHERE n.store_id = s.id AND n.done_at IS NULL) AS next_follow_up
                FROM stores s
                LEFT JOIN store_members m ON m.store_id = s.id AND m.role = 'STORE_OWNER'
                LEFT JOIN users u ON u.id = m.user_id
                LEFT JOIN LATERAL (
                    SELECT p.code, p.name, sb.requested_at FROM subscriptions sb
                    JOIN subscription_plans p ON p.id = sb.plan_id
                    WHERE sb.store_id = s.id AND sb.status = 'PENDING'
                    ORDER BY sb.requested_at DESC LIMIT 1) pend ON TRUE
                WHERE LOWER(s.name) LIKE LOWER(:q) OR LOWER(COALESCE(u.email, '')) LIKE LOWER(:q)
                ORDER BY s.created_at DESC
                """)
                .param("q", like).param("today", today).param("monthStart", today.withDayOfMonth(1))
                .query((rs, n) -> {
                    LocalDate end = rs.getObject("subscription_end", LocalDate.class);
                    String activePlan = rs.getString("active_plan");
                    String state = activePlan != null ? "ACTIVE" : end != null ? "EXPIRED" : "NONE";
                    Long daysRemaining = activePlan != null ? ChronoUnit.DAYS.between(today, end) : null;
                    boolean expiring = "EXPIRED".equals(state) || (daysRemaining != null && daysRemaining <= reminderDays);

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("name", rs.getString("name"));
                    row.put("phone", rs.getString("phone"));
                    row.put("address", rs.getString("address"));
                    row.put("status", rs.getString("status"));
                    row.put("ownerEmail", rs.getString("owner_email"));
                    row.put("ownerName", rs.getString("owner_name"));
                    row.put("ownerPhone", rs.getString("owner_phone"));
                    row.put("activePlan", activePlan);
                    row.put("subscriptionState", state);
                    row.put("subscriptionEnd", end);
                    row.put("expiresAt", subscriptions.expiresAt(end));
                    row.put("daysRemaining", daysRemaining);
                    row.put("expiring", expiring);
                    row.put("pendingPlanCode", rs.getString("pending_plan_code"));
                    row.put("pendingPlanName", rs.getString("pending_plan_name"));
                    row.put("pendingAt", rs.getObject("pending_at", OffsetDateTime.class));
                    row.put("lastRemindedAt", rs.getObject("last_reminded_at", OffsetDateTime.class));
                    row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    OffsetDateTime lastActivity = rs.getObject("last_activity_at", OffsetDateTime.class);
                    boolean trial = rs.getBoolean("on_trial");
                    row.put("lastLoginAt", rs.getObject("last_login_at", OffsetDateTime.class));
                    row.put("lastActivityAt", lastActivity);
                    row.put("salesThisMonth", rs.getLong("sales_month"));
                    row.put("salesTotalThisMonth", Money.of(rs.getBigDecimal("sales_month_total")));
                    row.put("onTrial", trial);
                    row.put("tags", AdminCrmService.parseTags(rs.getString("admin_tags")));
                    row.put("nextFollowUp", rs.getObject("next_follow_up", LocalDate.class));
                    // "Ödəyir, amma istifadə etmir": a paid, active package and nobody of the store did anything
                    // for 14 days (and the store is older than that)
                    OffsetDateTime idleSince = OffsetDateTime.now().minusDays(IDLE_DAYS);
                    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
                    row.put("idle", "ACTIVE".equals(state) && !trial && created != null && created.isBefore(idleSince)
                            && (lastActivity == null || lastActivity.isBefore(idleSince)));
                    return row;
                })
                .list();
    }

    @Transactional
    public Map<String, Object> setStoreStatus(UUID storeId, String status) {
        tenant.requireSuperAdmin();
        int updated = jdbc.sql("UPDATE stores SET status = :status WHERE id = :id")
                .param("status", status).param("id", storeId).update();
        if (updated == 0) {
            throw ApiException.notFound("Mağaza tapılmadı");
        }
        audit.log(storeId, tenant.currentUserId(), "SUSPENDED".equals(status) ? "STORE_SUSPENDED" : "STORE_ACTIVATED",
                "STORE", storeId.toString(), "Status: " + status);
        return Map.of("id", storeId, "status", status);
    }

    /**
     * Records that the admin reminded the store about its subscription (WhatsApp, call or e-mail), so the
     * "Bitən abunəliklər" list shows when the store was last contacted and nobody is messaged twice.
     */
    @Transactional
    public Map<String, Object> remind(UUID storeId, String channel) {
        tenant.requirePlatformAdmin();
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM stores WHERE id = :id").param("id", storeId)
                .query(Long.class).single() > 0;
        if (!exists) {
            throw ApiException.notFound("Mağaza tapılmadı");
        }
        audit.log(storeId, tenant.currentUserId(), "SUBSCRIPTION_REMINDED", "STORE", storeId.toString(),
                "Abunəlik xatırlatması: " + channel);
        OffsetDateTime at = jdbc.sql("""
                SELECT MAX(created_at) FROM audit_logs WHERE store_id = :id AND action = 'SUBSCRIPTION_REMINDED'
                """).param("id", storeId).query(OffsetDateTime.class).single();
        return Map.of("id", storeId, "channel", channel, "remindedAt", at);
    }

    public Map<String, Object> revenue() {
        tenant.requireSuperAdmin();
        BigDecimal total = sumApproved("1=1");
        // Month/year boundaries in the business time zone (Asia/Baku), not the database server's zone.
        BigDecimal thisMonth = sumApproved("reviewed_at >= (date_trunc('month', now() AT TIME ZONE 'Asia/Baku') AT TIME ZONE 'Asia/Baku')");
        BigDecimal thisYear = sumApproved("reviewed_at >= (date_trunc('year', now() AT TIME ZONE 'Asia/Baku') AT TIME ZONE 'Asia/Baku')");
        long activeStores = jdbc.sql("""
                SELECT COUNT(DISTINCT store_id) FROM subscriptions WHERE status = 'ACTIVE' AND end_date >= :today
                """).param("today", tenant.today()).query(Long.class).single();
        long totalStores = jdbc.sql("SELECT COUNT(*) FROM stores").query(Long.class).single();
        long pending = jdbc.sql("SELECT COUNT(*) FROM subscriptions WHERE status = 'PENDING'").query(Long.class).single();
        long expired = jdbc.sql("SELECT COUNT(*) FROM subscriptions WHERE status = 'EXPIRED'").query(Long.class).single();
        List<Map<String, Object>> perPlan = jdbc.sql("""
                SELECT p.code, p.name, COUNT(s.id) AS approved_count,
                       COALESCE(SUM(s.amount), 0) AS revenue
                FROM subscription_plans p
                LEFT JOIN subscriptions s ON s.plan_id = p.id AND s.status IN ('ACTIVE', 'EXPIRED')
                GROUP BY p.code, p.name, p.price ORDER BY p.price
                """).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("approvedCount", rs.getLong("approved_count"));
                    m.put("revenue", Money.of(rs.getBigDecimal("revenue")));
                    return m;
                }).list();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRevenue", total);
        result.put("monthRevenue", thisMonth);
        result.put("yearRevenue", thisYear);
        result.put("activeStores", activeStores);
        result.put("totalStores", totalStores);
        result.put("pendingRequests", pending);
        result.put("expiredSubscriptions", expired);
        result.put("perPlan", perPlan);
        result.put("monthly", monthly());
        result.put("trialStores", jdbc.sql("""
                SELECT COUNT(DISTINCT store_id) FROM subscriptions
                WHERE status = 'ACTIVE' AND trial AND start_date <= :today AND end_date >= :today
                """).param("today", tenant.today()).query(Long.class).single());
        return result;
    }

    /**
     * The last 12 months: subscription income (approved payments, by approval date), paying stores (a paid package
     * active at some point of the month), new paying stores and churn: stores paying at the start of the month whose
     * paid access ended during the month with no later paid period (in % of the stores paying at the start).
     */
    private List<Map<String, Object>> monthly() {
        LocalDate today = tenant.today();
        LocalDate first = today.withDayOfMonth(1).minusMonths(11);
        return jdbc.sql("""
                WITH months AS (
                    SELECT CAST(m AS date) AS ms,
                           LEAST(CAST(m + INTERVAL '1 month' - INTERVAL '1 day' AS date), CAST(:today AS date)) AS me
                    FROM generate_series(CAST(:first AS date), CAST(:today AS date), INTERVAL '1 month') m
                ), paid AS (
                    SELECT store_id, start_date, end_date FROM subscriptions
                    WHERE status IN ('ACTIVE', 'EXPIRED') AND NOT trial AND amount > 0 AND start_date IS NOT NULL
                ), last_end AS (
                    SELECT store_id, MAX(end_date) AS end_date FROM paid GROUP BY store_id
                )
                SELECT mo.ms,
                       (SELECT COALESCE(SUM(s.amount), 0) FROM subscriptions s
                         WHERE s.status IN ('ACTIVE', 'EXPIRED') AND s.reviewed_at IS NOT NULL
                           AND CAST(s.reviewed_at AT TIME ZONE 'Asia/Baku' AS date) BETWEEN mo.ms AND mo.me) AS revenue,
                       (SELECT COUNT(DISTINCT p.store_id) FROM paid p
                         WHERE p.start_date <= mo.me AND p.end_date >= mo.ms) AS paying,
                       (SELECT COUNT(DISTINCT p.store_id) FROM paid p WHERE p.start_date BETWEEN mo.ms AND mo.me
                           AND NOT EXISTS (SELECT 1 FROM paid q WHERE q.store_id = p.store_id AND q.start_date < mo.ms)) AS new_paying,
                       (SELECT COUNT(DISTINCT p.store_id) FROM paid p
                         WHERE p.start_date < mo.ms AND p.end_date >= mo.ms) AS at_start,
                       (SELECT COUNT(DISTINCT p.store_id) FROM paid p JOIN last_end le ON le.store_id = p.store_id
                         WHERE p.start_date < mo.ms AND p.end_date >= mo.ms AND le.end_date < mo.me) AS churned
                FROM months mo ORDER BY mo.ms
                """)
                .param("first", first).param("today", today)
                .query((rs, n) -> {
                    long atStart = rs.getLong("at_start");
                    long churned = rs.getLong("churned");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("month", rs.getObject("ms", LocalDate.class).toString().substring(0, 7));
                    m.put("revenue", Money.of(rs.getBigDecimal("revenue")));
                    m.put("payingStores", rs.getLong("paying"));
                    m.put("newPayingStores", rs.getLong("new_paying"));
                    m.put("payingAtStart", atStart);
                    m.put("churnedStores", churned);
                    m.put("churnRate", atStart == 0 ? BigDecimal.ZERO
                            : BigDecimal.valueOf(churned * 100.0 / atStart).setScale(1, java.math.RoundingMode.HALF_UP));
                    return m;
                }).list();
    }

    public List<Map<String, Object>> users(String q) {
        tenant.requireSuperAdmin();
        String like = az.borcdefteri.common.Search.contains(q);
        return jdbc.sql("""
                SELECT u.id, u.email, u.full_name, u.phone, u.platform_role, u.enabled, u.created_at,
                       m.role AS store_role, s.name AS store_name
                FROM users u
                LEFT JOIN store_members m ON m.user_id = u.id
                LEFT JOIN stores s ON s.id = m.store_id
                WHERE LOWER(u.email) LIKE LOWER(:q) OR LOWER(u.full_name) LIKE LOWER(:q)
                ORDER BY u.created_at DESC LIMIT 500
                """).param("q", like).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("email", rs.getString("email"));
                    m.put("fullName", rs.getString("full_name"));
                    m.put("phone", rs.getString("phone"));
                    m.put("platformRole", rs.getString("platform_role"));
                    m.put("enabled", rs.getBoolean("enabled"));
                    m.put("storeRole", rs.getString("store_role"));
                    m.put("storeName", rs.getString("store_name"));
                    return m;
                }).list();
    }

    public List<Map<String, Object>> auditLogs(UUID storeId, int limit) {
        tenant.requireSuperAdmin();
        String where = storeId == null ? "" : " WHERE a.store_id = :storeId";
        var q = jdbc.sql("""
                SELECT a.id, a.store_id, a.actor_id, a.action, a.entity_type, a.entity_id, a.details, a.ip_address, a.created_at,
                       u.email AS actor_email, u.full_name AS actor_name, s.name AS store_name
                FROM audit_logs a
                LEFT JOIN users u ON u.id = a.actor_id
                LEFT JOIN stores s ON s.id = a.store_id""" + where
                + " ORDER BY a.created_at DESC, a.id DESC LIMIT " + Math.min(Math.max(limit, 1), 1000));
        if (storeId != null) {
            q = q.param("storeId", storeId);
        }
        return q.query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("storeId", rs.getObject("store_id", UUID.class));
            m.put("actorId", rs.getObject("actor_id", UUID.class));
            m.put("actorEmail", rs.getString("actor_email"));
            m.put("actorName", rs.getString("actor_name"));
            m.put("storeName", rs.getString("store_name"));
            m.put("action", rs.getString("action"));
            m.put("entityType", rs.getString("entity_type"));
            m.put("entityId", rs.getString("entity_id"));
            m.put("details", rs.getString("details"));
            m.put("ipAddress", rs.getString("ip_address"));
            m.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class));
            return m;
        }).list();
    }

    private BigDecimal sumApproved(String condition) {
        BigDecimal v = jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM subscriptions
                WHERE status IN ('ACTIVE', 'EXPIRED') AND reviewed_at IS NOT NULL AND\s""" + condition)
                .query(BigDecimal.class).single();
        return Money.of(v);
    }
}
