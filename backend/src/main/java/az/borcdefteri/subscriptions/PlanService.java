package az.borcdefteri.subscriptions;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Subscription packages, fully managed by the SUPER_ADMIN: name, description, advantages, a price per period
 * (1 / 3 / 6 / 12 months and an optional custom number of days), limits, ordering, "recommended" flag and the
 * card colours. Colours are design only; prices are always read from here on the server, never from a client.
 */
@Service
public class PlanService {

    /** A purchasable period of a package. Standard periods are calendar months, CUSTOM is a number of days. */
    public enum Period {
        MONTHLY(1, "price_monthly"), QUARTERLY(3, "price_quarterly"), SEMI_ANNUAL(6, "price_semi_annual"),
        YEARLY(12, "price_yearly"), CUSTOM(0, "custom_price");

        public final int months;
        final String column;

        Period(int months, String column) {
            this.months = months;
            this.column = column;
        }

        public static Period parse(String s) {
            try {
                return Period.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("INVALID_PERIOD", "Seçilmiş müddət düzgün deyil");
            }
        }

        static Period ofMonths(int months) {
            return Arrays.stream(values()).filter(p -> p.months == months && p != CUSTOM).findFirst().orElse(null);
        }
    }

    public record PeriodOption(String period, Integer months, Integer days, BigDecimal price) {}

    public record PlanView(UUID id, String code, String name, BigDecimal price, int durationMonths, boolean active,
                           String description, List<String> features, Integer maxEmployees, Integer maxCustomers,
                           int sortOrder, boolean recommended, boolean archived, List<PeriodOption> options,
                           String primaryColor, String secondaryColor, String backgroundColor, String textColor,
                           String buttonColor, boolean inUse) {

        public Optional<PeriodOption> option(Period period) {
            return options.stream().filter(o -> o.period().equals(period.name())).findFirst();
        }
    }

    /** Everything the admin form sends. Period prices left null are not offered. */
    public record PlanInput(String name, String description, List<String> features,
                            BigDecimal priceMonthly, BigDecimal priceQuarterly, BigDecimal priceSemiAnnual,
                            BigDecimal priceYearly, Integer customDays, BigDecimal customPrice,
                            Integer maxEmployees, Integer maxCustomers, Boolean active, Boolean recommended,
                            String primaryColor, String secondaryColor, String backgroundColor, String textColor,
                            String buttonColor) {}

