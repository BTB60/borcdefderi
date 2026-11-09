package az.borcdefteri.subscriptions;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.AppProperties;
import az.borcdefteri.common.Money;
import az.borcdefteri.notifications.NotificationService;
import az.borcdefteri.subscriptions.PlanService.Period;
import az.borcdefteri.subscriptions.PlanService.PeriodOption;
import az.borcdefteri.subscriptions.PlanService.PlanView;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Manual subscription workflow. A row of "subscriptions" is the payment request:
 * PENDING (store says "Ödəniş etdim") -> ACTIVE (SUPER_ADMIN approved) | REJECTED; ACTIVE -> EXPIRED.
 * The amount and the period always come from the package on the server, never from the client.
 */
@Service
public class SubscriptionService {

    public record SubscriptionView(UUID id, UUID storeId, String storeName, String planCode, String planName,
                                   String status, BigDecimal amount, LocalDate startDate, LocalDate endDate,
                                   String payerReference, String rejectReason, OffsetDateTime requestedAt,
                                   OffsetDateTime reviewedAt, boolean hasReceipt,
                                   String periodCode, Integer periodMonths, Integer periodDays,
                                   String paymentMethod, String payerName, BigDecimal declaredAmount,
                                   LocalDate paymentDate, String note,
                                   String ownerName, String ownerEmail, String ownerPhone, String storePhone,
                                   String reviewedByName) {

        /** PENDING / APPROVED / REJECTED, as the payment request status (ACTIVE and EXPIRED were approved). */
        @com.fasterxml.jackson.annotation.JsonProperty("requestStatus")
        public String requestStatus() {
            return switch (status) {
                case "ACTIVE", "EXPIRED" -> "APPROVED";
                default -> status;
            };
        }
    }

    /** What the store sends with "Ödəniş etdim". Everything except the package is optional. */
    public record PaymentClaim(String planCode, String period, String paymentMethod, String payerName,
                               BigDecimal declaredAmount, LocalDate paymentDate, String note, String payerReference) {}

    public record ReceiptFile(byte[] content, String contentType, String fileName) {}

    private static final String SELECT_SUB = """
            SELECT s.id, s.store_id, st.name AS store_name, st.phone AS store_phone,
                   p.code AS plan_code, p.name AS plan_name,
                   s.status, s.amount, s.start_date, s.end_date, s.payer_reference, s.reject_reason,
                   s.requested_at, s.reviewed_at, s.period_code, s.period_months, s.period_days,
                   s.payment_method, s.payer_name, s.declared_amount, s.payment_date, s.note,
                   ow.full_name AS owner_name, ow.email AS owner_email, ow.phone AS owner_phone,
                   rv.full_name AS reviewed_by_name,
                   EXISTS (SELECT 1 FROM subscription_receipts r WHERE r.subscription_id = s.id) AS has_receipt
            FROM subscriptions s
            JOIN subscription_plans p ON p.id = s.plan_id
            JOIN stores st ON st.id = s.store_id
            LEFT JOIN LATERAL (
                SELECT u.full_name, u.email, u.phone FROM store_members m JOIN users u ON u.id = m.user_id
                WHERE m.store_id = s.store_id AND m.role = 'STORE_OWNER' ORDER BY m.created_at LIMIT 1
            ) ow ON TRUE
            LEFT JOIN users rv ON rv.id = s.reviewed_by
            """;

    private static final DateTimeFormatter AZ_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final ReceiptStorage receipts;
    private final AuditService audit;
    private final AppProperties props;
    private final PlanService plans;
    private final NotificationService notifications;

    public SubscriptionService(JdbcClient jdbc, TenantService tenant, ReceiptStorage receipts, AuditService audit,
                               AppProperties props, PlanService plans, NotificationService notifications) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.receipts = receipts;
        this.audit = audit;
        this.props = props;
        this.plans = plans;
        this.notifications = notifications;
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
            // Renewals queue after the current period: show the furthest paid-up date.
            LocalDate end = jdbc.sql("SELECT MAX(end_date) FROM subscriptions WHERE store_id = :s AND status = 'ACTIVE'")
                    .param("s", storeId).query(LocalDate.class).single();
            long days = ChronoUnit.DAYS.between(today, end);
            return new SubscriptionSummary(true, "ACTIVE", active.planCode(), active.planName(),
                    active.startDate(), end, days, days <= reminderDays, expiresAt(end));
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

