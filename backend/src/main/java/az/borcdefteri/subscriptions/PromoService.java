package az.borcdefteri.subscriptions;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.subscriptions.PlanService.PlanView;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Promo codes (a percent or a fixed manat discount on a package) and the free trial for new stores.
 * A code is counted as used when a request with it is approved; a store can use the same code once.
 */
@Service
public class PromoService {

    public record PromoView(UUID id, String code, Integer percentOff, BigDecimal amountOff, Integer maxUses,
                            LocalDate validUntil, boolean active, String note, long usedCount, OffsetDateTime createdAt) {}

    public record PromoInput(String code, Integer percentOff, BigDecimal amountOff, Integer maxUses,
                             LocalDate validUntil, Boolean active, String note) {}

    /** The discount a code gives on a given price. */
    public record Applied(UUID promoId, String code, BigDecimal price, BigDecimal discount, BigDecimal total) {}

    public record TrialSettings(int days, String planCode) {}

    static final String TRIAL_DAYS = "trial_days";
    static final String TRIAL_PLAN = "trial_plan";

    private static final String SELECT = """
            SELECT c.*, (SELECT COUNT(*) FROM subscriptions s
                          WHERE s.promo_code_id = c.id AND s.status IN ('ACTIVE', 'EXPIRED')) AS used_count
            FROM promo_codes c
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final PlanService plans;

    public PromoService(JdbcClient jdbc, TenantService tenant, AuditService audit, PlanService plans) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.plans = plans;
    }

    // ---------- admin ----------

    public List<PromoView> list() {
        tenant.requireSuperAdmin();
        return jdbc.sql(SELECT + " ORDER BY c.created_at DESC").query(this::map).list();
    }

    @Transactional
    public PromoView create(PromoInput in) {
        tenant.requireSuperAdmin();
        String code = normalize(in.code());
        if (code == null || !code.matches("[A-Z0-9_-]{3,40}")) {
            throw ApiException.badRequest("INVALID_CODE", "Kod 3-40 simvol olmalıdır (hərf, rəqəm, - və _)");
        }
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM promo_codes WHERE code = :c").param("c", code)
                .query(Long.class).single() > 0;
        if (exists) {
            throw ApiException.conflict("CODE_EXISTS", "Bu promo-kod artıq var");
        }
        UUID id = UUID.randomUUID();
        validate(in);
        jdbc.sql("""
                INSERT INTO promo_codes (id, code, percent_off, amount_off, max_uses, valid_until, active, note, created_by)
                VALUES (:id, :code, :pct, :amt, :max, :until, :active, :note, :by)
                """)
                .param("id", id).param("code", code).param("pct", in.percentOff())
                .param("amt", in.amountOff() == null ? null : Money.of(in.amountOff()))
                .param("max", in.maxUses()).param("until", in.validUntil())
                .param("active", in.active() == null || in.active()).param("note", blank(in.note()))
                .param("by", tenant.currentUserId()).update();
        audit.log(null, tenant.currentUserId(), "PROMO_CREATED", "PROMO", id.toString(), code + ": " + describe(in));
        return get(id);
    }

    @Transactional
    public PromoView update(UUID id, PromoInput in) {
        tenant.requireSuperAdmin();
        get(id);
        validate(in);
        jdbc.sql("""
                UPDATE promo_codes SET percent_off = :pct, amount_off = :amt, max_uses = :max, valid_until = :until,
                       active = :active, note = :note WHERE id = :id
                """)
                .param("pct", in.percentOff()).param("amt", in.amountOff() == null ? null : Money.of(in.amountOff()))
                .param("max", in.maxUses()).param("until", in.validUntil())
                .param("active", in.active() == null || in.active()).param("note", blank(in.note()))
                .param("id", id).update();
        audit.log(null, tenant.currentUserId(), "PROMO_UPDATED", "PROMO", id.toString(), describe(in));
        return get(id);
    }

    public TrialSettings trial() {
        int days = jdbc.sql("SELECT value FROM platform_settings WHERE key = :k").param("k", TRIAL_DAYS)
                .query(String.class).optional().map(v -> {
                    try { return Integer.parseInt(v.trim()); } catch (RuntimeException e) { return 0; }
                }).orElse(0);
        String plan = jdbc.sql("SELECT value FROM platform_settings WHERE key = :k").param("k", TRIAL_PLAN)
                .query(String.class).optional().filter(v -> !v.isBlank()).orElse(null);
        return new TrialSettings(Math.max(0, days), plan);
    }

    @Transactional
    public TrialSettings updateTrial(int days, String planCode) {
        tenant.requireSuperAdmin();
        if (days < 0 || days > 90) {
            throw ApiException.badRequest("INVALID_DAYS", "Sınaq 0-90 gün ola bilər (0 = sınaq yoxdur)");
        }
        String plan = blank(planCode);
        if (plan != null && plans.byCode(plan).filter(p -> !p.archived()).isEmpty()) {
            throw ApiException.badRequest("INVALID_PLAN", "Seçilmiş paket mövcud deyil");
        }
        for (String[] kv : new String[][]{{TRIAL_DAYS, String.valueOf(days)}, {TRIAL_PLAN, plan}}) {
            jdbc.sql("""
                    INSERT INTO platform_settings (key, value, updated_at, updated_by) VALUES (:k, :v, now(), :by)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by
                    """).param("k", kv[0]).param("v", kv[1]).param("by", tenant.currentUserId()).update();
        }
        audit.log(null, tenant.currentUserId(), "TRIAL_SETTINGS_UPDATED", "SETTINGS", "trial",
                days == 0 ? "Pulsuz sınaq söndürüldü" : "Pulsuz sınaq: " + days + " gün" + (plan != null ? ", paket: " + plan : ""));
        return trial();
    }

    /**
     * Gives a newly registered store its free trial (if switched on): an ACTIVE subscription of the trial package
     * (or the recommended / most expensive one) for N days, amount 0. Runs inside the registration transaction.
     */
    public void startTrial(UUID storeId, UUID userId) {
        TrialSettings t = trial();
        if (t.days() <= 0) return;
        List<PlanView> active = plans.activePlans();
        Optional<PlanView> plan = Optional.ofNullable(t.planCode()).flatMap(plans::byCode).filter(p -> p.active() && !p.archived());
        if (plan.isEmpty()) {
            plan = active.stream().filter(PlanView::recommended).findFirst();
        }
        if (plan.isEmpty()) {
            plan = active.stream().max((a, b) -> a.price().compareTo(b.price()));
        }
        if (plan.isEmpty()) return;
        LocalDate start = tenant.today();
        LocalDate end = start.plusDays(t.days() - 1L);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO subscriptions (id, store_id, plan_id, status, amount, start_date, end_date, period_code,
                                           period_days, trial, reviewed_at, note)
                VALUES (:id, :store, :plan, 'ACTIVE', 0, :start, :end, 'CUSTOM', :days, TRUE, now(), 'Pulsuz sınaq')
                """)
                .param("id", id).param("store", storeId).param("plan", plan.get().id()).param("start", start)
                .param("end", end).param("days", t.days()).update();
        audit.log(storeId, userId, "TRIAL_STARTED", "SUBSCRIPTION", id.toString(),
                "Pulsuz sınaq: " + plan.get().name() + ", " + t.days() + " gün (" + start + " - " + end + ")");
    }

    // ---------- store side ----------

    /** Checks a code for this store and price; throws a clear error when it cannot be used. */
    public Applied apply(String rawCode, UUID storeId, BigDecimal price, UUID ignoreSubscription) {
        String code = normalize(rawCode);
        PromoView p = jdbc.sql(SELECT + " WHERE c.code = :c").param("c", code).query(this::map).optional()
                .orElseThrow(() -> ApiException.badRequest("PROMO_INVALID", "Promo-kod tapılmadı"));
        if (!p.active()) {
            throw ApiException.badRequest("PROMO_INVALID", "Bu promo-kod artıq aktiv deyil");
        }
        if (p.validUntil() != null && p.validUntil().isBefore(tenant.today())) {
            throw ApiException.badRequest("PROMO_EXPIRED", "Promo-kodun müddəti bitib");
        }
        if (p.maxUses() != null && p.usedCount() >= p.maxUses()) {
            throw ApiException.badRequest("PROMO_USED_UP", "Promo-kodun istifadə limiti bitib");
        }
        boolean usedByStore = jdbc.sql("""
                SELECT COUNT(*) FROM subscriptions WHERE store_id = :s AND promo_code_id = :p
                  AND status IN ('ACTIVE', 'EXPIRED') AND id <> :ignore
                """).param("s", storeId).param("p", p.id())
                .param("ignore", ignoreSubscription == null ? new UUID(0, 0) : ignoreSubscription)
                .query(Long.class).single() > 0;
        if (usedByStore) {
            throw ApiException.badRequest("PROMO_ALREADY_USED", "Bu promo-koddan artıq istifadə etmisiniz");
        }
        BigDecimal discount = p.percentOff() != null
                ? price.multiply(BigDecimal.valueOf(p.percentOff())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : p.amountOff();
        discount = Money.of(discount.min(price));
        return new Applied(p.id(), p.code(), Money.of(price), discount, Money.of(price.subtract(discount)));
    }

    // ---------- helpers ----------

    private PromoView get(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Promo-kod tapılmadı"));
    }

    private void validate(PromoInput in) {
        if ((in.percentOff() == null) == (in.amountOff() == null)) {
            throw ApiException.badRequest("INVALID_DISCOUNT", "Endirimi ya faizlə, ya da manatla yazın");
        }
        if (in.percentOff() != null && (in.percentOff() < 1 || in.percentOff() > 100)) {
            throw ApiException.badRequest("INVALID_DISCOUNT", "Faiz 1-100 arasında olmalıdır");
        }
        if (in.amountOff() != null && (in.amountOff().signum() <= 0 || in.amountOff().compareTo(new BigDecimal("100000")) > 0)) {
            throw ApiException.badRequest("INVALID_DISCOUNT", "Endirim məbləği düzgün deyil");
        }
        if (in.maxUses() != null && in.maxUses() < 1) {
            throw ApiException.badRequest("INVALID_MAX_USES", "İstifadə limiti 1-dən az ola bilməz");
        }
        if (in.note() != null && in.note().length() > 200) {
            throw ApiException.badRequest("NOTE_TOO_LONG", "Qeyd 200 simvoldan uzun ola bilməz");
        }
    }

    private static String describe(PromoInput in) {
        return (in.percentOff() != null ? in.percentOff() + "%" : in.amountOff() + " AZN")
                + (in.maxUses() != null ? ", limit " + in.maxUses() : "")
                + (in.validUntil() != null ? ", " + in.validUntil() + " qədər" : "")
                + (Boolean.FALSE.equals(in.active()) ? ", deaktiv" : "");
    }

    private PromoView map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PromoView(rs.getObject("id", UUID.class), rs.getString("code"),
                (Integer) rs.getObject("percent_off"), rs.getBigDecimal("amount_off"), (Integer) rs.getObject("max_uses"),
                rs.getObject("valid_until", LocalDate.class), rs.getBoolean("active"), rs.getString("note"),
                rs.getLong("used_count"), rs.getObject("created_at", OffsetDateTime.class));
    }

    static String normalize(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