    /** Limits of the store's current (active) package; null = unlimited. */
    public record Limits(Integer maxEmployees, Integer maxCustomers) {}

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private static final String SELECT = """
            SELECT p.*, EXISTS (SELECT 1 FROM subscriptions s WHERE s.plan_id = p.id) AS in_use
            FROM subscription_plans p
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public PlanService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    // ---------- read ----------

    /** Packages shown to stores (and before login): active, not archived, offering at least one period. */
    public List<PlanView> activePlans() {
        return jdbc.sql(SELECT + " WHERE p.active AND NOT p.archived ORDER BY p.sort_order, p.price, p.name")
                .query(this::map).list().stream().filter(p -> !p.options().isEmpty()).toList();
    }

    public List<PlanView> allPlans(boolean includeArchived) {
        String where = includeArchived ? "" : " WHERE NOT p.archived";
        return jdbc.sql(SELECT + where + " ORDER BY p.archived, p.sort_order, p.price, p.name").query(this::map).list();
    }

    public PlanView get(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Paket tapılmadı"));
    }

    public Optional<PlanView> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " WHERE p.code = :code").param("code", code.trim().toUpperCase(Locale.ROOT))
                .query(this::map).optional();
    }

    public Limits limitsFor(UUID storeId) {
        return jdbc.sql("""
                SELECT p.max_employees, p.max_customers FROM subscriptions s
                JOIN subscription_plans p ON p.id = s.plan_id
                WHERE s.store_id = :s AND s.status = 'ACTIVE' AND s.end_date >= :today
                ORDER BY s.end_date DESC LIMIT 1
                """).param("s", storeId).param("today", tenant.today())
                .query((rs, n) -> new Limits((Integer) rs.getObject("max_employees"), (Integer) rs.getObject("max_customers")))
                .optional().orElse(new Limits(null, null));
    }

    // ---------- write (SUPER_ADMIN) ----------

    @Transactional
    public PlanView create(PlanInput in) {
        tenant.requireSuperAdmin();
        Normalized n = normalize(in);
        UUID id = UUID.randomUUID();
        String code = "P" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        int nextOrder = jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM subscription_plans")
                .query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO subscription_plans (id, code, name, price, duration_months, active, sort_order)
                VALUES (:id, :code, :name, 0, 1, FALSE, :order)
                """).param("id", id).param("code", code).param("name", n.name).param("order", nextOrder).update();
        write(id, n);
        audit.log(null, tenant.currentUserId(), "PLAN_CREATED", "PLAN", id.toString(), n.summary());
        return get(id);
    }

    @Transactional
    public PlanView update(UUID id, PlanInput in) {
        tenant.requireSuperAdmin();
        PlanView current = get(id);
        if (current.archived()) {
            throw ApiException.conflict("PLAN_ARCHIVED", "Arxivdəki paket dəyişdirilə bilməz — əvvəlcə bərpa edin");
        }
        Normalized n = normalize(in);
        write(id, n);
        audit.log(null, tenant.currentUserId(), "PLAN_UPDATED", "PLAN", id.toString(), n.summary());
        return get(id);
    }

    /**
     * Old admin form (single price + months). Kept so existing clients keep working: the package then offers
     * exactly that one period.
     */
    @Transactional
    public PlanView updateLegacy(UUID id, String name, BigDecimal price, int durationMonths, boolean active) {
        tenant.requireSuperAdmin();
        PlanView current = get(id);
        Period period = Period.ofMonths(durationMonths);
        PlanInput in = new PlanInput(name, current.description(), current.features(),
                period == Period.MONTHLY ? price : null, period == Period.QUARTERLY ? price : null,
                period == Period.SEMI_ANNUAL ? price : null, period == Period.YEARLY ? price : null,
                period == null ? durationMonths * 30 : null, period == null ? price : null,
                current.maxEmployees(), current.maxCustomers(), active, current.recommended(),
                current.primaryColor(), current.secondaryColor(), current.backgroundColor(), current.textColor(),
                current.buttonColor());
        return update(id, in);
    }

    /** Deletes an unused package; a package that any store ever requested or paid for is archived instead. */
    @Transactional
    public String delete(UUID id) {
        tenant.requireSuperAdmin();
        PlanView plan = get(id);
        if (plan.inUse()) {
            jdbc.sql("UPDATE subscription_plans SET archived = TRUE, active = FALSE, recommended = FALSE, updated_at = now() WHERE id = :id")
                    .param("id", id).update();
            audit.log(null, tenant.currentUserId(), "PLAN_ARCHIVED", "PLAN", id.toString(), plan.name());
            return "ARCHIVED";
        }
        jdbc.sql("DELETE FROM subscription_plans WHERE id = :id").param("id", id).update();
        audit.log(null, tenant.currentUserId(), "PLAN_DELETED", "PLAN", id.toString(), plan.name());
        return "DELETED";
    }

    @Transactional
    public PlanView restore(UUID id) {
        tenant.requireSuperAdmin();
        get(id);
        jdbc.sql("UPDATE subscription_plans SET archived = FALSE, updated_at = now() WHERE id = :id").param("id", id).update();
        audit.log(null, tenant.currentUserId(), "PLAN_RESTORED", "PLAN", id.toString(), null);
        return get(id);
    }

    /** Saves the display order: ids in the order the admin arranged them. */
    @Transactional
    public List<PlanView> reorder(List<UUID> ids) {
        tenant.requireSuperAdmin();
        for (int i = 0; i < ids.size(); i++) {
            jdbc.sql("UPDATE subscription_plans SET sort_order = :o, updated_at = now() WHERE id = :id")
                    .param("o", i + 1).param("id", ids.get(i)).update();
        }
        audit.log(null, tenant.currentUserId(), "PLAN_REORDERED", "PLAN", "order", ids.size() + " paket");
        return allPlans(false);
    }

    // ---------- helpers ----------

    private record Normalized(String name, String description, String features, BigDecimal monthly,
                              BigDecimal quarterly, BigDecimal semiAnnual, BigDecimal yearly, Integer customDays,
                              BigDecimal customPrice, Integer maxEmployees, Integer maxCustomers, boolean active,
                              boolean recommended, String primary, String secondary, String background, String text,
                              String button) {
        String summary() {
            return name + ": 1ay=" + monthly + ", 3ay=" + quarterly + ", 6ay=" + semiAnnual + ", 12ay=" + yearly
                    + (customDays != null ? ", " + customDays + "gün=" + customPrice : "") + ", aktiv=" + active;
        }
    }

    private Normalized normalize(PlanInput in) {
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.badRequest("INVALID_NAME", "Paket adı 1-100 simvol olmalıdır");
        }
        String description = blankToNull(in.description());
        if (description != null && description.length() > 500) {
            throw ApiException.badRequest("INVALID_DESCRIPTION", "Təsvir 500 simvoldan uzun ola bilməz");
        }
        List<String> features = new ArrayList<>();
        if (in.features() != null) {
            for (String f : in.features()) {
                String t = blankToNull(f);
                if (t != null) {
                    if (t.length() > 200) {
                        throw ApiException.badRequest("INVALID_FEATURE", "Hər üstünlük 200 simvoldan qısa olmalıdır");
                    }
                    features.add(t.replace('\n', ' '));
                }
            }
        }
        if (features.size() > 20) {
            throw ApiException.badRequest("INVALID_FEATURE", "Ən çox 20 üstünlük əlavə etmək olar");
        }
        BigDecimal monthly = price(in.priceMonthly());
        BigDecimal quarterly = price(in.priceQuarterly());
        BigDecimal semi = price(in.priceSemiAnnual());
        BigDecimal yearly = price(in.priceYearly());
        Integer customDays = in.customDays();
        BigDecimal customPrice = price(in.customPrice());
        if ((customDays == null) != (customPrice == null)) {
            throw ApiException.badRequest("INVALID_CUSTOM_PERIOD", "Xüsusi müddət üçün həm gün sayını, həm qiyməti yazın");
        }
        if (customDays != null && (customDays < 1 || customDays > 3660)) {
            throw ApiException.badRequest("INVALID_CUSTOM_PERIOD", "Xüsusi müddət 1-3660 gün olmalıdır");
        }
        boolean active = in.active() == null || in.active();
        if (active && monthly == null && quarterly == null && semi == null && yearly == null && customDays == null) {
            throw ApiException.badRequest("NO_PRICE", "Aktiv paket üçün ən azı bir müddətin qiymətini yazın");
        }
        Integer maxEmployees = limit(in.maxEmployees(), "İşçi sayı");
        Integer maxCustomers = limit(in.maxCustomers(), "Müştəri sayı");
        return new Normalized(name, description, features.isEmpty() ? null : String.join("\n", features),
                monthly, quarterly, semi, yearly, customDays, customPrice, maxEmployees, maxCustomers, active,
                Boolean.TRUE.equals(in.recommended()),
                color(in.primaryColor(), "#101C35"), color(in.secondaryColor(), "#D6AD60"),
                color(in.backgroundColor(), "#FFFFFF"), color(in.textColor(), "#101C35"),
                color(in.buttonColor(), "#101C35"));
    }

    private void write(UUID id, Normalized n) {
        // price / duration_months mirror the first offered period, for older screens and reports.
        BigDecimal headlinePrice;
        int headlineMonths;
        if (n.monthly != null) { headlinePrice = n.monthly; headlineMonths = 1; }
        else if (n.quarterly != null) { headlinePrice = n.quarterly; headlineMonths = 3; }
        else if (n.semiAnnual != null) { headlinePrice = n.semiAnnual; headlineMonths = 6; }
        else if (n.yearly != null) { headlinePrice = n.yearly; headlineMonths = 12; }
        else if (n.customDays != null) { headlinePrice = n.customPrice; headlineMonths = Math.max(1, (n.customDays + 29) / 30); }
        else { headlinePrice = BigDecimal.ZERO; headlineMonths = 1; }

        jdbc.sql("""
                UPDATE subscription_plans SET name = :name, description = :description, features = :features,
                       price = :price, duration_months = :months, active = :active, recommended = :recommended,
                       price_monthly = :m1, price_quarterly = :m3, price_semi_annual = :m6, price_yearly = :m12,
                       custom_days = :customDays, custom_price = :customPrice,
                       max_employees = :maxEmployees, max_customers = :maxCustomers,
                       primary_color = :c1, secondary_color = :c2, background_color = :c3, text_color = :c4,
                       button_color = :c5, updated_at = now()
                WHERE id = :id
                """)
                .param("name", n.name).param("description", n.description).param("features", n.features)
                .param("price", Money.of(headlinePrice)).param("months", headlineMonths)
                .param("active", n.active).param("recommended", n.recommended)
                .param("m1", n.monthly).param("m3", n.quarterly).param("m6", n.semiAnnual).param("m12", n.yearly)
                .param("customDays", n.customDays).param("customPrice", n.customPrice)
                .param("maxEmployees", n.maxEmployees).param("maxCustomers", n.maxCustomers)
                .param("c1", n.primary).param("c2", n.secondary).param("c3", n.background).param("c4", n.text)
                .param("c5", n.button).param("id", id)
                .update();
    }

    private static BigDecimal price(BigDecimal p) {
        if (p == null) {
            return null;
        }
        if (p.signum() < 0 || p.compareTo(new BigDecimal("1000000")) >= 0) {
            throw ApiException.badRequest("INVALID_PRICE", "Qiymət 0 ilə 999999.99 arasında olmalıdır");
        }
        return Money.of(p);
    }

    private static Integer limit(Integer v, String label) {
        if (v == null) {
            return null;
        }
        if (v < 0 || v > 100000) {
            throw ApiException.badRequest("INVALID_LIMIT", label + " limiti 0-100000 arasında olmalıdır");
        }
        return v;
    }

    private static String color(String c, String fallback) {
        if (c == null || c.isBlank()) {
            return fallback;
        }
        String t = c.trim();
        if (!HEX.matcher(t).matches()) {
            throw ApiException.badRequest("INVALID_COLOR", "Rəng HEX formatında olmalıdır, məs. #1E40AF");
        }
        return t.toUpperCase(Locale.ROOT);
    }

    private PlanView map(ResultSet rs, int n) throws SQLException {
        List<PeriodOption> options = new ArrayList<>();
        for (Period p : Period.values()) {
            BigDecimal price = rs.getBigDecimal(p.column);
            if (price == null) {
                continue;
            }
            if (p == Period.CUSTOM) {
                options.add(new PeriodOption(p.name(), null, (Integer) rs.getObject("custom_days"), Money.of(price)));
            } else {
                options.add(new PeriodOption(p.name(), p.months, null, Money.of(price)));
            }
        }
        String features = rs.getString("features");
        return new PlanView(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                Money.of(rs.getBigDecimal("price")), rs.getInt("duration_months"), rs.getBoolean("active"),
                rs.getString("description"),
                features == null ? List.of() : List.of(features.split("\n")),
                (Integer) rs.getObject("max_employees"), (Integer) rs.getObject("max_customers"),
                rs.getInt("sort_order"), rs.getBoolean("recommended"), rs.getBoolean("archived"), options,
                rs.getString("primary_color"), rs.getString("secondary_color"), rs.getString("background_color"),
                rs.getString("text_color"), rs.getString("button_color"), rs.getBoolean("in_use"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