    /** Compatibility entry point (package + optional transaction number + optional receipt). */
    @Transactional
    public SubscriptionView request(String planCode, String payerReference, MultipartFile receipt) {
        return request(new PaymentClaim(planCode, null, null, null, null, null, null, payerReference), receipt);
    }

    /**
     * "Ödəniş etdim": the owner tells the admin which package and period they paid for, how, by whom, when, plus an
     * optional receipt image (PNG/JPG/PDF, max 5 MB, checked by content). Creates a PENDING request, or updates the
     * store's existing pending request (a new receipt replaces the old one). Nothing is activated here.
     */
    @Transactional
    public SubscriptionView request(PaymentClaim claim, MultipartFile receipt) {
        CurrentMember member = tenant.requireMember();
        if (!member.isOwner()) {
            throw ApiException.forbidden("OWNER_ONLY", "Abunəliyi yalnız mağaza sahibi göndərə bilər");
        }
        PlanView plan = plans.byCode(claim.planCode())
                .filter(p -> p.active() && !p.archived())
                .orElseThrow(() -> ApiException.badRequest("INVALID_PLAN", "Seçilmiş plan mövcud deyil"));
        PeriodOption option = (claim.period() == null || claim.period().isBlank())
                ? plan.options().stream().findFirst().orElse(null)
                : plan.option(Period.parse(claim.period())).orElse(null);
        if (option == null) {
            throw ApiException.badRequest("INVALID_PERIOD", "Bu paket seçilmiş müddətə satılmır");
        }
        String method = claim.paymentMethod() == null || claim.paymentMethod().isBlank() ? null
                : claim.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (method != null && !method.equals("M10") && !method.equals("CARD")) {
            throw ApiException.badRequest("INVALID_METHOD", "Ödəniş üsulu M10 və ya bank kartı olmalıdır");
        }
        String reference = check(blankToNull(claim.payerReference()), 120, "REFERENCE_TOO_LONG",
                "Ödəniş istinad nömrəsi 120 simvoldan uzun ola bilməz");
        String payerName = check(blankToNull(claim.payerName()), 120, "PAYER_TOO_LONG",
                "Ödəniş edənin adı 120 simvoldan uzun ola bilməz");
        String note = check(blankToNull(claim.note()), 500, "NOTE_TOO_LONG", "Qeyd 500 simvoldan uzun ola bilməz");
        BigDecimal declared = claim.declaredAmount();
        if (declared != null && (declared.signum() < 0 || declared.compareTo(new BigDecimal("1000000")) >= 0)) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Ödəniş məbləği düzgün deyil");
        }
        LocalDate paymentDate = claim.paymentDate();
        if (paymentDate != null && (paymentDate.isAfter(tenant.today().plusDays(1))
                || paymentDate.isBefore(tenant.today().minusDays(366)))) {
            throw ApiException.badRequest("INVALID_PAYMENT_DATE", "Ödəniş tarixi düzgün deyil");
        }
        ReceiptStorage.Stored stored = (receipt == null || receipt.isEmpty()) ? null : receipts.store(receipt);
        BigDecimal amount = option.price(); // server-side price, the declared amount is only shown to the admin

