package az.borcdefteri.subscriptions;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.AppProperties;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Manual subscription workflow: PENDING (receipt uploaded) -> ACTIVE (approved) | REJECTED; ACTIVE -> EXPIRED. */
@Service
public class SubscriptionService {

    public record PlanView(UUID id, String code, String name, BigDecimal price, int durationMonths, boolean active) {}

    public record SubscriptionView(UUID id, UUID storeId, String storeName, String planCode, String planName,
                                   String status, BigDecimal amount, LocalDate startDate, LocalDate endDate,
                                   String payerReference, String rejectReason, OffsetDateTime requestedAt,
                                   OffsetDateTime reviewedAt, boolean hasReceipt) {}

    public record ReceiptFile(Path path, String contentType, String fileName) {}

    private static final String SELECT_SUB = """
            SELECT s.id, s.store_id, st.name AS store_name, p.code AS plan_code, p.name AS plan_name,
                   s.status, s.amount, s.start_date, s.end_date, s.payer_reference, s.reject_reason,
                   s.requested_at, s.reviewed_at, p.duration_months, p.price,
                   EXISTS (SELECT 1 FROM subscription_receipts r WHERE r.subscription_id = s.id) AS has_receipt
            FROM subscriptions s
            JOIN subscription_plans p ON p.id = s.plan_id
            JOIN stores st ON st.id = s.store_id
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final ReceiptStorage receipts;
    private final AuditService audit;
    private final AppProperties props;

    public SubscriptionService(JdbcClient jdbc, TenantService tenant, ReceiptStorage receipts,
                               AuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.receipts = receipts;
        this.audit = audit;
        this.props = props;
    }

    // ---------- plans ----------

    public List<PlanView> activePlans() {
        return jdbc.sql("SELECT id, code, name, price, duration_months, active FROM subscription_plans WHERE active ORDER BY price")
                .query((rs, n) -> new PlanView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), Money.of(rs.getBigDecimal("price")), rs.getInt("duration_months"),
                        rs.getBoolean("active")))
                .list();
    }

    public List<PlanView> allPlans() {
        return jdbc.sql("SELECT id, code, name, price, duration_months, active FROM subscription_plans ORDER BY price")
                .query((rs, n) -> new PlanView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), Money.of(rs.getBigDecimal("price")), rs.getInt("duration_months"),
                        rs.getBoolean("active")))
                .list();
    }

    @Transactional
    public PlanView updatePlan(UUID planId, String name, BigDecimal price, int durationMonths, boolean active) {
        tenant.requireSuperAdmin();
        int updated = jdbc.sql("""
                UPDATE subscription_plans SET name = :name, price = :price, duration_months = :months, active = :active
                WHERE id = :id
                """)
                .param("name", name).param("price", Money.of(price)).param("months", durationMonths)
                .param("active", active).param("id", planId)
                .update();
        if (updated == 0) {
            throw ApiException.notFound("Plan tapılmadı");
        }
        audit.log(null, tenant.currentUserId(), "PLAN_UPDATED", "PLAN", planId.toString(),
                "price=" + Money.of(price) + ", months=" + durationMonths + ", active=" + active);
        return allPlans().stream().filter(p -> p.id().equals(planId)).findFirst().orElseThrow();
    }

    // ---------- store side ----------

    public SubscriptionSummary summaryFor(UUID storeId) {
        LocalDate today = tenant.today();
        int reminderDays = props.subscription().reminderDays();
        SubscriptionView active = jdbc.sql(SELECT_SUB + """
                WHERE s.store_id = :storeId AND s.status = 'ACTIVE' AND s.end_date >= :today
                ORDER BY s.end_date DESC LIMIT 1
                """)
                .param("storeId", storeId).param("today", today)
                .query(this::mapSub).optional().orElse(null);
        if (active != null) {
            long days = ChronoUnit.DAYS.between(today, active.endDate());
            return new SubscriptionSummary(true, "ACTIVE", active.planCode(), active.planName(),
                    active.startDate(), active.endDate(), days, days <= reminderDays, expiresAt(active.endDate()));
        }
        SubscriptionView latest = jdbc.sql(SELECT_SUB + """
                WHERE s.store_id = :storeId ORDER BY s.requested_at DESC LIMIT 1
                """)
                .param("storeId", storeId)
                .query(this::mapSub).optional().orElse(null);
        if (latest == null) {
            return new SubscriptionSummary(false, "NONE", null, null, null, null, 0, false, null);
        }
        String status = "ACTIVE".equals(latest.status()) ? "EXPIRED" : latest.status();
        return new SubscriptionSummary(false, status, latest.planCode(), latest.planName(),
                latest.startDate(), latest.endDate(), 0, false, expiresAt(latest.endDate()));
    }

    /** Access ends at the start of the day after the (inclusive) end date, in the business time zone. */
    public OffsetDateTime expiresAt(LocalDate endDate) {
        if (endDate == null) {
            return null;
        }
        return endDate.plusDays(1).atStartOfDay(ZoneId.of(props.timezone())).toOffsetDateTime();
    }

    public List<SubscriptionView> historyFor(UUID storeId) {
        return jdbc.sql(SELECT_SUB + " WHERE s.store_id = :storeId ORDER BY s.requested_at DESC")
                .param("storeId", storeId).query(this::mapSub).list();
    }

    /** Owner submits a plan + payment reference + receipt. Amount is always taken from the plan, never from the client. */
    @Transactional
    public SubscriptionView submit(String planCode, String payerReference, MultipartFile receipt) {
        CurrentMember member = tenant.requireMember();
        if (!member.isOwner()) {
            throw ApiException.forbidden("OWNER_ONLY", "Abunəliyi yalnız mağaza sahibi göndərə bilər");
        }
        boolean pending = jdbc.sql("SELECT COUNT(*) FROM subscriptions WHERE store_id = :s AND status = 'PENDING'")
                .param("s", member.storeId()).query(Long.class).single() > 0;
        if (pending) {
            throw ApiException.conflict("SUBSCRIPTION_PENDING", "Artıq təsdiq gözləyən abunəliyiniz var");
        }
        PlanView plan = activePlans().stream().filter(p -> p.code().equals(planCode)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("INVALID_PLAN", "Seçilmiş plan mövcud deyil"));

        ReceiptStorage.Stored stored = receipts.store(receipt);
        UUID subId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO subscriptions (id, store_id, plan_id, status, amount, payer_reference)
                VALUES (:id, :storeId, :planId, 'PENDING', :amount, :ref)
                """)
                .param("id", subId).param("storeId", member.storeId())
                .param("planId", plan.id()).param("amount", plan.price())
                .param("ref", blankToNull(payerReference))
                .update();
        jdbc.sql("""
                INSERT INTO subscription_receipts (id, subscription_id, store_id, storage_name, original_name, content_type, size_bytes)
                VALUES (:id, :subId, :storeId, :name, :orig, :type, :size)
                """)
                .param("id", UUID.randomUUID()).param("subId", subId).param("storeId", member.storeId())
                .param("name", stored.storageName()).param("orig", stored.originalName())
                .param("type", stored.contentType()).param("size", stored.sizeBytes())
                .update();
        audit.log(member.storeId(), member.userId(), "SUBSCRIPTION_SUBMITTED", "SUBSCRIPTION", subId.toString(),
                "Plan: " + plan.code() + ", məbləğ: " + plan.price());
        return getView(subId);
    }

    /**
     * "Abunə ol": the owner picks a package. Creates a PENDING request without a receipt (the receipt is sent to the
     * admin directly, e.g. by WhatsApp). If a request is already pending, its package is changed instead.
     */
    @Transactional
    public SubscriptionView request(String planCode) {
        CurrentMember member = tenant.requireMember();
        if (!member.isOwner()) {
            throw ApiException.forbidden("OWNER_ONLY", "Abunəliyi yalnız mağaza sahibi göndərə bilər");
        }
        PlanView plan = activePlans().stream().filter(p -> p.code().equals(planCode)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("INVALID_PLAN", "Seçilmiş plan mövcud deyil"));
        lockStore(member.storeId()); // serialises concurrent requests of the same store
        UUID pendingId = jdbc.sql("SELECT id FROM subscriptions WHERE store_id = :s AND status = 'PENDING' ORDER BY requested_at DESC LIMIT 1")
                .param("s", member.storeId()).query(UUID.class).optional().orElse(null);
        UUID subId;
        if (pendingId != null) {
            jdbc.sql("UPDATE subscriptions SET plan_id = :planId, amount = :amount, requested_at = now() WHERE id = :id")
                    .param("planId", plan.id()).param("amount", plan.price()).param("id", pendingId).update();
            subId = pendingId;
        } else {
            subId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO subscriptions (id, store_id, plan_id, status, amount)
                    VALUES (:id, :storeId, :planId, 'PENDING', :amount)
                    """)
                    .param("id", subId).param("storeId", member.storeId())
                    .param("planId", plan.id()).param("amount", plan.price())
                    .update();
        }
        audit.log(member.storeId(), member.userId(), "SUBSCRIPTION_REQUESTED", "SUBSCRIPTION", subId.toString(),
                "Plan: " + plan.code() + ", məbləğ: " + plan.price());
        return getView(subId);
    }

    public ReceiptFile receiptFor(UUID subscriptionId) {
        boolean admin = tenant.isSuperAdmin();
        UUID callerStoreId = admin ? null : tenant.requireMember().storeId();
        record R(String storage, String type, String original, UUID storeId) {}
        R row = jdbc.sql("""
                SELECT r.storage_name, r.content_type, r.original_name, r.store_id
                FROM subscription_receipts r WHERE r.subscription_id = :id
                """).param("id", subscriptionId)
                .query((rs, n) -> new R(rs.getString("storage_name"), rs.getString("content_type"),
                        rs.getString("original_name"), rs.getObject("store_id", UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Qəbz tapılmadı"));
        if (!admin && !row.storeId().equals(callerStoreId)) {
            throw ApiException.notFound("Qəbz tapılmadı");
        }
        String name = row.original() != null ? row.original() : "qebz";
        return new ReceiptFile(receipts.resolve(row.storage()), row.type(), name);
    }

    // ---------- super admin side ----------

    public List<SubscriptionView> listByStatus(String status) {
        String where = (status == null || status.isBlank()) ? "" : " WHERE s.status = :status";
        var q = jdbc.sql(SELECT_SUB + where + " ORDER BY s.requested_at DESC");
        if (where.length() > 0) {
            q = q.param("status", status.toUpperCase());
        }
        return q.query(this::mapSub).list();
    }

    @Transactional
    public SubscriptionView approve(UUID subscriptionId) {
        tenant.requireSuperAdmin();
        SubscriptionView sub = getView(subscriptionId);
        if (!"PENDING".equals(sub.status())) {
            throw ApiException.conflict("INVALID_STATE", "Yalnız gözləyən abunəlik təsdiqlənə bilər");
        }
        int months = jdbc.sql("SELECT duration_months FROM subscription_plans WHERE code = :code")
                .param("code", sub.planCode()).query(Integer.class).single();
        LocalDate[] period = periodFor(sub.storeId(), months);
        LocalDate start = period[0];
        LocalDate end = period[1];

        jdbc.sql("""
                UPDATE subscriptions SET status = 'ACTIVE', start_date = :start, end_date = :end,
                       reviewed_at = now(), reviewed_by = :admin
                WHERE id = :id
                """)
                .param("start", start).param("end", end).param("admin", tenant.currentUserId())
                .param("id", subscriptionId).update();
        audit.log(sub.storeId(), tenant.currentUserId(), "SUBSCRIPTION_APPROVED", "SUBSCRIPTION",
                subscriptionId.toString(), "Aktiv: " + start + " - " + end);
        return getView(subscriptionId);
    }

    /**
     * Admin activates a store directly after receiving the payment receipt (phone / WhatsApp).
     * A pending request of the store is turned into the active subscription; otherwise a new one is created.
     */
    @Transactional
    public SubscriptionView adminActivate(UUID storeId, String planCode) {
        tenant.requireSuperAdmin();
        if (!lockStore(storeId)) {
            throw ApiException.notFound("Mağaza tapılmadı");
        }
        PlanView plan = allPlans().stream().filter(p -> p.code().equals(planCode)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("INVALID_PLAN", "Seçilmiş plan mövcud deyil"));
        LocalDate[] period = periodFor(storeId, plan.durationMonths());
        UUID adminId = tenant.currentUserId();

        UUID subId = jdbc.sql("SELECT id FROM subscriptions WHERE store_id = :s AND status = 'PENDING' ORDER BY requested_at DESC LIMIT 1")
                .param("s", storeId).query(UUID.class).optional().orElse(null);
        if (subId != null) {
            jdbc.sql("""
                    UPDATE subscriptions SET status = 'ACTIVE', plan_id = :planId, amount = :amount,
                           start_date = :start, end_date = :end, reviewed_at = now(), reviewed_by = :admin
                    WHERE id = :id
                    """)
                    .param("planId", plan.id()).param("amount", plan.price())
                    .param("start", period[0]).param("end", period[1]).param("admin", adminId).param("id", subId)
                    .update();
        } else {
            subId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO subscriptions (id, store_id, plan_id, status, amount, start_date, end_date, reviewed_at, reviewed_by)
                    VALUES (:id, :storeId, :planId, 'ACTIVE', :amount, :start, :end, now(), :admin)
                    """)
                    .param("id", subId).param("storeId", storeId).param("planId", plan.id()).param("amount", plan.price())
                    .param("start", period[0]).param("end", period[1]).param("admin", adminId)
                    .update();
        }
        audit.log(storeId, adminId, "SUBSCRIPTION_ACTIVATED", "SUBSCRIPTION", subId.toString(),
                plan.code() + " aktiv: " + period[0] + " - " + period[1]);
        return getView(subId);
    }

    private boolean lockStore(UUID storeId) {
        return jdbc.sql("SELECT id FROM stores WHERE id = :id FOR UPDATE")
                .param("id", storeId).query(UUID.class).optional().isPresent();
    }

    /** Renewal before expiry continues from the current end date; otherwise the period starts today. */
    private LocalDate[] periodFor(UUID storeId, int months) {
        LocalDate today = tenant.today();
        LocalDate currentEnd = jdbc.sql("SELECT MAX(end_date) FROM subscriptions WHERE store_id = :s AND status = 'ACTIVE'")
                .param("s", storeId).query(LocalDate.class).optional().orElse(null);
        LocalDate start = (currentEnd != null && !currentEnd.isBefore(today)) ? currentEnd.plusDays(1) : today;
        return new LocalDate[]{start, start.plusMonths(months).minusDays(1)};
    }

    @Transactional
    public SubscriptionView reject(UUID subscriptionId, String reason) {
        tenant.requireSuperAdmin();
        SubscriptionView sub = getView(subscriptionId);
        if (!"PENDING".equals(sub.status())) {
            throw ApiException.conflict("INVALID_STATE", "Yalnız gözləyən abunəlik rədd edilə bilər");
        }
        jdbc.sql("""
                UPDATE subscriptions SET status = 'REJECTED', reject_reason = :reason,
                       reviewed_at = now(), reviewed_by = :admin
                WHERE id = :id
                """)
                .param("reason", reason).param("admin", tenant.currentUserId()).param("id", subscriptionId)
                .update();
        audit.log(sub.storeId(), tenant.currentUserId(), "SUBSCRIPTION_REJECTED", "SUBSCRIPTION",
                subscriptionId.toString(), reason);
        return getView(subscriptionId);
    }

    /** Adds days to the store's current active subscription (admin courtesy extension). */
    @Transactional
    public LocalDate extend(UUID storeId, int days) {
        tenant.requireSuperAdmin();
        if (days < 1 || days > 366) {
            throw ApiException.badRequest("INVALID_DAYS", "Uzatma 1-366 gün arasında olmalıdır");
        }
        UUID subId = jdbc.sql("""
                SELECT id FROM subscriptions WHERE store_id = :s AND status = 'ACTIVE'
                ORDER BY end_date DESC LIMIT 1
                """).param("s", storeId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_SUBSCRIPTION", "Mağazanın aktiv abunəliyi yoxdur"));
        LocalDate newEnd = jdbc.sql("UPDATE subscriptions SET end_date = end_date + :days WHERE id = :id RETURNING end_date")
                .param("days", days).param("id", subId).query(LocalDate.class).single();
        audit.log(storeId, tenant.currentUserId(), "SUBSCRIPTION_EXTENDED", "SUBSCRIPTION", subId.toString(),
                "+" + days + " gün, yeni bitmə tarixi: " + newEnd);
        return newEnd;
    }

    /** Marks ACTIVE subscriptions whose end date has passed as EXPIRED. Runs daily in business time zone. */
    @Transactional
    public int expireDue() {
        return jdbc.sql("UPDATE subscriptions SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND end_date < :today")
                .param("today", tenant.today()).update();
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Baku")
    public void scheduledExpiry() {
        expireDue();
    }

    // ---------- helpers ----------

    SubscriptionView getView(UUID id) {
        return jdbc.sql(SELECT_SUB + " WHERE s.id = :id").param("id", id).query(this::mapSub).optional()
                .orElseThrow(() -> ApiException.notFound("Abunəlik tapılmadı"));
    }

    private SubscriptionView mapSub(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SubscriptionView(
                rs.getObject("id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("store_name"),
                rs.getString("plan_code"),
                rs.getString("plan_name"),
                rs.getString("status"),
                Money.of(rs.getBigDecimal("amount")),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getString("payer_reference"),
                rs.getString("reject_reason"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("reviewed_at", OffsetDateTime.class),
                rs.getBoolean("has_receipt"));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
