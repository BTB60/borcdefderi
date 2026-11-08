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
        tenant.requireSuperAdmin();
        String like = (q == null || q.isBlank()) ? "%" : "%" + q.trim().toLowerCase() + "%";
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
                       pend.code AS pending_plan_code, pend.name AS pending_plan_name, pend.requested_at AS pending_at
                FROM stores s
                LEFT JOIN store_members m ON m.store_id = s.id AND m.role = 'STORE_OWNER'
                LEFT JOIN users u ON u.id = m.user_id
                LEFT JOIN LATERAL (
                    SELECT p.code, p.name, sb.requested_at FROM subscriptions sb
                    JOIN subscription_plans p ON p.id = sb.plan_id
                    WHERE sb.store_id = s.id AND sb.status = 'PENDING'
                    ORDER BY sb.requested_at DESC LIMIT 1) pend ON TRUE
                WHERE LOWER(s.name) LIKE :q OR LOWER(COALESCE(u.email, '')) LIKE :q
                ORDER BY s.created_at DESC
                """)
                .param("q", like).param("today", today)
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
                    row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
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

    public Map<String, Object> revenue() {
        tenant.requireSuperAdmin();
        BigDecimal total = sumApproved("1=1");
        BigDecimal thisMonth = sumApproved("reviewed_at >= date_trunc('month', now())");
        BigDecimal thisYear = sumApproved("reviewed_at >= date_trunc('year', now())");
        long activeStores = jdbc.sql("""
                SELECT COUNT(DISTINCT store_id) FROM subscriptions WHERE status = 'ACTIVE' AND end_date >= CURRENT_DATE
                """).query(Long.class).single();
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
        return result;
    }

    public List<Map<String, Object>> users(String q) {
        tenant.requireSuperAdmin();
        String like = (q == null || q.isBlank()) ? "%" : "%" + q.trim().toLowerCase() + "%";
        return jdbc.sql("""
                SELECT u.id, u.email, u.full_name, u.phone, u.platform_role, u.enabled, u.created_at,
                       m.role AS store_role, s.name AS store_name
                FROM users u
                LEFT JOIN store_members m ON m.user_id = u.id
                LEFT JOIN stores s ON s.id = m.store_id
                WHERE LOWER(u.email) LIKE :q OR LOWER(u.full_name) LIKE :q
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
        String where = storeId == null ? "" : " WHERE store_id = :storeId";
        var q = jdbc.sql("""
                SELECT id, store_id, actor_id, action, entity_type, entity_id, details, ip_address, created_at
                FROM audit_logs""" + where + " ORDER BY created_at DESC, id DESC LIMIT " + Math.min(Math.max(limit, 1), 1000));
        if (storeId != null) {
            q = q.param("storeId", storeId);
        }
        return q.query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("storeId", rs.getObject("store_id", UUID.class));
            m.put("actorId", rs.getObject("actor_id", UUID.class));
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