        lockStore(member.storeId()); // serialises concurrent requests of the same store
        UUID pendingId = jdbc.sql("SELECT id FROM subscriptions WHERE store_id = :s AND status = 'PENDING' ORDER BY requested_at DESC LIMIT 1")
                .param("s", member.storeId()).query(UUID.class).optional().orElse(null);
        UUID subId = pendingId != null ? pendingId : UUID.randomUUID();
        if (pendingId == null) {
            jdbc.sql("""
                    INSERT INTO subscriptions (id, store_id, plan_id, status, amount)
                    VALUES (:id, :storeId, :planId, 'PENDING', :amount)
                    """)
                    .param("id", subId).param("storeId", member.storeId()).param("planId", plan.id())
                    .param("amount", amount).update();
        }
        jdbc.sql("""
                UPDATE subscriptions SET plan_id = :planId, amount = :amount, requested_at = now(),
                       period_code = :period, period_months = :months, period_days = :days,
                       payer_reference = COALESCE(:ref, payer_reference),
                       payment_method = COALESCE(:method, payment_method),
                       payer_name = COALESCE(:payer, payer_name),
                       declared_amount = COALESCE(:declared, declared_amount),
                       payment_date = COALESCE(:paymentDate, payment_date),
                       note = COALESCE(:note, note)
                WHERE id = :id
                """)
                .param("planId", plan.id()).param("amount", amount).param("period", option.period())
                .param("months", option.months()).param("days", option.days()).param("ref", reference)
                .param("method", method).param("payer", payerName)
                .param("declared", declared == null ? null : Money.of(declared))
                .param("paymentDate", paymentDate).param("note", note).param("id", subId)
                .update();
        if (stored != null) {
            jdbc.sql("DELETE FROM subscription_receipts WHERE subscription_id = :subId").param("subId", subId).update();
            jdbc.sql("""
                    INSERT INTO subscription_receipts (id, subscription_id, store_id, storage_name, original_name,
                                                       content_type, size_bytes, content)
                    VALUES (:id, :subId, :storeId, :name, :orig, :type, :size, :content)
                    """)
                    .param("id", UUID.randomUUID()).param("subId", subId).param("storeId", member.storeId())
                    .param("name", stored.storageName()).param("orig", stored.originalName())
                    .param("type", stored.contentType()).param("size", stored.sizeBytes())
                    .param("content", stored.content())
                    .update();
        }
        audit.log(member.storeId(), member.userId(), "SUBSCRIPTION_REQUESTED", "SUBSCRIPTION", subId.toString(),
                "Paket: " + plan.name() + " (" + periodLabel(option.period(), option.months(), option.days())
                        + "), məbləğ: " + amount + (method != null ? ", üsul: " + method : "")
                        + (declared != null ? ", bildirilən: " + Money.of(declared) : "")
                        + (reference != null ? ", istinad: " + reference : "") + (stored != null ? ", qəbz yükləndi" : ""));
        return getView(subId);
    }

    public ReceiptFile receiptFor(UUID subscriptionId) {
        boolean admin = tenant.isSuperAdmin();
        UUID callerStoreId = admin ? null : tenant.requireMember().storeId();
        record R(String storage, String type, String original, UUID storeId, byte[] content) {}
        R row = jdbc.sql("""
                SELECT r.storage_name, r.content_type, r.original_name, r.store_id, r.content
                FROM subscription_receipts r WHERE r.subscription_id = :id
                """).param("id", subscriptionId)
                .query((rs, n) -> new R(rs.getString("storage_name"), rs.getString("content_type"),
                        rs.getString("original_name"), rs.getObject("store_id", UUID.class), rs.getBytes("content")))
                .optional().orElseThrow(() -> ApiException.notFound("Qəbz tapılmadı"));
        if (!admin && !row.storeId().equals(callerStoreId)) {
            throw ApiException.notFound("Qəbz tapılmadı");
        }
        String name = row.original() != null ? row.original() : "qebz";
        byte[] bytes = row.content() != null ? row.content() : receipts.readLegacy(row.storage());
        return new ReceiptFile(bytes, row.type(), name);
    }

    // ---------- super admin side ----------

    public List<SubscriptionView> listByStatus(String status, UUID storeId) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        if (status != null && !status.isBlank()) {
            where.append(" AND s.status = :status");
        }
        if (storeId != null) {
            where.append(" AND s.store_id = :storeId");
        }
        var q = jdbc.sql(SELECT_SUB + where + " ORDER BY s.requested_at DESC LIMIT 500");
        if (status != null && !status.isBlank()) {
            q = q.param("status", status.toUpperCase(Locale.ROOT));
        }
        if (storeId != null) {
            q = q.param("storeId", storeId);
        }
        return q.query(this::mapSub).list();
    }

    /**
     * Approves a payment request in one transaction: the request row is locked and must still be PENDING (so the
     * same request can never be approved twice), the paid period is applied after any remaining active time,
     * the store is reactivated, the decision is written to the audit history and the store gets a notification.
     */
    @Transactional
    public SubscriptionView approve(UUID subscriptionId) {
        tenant.requireSuperAdmin();
        UUID storeId = jdbc.sql("SELECT store_id FROM subscriptions WHERE id = :id")
                .param("id", subscriptionId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Abunəlik tapılmadı"));
        lockStore(storeId);
        String status = jdbc.sql("SELECT status FROM subscriptions WHERE id = :id FOR UPDATE")
                .param("id", subscriptionId).query(String.class).single();
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("INVALID_STATE", "Yalnız gözləyən sorğu təsdiqlənə bilər (bu sorğu artıq baxılıb)");
        }
        SubscriptionView sub = getView(subscriptionId);
        LocalDate[] period = periodFor(storeId, sub.periodMonths(), sub.periodDays(), sub.planCode());

        int updated = jdbc.sql("""
                UPDATE subscriptions SET status = 'ACTIVE', start_date = :start, end_date = :end,
                       reviewed_at = now(), reviewed_by = :admin
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("start", period[0]).param("end", period[1]).param("admin", tenant.currentUserId())
                .param("id", subscriptionId).update();
        if (updated != 1) {
            throw ApiException.conflict("INVALID_STATE", "Sorğu artıq baxılıb");
        }
        reactivateStore(storeId);
        audit.log(storeId, tenant.currentUserId(), "SUBSCRIPTION_APPROVED", "SUBSCRIPTION",
                subscriptionId.toString(), sub.planName() + ", " + sub.amount() + " AZN, aktiv: " + period[0] + " - " + period[1]);
        notifications.create(storeId, "PAYMENT_APPROVED", "Ödənişiniz təsdiqləndi",
                sub.planName() + " paketi aktivdir: " + AZ_DATE.format(period[0]) + " – " + AZ_DATE.format(period[1]) + ".");
        return getView(subscriptionId);
    }

    /**
     * Admin activates a store directly after receiving the payment receipt (phone / WhatsApp).
     * A pending request of the store is turned into the active subscription; otherwise a new one is created.
     */
    @Transactional
    public SubscriptionView adminActivate(UUID storeId, String planCode, String periodCode) {
        tenant.requireSuperAdmin();
        if (!lockStore(storeId)) {
            throw ApiException.notFound("Mağaza tapılmadı");
        }
        PlanView plan = plans.byCode(planCode).filter(p -> !p.archived())
                .orElseThrow(() -> ApiException.badRequest("INVALID_PLAN", "Seçilmiş plan mövcud deyil"));
        PeriodOption option = (periodCode == null || periodCode.isBlank())
                ? plan.options().stream().findFirst().orElse(null)
                : plan.option(Period.parse(periodCode)).orElse(null);
        if (option == null) {
            throw ApiException.badRequest("INVALID_PERIOD", "Bu paket seçilmiş müddətə satılmır");
        }
        LocalDate[] period = periodFor(storeId, option.months(), option.days(), plan.code());
        UUID adminId = tenant.currentUserId();

        UUID subId = jdbc.sql("SELECT id FROM subscriptions WHERE store_id = :s AND status = 'PENDING' ORDER BY requested_at DESC LIMIT 1 FOR UPDATE")
                .param("s", storeId).query(UUID.class).optional().orElse(null);
        if (subId == null) {
            subId = UUID.randomUUID();
            jdbc.sql("INSERT INTO subscriptions (id, store_id, plan_id, status, amount) VALUES (:id, :storeId, :planId, 'PENDING', :amount)")
                    .param("id", subId).param("storeId", storeId).param("planId", plan.id()).param("amount", option.price())
                    .update();
        }
        jdbc.sql("""
                UPDATE subscriptions SET status = 'ACTIVE', plan_id = :planId, amount = :amount,
                       period_code = :periodCode, period_months = :months, period_days = :days,
                       start_date = :start, end_date = :end, reviewed_at = now(), reviewed_by = :admin
                WHERE id = :id
                """)
                .param("planId", plan.id()).param("amount", option.price()).param("periodCode", option.period())
                .param("months", option.months()).param("days", option.days())
                .param("start", period[0]).param("end", period[1]).param("admin", adminId).param("id", subId)
                .update();
        reactivateStore(storeId);
        audit.log(storeId, adminId, "SUBSCRIPTION_ACTIVATED", "SUBSCRIPTION", subId.toString(),
                plan.name() + " aktiv: " + period[0] + " - " + period[1]);
        notifications.create(storeId, "SUBSCRIPTION_ACTIVATED", "Abunəliyiniz aktivləşdirildi",
                plan.name() + " paketi aktivdir: " + AZ_DATE.format(period[0]) + " – " + AZ_DATE.format(period[1]) + ".");
        return getView(subId);
    }

    @Transactional
    public SubscriptionView reject(UUID subscriptionId, String reason) {
        tenant.requireSuperAdmin();
        String status = jdbc.sql("SELECT status FROM subscriptions WHERE id = :id FOR UPDATE")
                .param("id", subscriptionId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Abunəlik tapılmadı"));
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("INVALID_STATE", "Yalnız gözləyən sorğu rədd edilə bilər");
        }
        jdbc.sql("""
                UPDATE subscriptions SET status = 'REJECTED', reject_reason = :reason,
                       reviewed_at = now(), reviewed_by = :admin
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("reason", reason).param("admin", tenant.currentUserId()).param("id", subscriptionId)
                .update();
        SubscriptionView sub = getView(subscriptionId);
        audit.log(sub.storeId(), tenant.currentUserId(), "SUBSCRIPTION_REJECTED", "SUBSCRIPTION",
                subscriptionId.toString(), reason);
        notifications.create(sub.storeId(), "PAYMENT_REJECTED", "Ödəniş sorğunuz rədd edildi",
                "Səbəb: " + reason);
        return sub;
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
        LocalDate newEnd = jdbc.sql("""
                UPDATE subscriptions SET end_date = GREATEST(end_date, CAST(:yesterday AS date)) + :days
                WHERE id = :id RETURNING end_date
                """)
                .param("yesterday", tenant.today().minusDays(1)).param("days", days).param("id", subId)
                .query(LocalDate.class).single();
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

    private boolean lockStore(UUID storeId) {
        return jdbc.sql("SELECT id FROM stores WHERE id = :id FOR UPDATE")
                .param("id", storeId).query(UUID.class).optional().isPresent();
    }

    /** A paid store is usable again: a store suspended (e.g. for non-payment) becomes ACTIVE. */
    private void reactivateStore(UUID storeId) {
        int changed = jdbc.sql("UPDATE stores SET status = 'ACTIVE' WHERE id = :id AND status <> 'ACTIVE'")
                .param("id", storeId).update();
        if (changed > 0) {
            audit.log(storeId, tenant.currentUserId(), "STORE_ACTIVATED", "STORE", storeId.toString(),
                    "Ödəniş təsdiqi ilə bərpa edildi");
        }
    }

    /**
     * Renewal rule: time already paid is never lost. If the store still has active time, the new period starts
     * the day after the current end date; otherwise it starts today. Months are calendar months, custom periods
     * are exact days. Requests created before V7 have no snapshot and fall back to the package's months.
     */
    private LocalDate[] periodFor(UUID storeId, Integer months, Integer days, String planCode) {
        LocalDate today = tenant.today();
        LocalDate currentEnd = jdbc.sql("SELECT MAX(end_date) FROM subscriptions WHERE store_id = :s AND status = 'ACTIVE'")
                .param("s", storeId).query(LocalDate.class).optional().orElse(null);
        LocalDate start = (currentEnd != null && !currentEnd.isBefore(today)) ? currentEnd.plusDays(1) : today;
        if (days != null) {
            return new LocalDate[]{start, start.plusDays(days - 1L)};
        }
        int m = months != null ? months
                : plans.byCode(planCode).map(PlanView::durationMonths).orElse(1);
        return new LocalDate[]{start, start.plusMonths(m).minusDays(1)};
    }

    static String periodLabel(String code, Integer months, Integer days) {
        if (days != null) {
            return days + " gün";
        }
        return (months != null ? months : 1) + " ay";
    }

    SubscriptionView getView(UUID id) {
        return jdbc.sql(SELECT_SUB + " WHERE s.id = :id").param("id", id).query(this::mapSub).optional()
                .orElseThrow(() -> ApiException.notFound("Abunəlik tapılmadı"));
    }

    private SubscriptionView mapSub(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        BigDecimal declared = rs.getBigDecimal("declared_amount");
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
                rs.getBoolean("has_receipt"),
                rs.getString("period_code"),
                (Integer) rs.getObject("period_months"),
                (Integer) rs.getObject("period_days"),
                rs.getString("payment_method"),
                rs.getString("payer_name"),
                declared == null ? null : Money.of(declared),
                rs.getObject("payment_date", LocalDate.class),
                rs.getString("note"),
                rs.getString("owner_name"),
                rs.getString("owner_email"),
                rs.getString("owner_phone"),
                rs.getString("store_phone"),
                rs.getString("reviewed_by_name"));
    }

    private static String check(String value, int max, String code, String message) {
        if (value != null && value.length() > max) {
            throw ApiException.badRequest(code, message);
        }
        return value;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
