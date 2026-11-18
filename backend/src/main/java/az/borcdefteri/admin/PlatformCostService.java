package az.borcdefteri.admin;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Platforma xərcləri (super admin): the bills of running HesabPro — server, database, domain, e-mail — with the
 * next due date, what was paid, and this month's subscription income against the monthly cost.
 */
@Service
@RestController
public class PlatformCostService {

    /** 1 USD / EUR in AZN, for the totals (the manat is pegged to the dollar). */
    static final Map<String, BigDecimal> TO_AZN = Map.of("AZN", BigDecimal.ONE, "USD", new BigDecimal("1.70"), "EUR", new BigDecimal("1.85"));

    public record Payment(UUID id, LocalDate paidOn, BigDecimal amount, String currency, String note, String byName, OffsetDateTime createdAt) {}

    /** status: FREE, OK, SOON (≤ 7 days), DUE (today), OVERDUE, NO_DATE. */
    public record Cost(UUID id, String name, String provider, String url, BigDecimal amount, String currency, String period,
                       LocalDate nextDue, Long daysLeft, String status, String note, boolean active, BigDecimal monthlyAzn,
                       List<Payment> payments) {}

    public record CostRequest(String name, String provider, String url, BigDecimal amount, String currency, String period,
                              LocalDate nextDue, String note, Boolean active) {}

    public record PayRequest(LocalDate paidOn, BigDecimal amount, String note) {}

    public record Summary(BigDecimal monthlyCostAzn, BigDecimal yearlyCostAzn, BigDecimal incomeThisMonth, BigDecimal netThisMonth,
                          BigDecimal paidThisYearAzn, long dueSoon, long overdue, List<Cost> costs) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public PlatformCostService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @GetMapping("/api/admin/costs")
    public Summary summary() {
        tenant.requireSuperAdmin();
        LocalDate today = tenant.today();
        List<Cost> costs = load(today);
        BigDecimal monthly = Money.of(costs.stream().filter(Cost::active).map(Cost::monthlyAzn).reduce(BigDecimal.ZERO, BigDecimal::add));
        LocalDate monthStart = today.withDayOfMonth(1);
        BigDecimal income = Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM subscriptions
                WHERE status IN ('ACTIVE', 'EXPIRED') AND reviewed_at IS NOT NULL
                  AND CAST(reviewed_at AT TIME ZONE 'Asia/Baku' AS date) BETWEEN :a AND :b
                """).param("a", monthStart).param("b", today).query(BigDecimal.class).single());
        BigDecimal paidYear = BigDecimal.ZERO;
        for (var r : jdbc.sql("SELECT amount, currency FROM platform_cost_payments WHERE paid_on >= :y")
                .param("y", today.withDayOfYear(1)).query((rs, n) -> Map.entry(rs.getBigDecimal("amount"), rs.getString("currency"))).list()) {
            paidYear = paidYear.add(toAzn(r.getKey(), r.getValue()));
        }
        long soon = costs.stream().filter(c -> c.active() && ("SOON".equals(c.status()) || "DUE".equals(c.status()))).count();
        long overdue = costs.stream().filter(c -> c.active() && "OVERDUE".equals(c.status())).count();
        return new Summary(monthly, Money.of(monthly.multiply(BigDecimal.valueOf(12))), income, Money.of(income.subtract(monthly)),
                Money.of(paidYear), soon, overdue, costs);
    }

    @PostMapping("/api/admin/costs")
    public Cost create(@RequestBody CostRequest req) {
        tenant.requireSuperAdmin();
        UUID id = UUID.randomUUID();
        validate(req);
        jdbc.sql("""
                INSERT INTO platform_costs (id, name, provider, url, amount, currency, period, next_due, note, active, sort_order)
                VALUES (:id, :n, :p, :u, :a, :c, :per, :d, :note, TRUE, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM platform_costs))
                """).param("id", id).param("n", req.name().trim()).param("p", blank(req.provider())).param("u", blank(req.url()))
                .param("a", Money.of(req.amount() == null ? BigDecimal.ZERO : req.amount())).param("c", currency(req.currency()))
                .param("per", period(req.period())).param("d", req.nextDue()).param("note", blank(req.note())).update();
        return one(id);
    }

    @PutMapping("/api/admin/costs/{id}")
    public Cost update(@PathVariable UUID id, @RequestBody CostRequest req) {
        tenant.requireSuperAdmin();
        validate(req);
        int n = jdbc.sql("""
                UPDATE platform_costs SET name = :n, provider = :p, url = :u, amount = :a, currency = :c, period = :per,
                       next_due = :d, note = :note, active = :act WHERE id = :id
                """).param("n", req.name().trim()).param("p", blank(req.provider())).param("u", blank(req.url()))
                .param("a", Money.of(req.amount() == null ? BigDecimal.ZERO : req.amount())).param("c", currency(req.currency()))
                .param("per", period(req.period())).param("d", req.nextDue()).param("note", blank(req.note()))
                .param("act", req.active() == null || req.active()).param("id", id).update();
        if (n == 0) throw ApiException.notFound("Xərc tapılmadı");
        return one(id);
    }

    /** "Ödədim": the payment is kept and the next due date moves on by the period. */
    @PostMapping("/api/admin/costs/{id}/paid")
    @Transactional
    public Cost paid(@PathVariable UUID id, @RequestBody PayRequest req) {
        tenant.requireSuperAdmin();
        record Row(BigDecimal amount, String currency, String period, LocalDate next) {}
        Row c = jdbc.sql("SELECT amount, currency, period, next_due FROM platform_costs WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, n) -> new Row(rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("period"),
                        rs.getObject("next_due", LocalDate.class))).optional().orElseThrow(() -> ApiException.notFound("Xərc tapılmadı"));
        LocalDate paidOn = req.paidOn() != null ? req.paidOn() : tenant.today();
        BigDecimal amount = Money.of(req.amount() != null ? req.amount() : c.amount());
        if (amount.signum() < 0) throw ApiException.badRequest("INVALID_AMOUNT", "Məbləğ mənfi ola bilməz");
        jdbc.sql("""
                INSERT INTO platform_cost_payments (id, cost_id, paid_on, amount, currency, note, created_by)
                VALUES (:id, :c, :d, :a, :cur, :n, :by)
                """).param("id", UUID.randomUUID()).param("c", id).param("d", paidOn).param("a", amount).param("cur", c.currency())
                .param("n", blank(req.note())).param("by", tenant.currentUserId()).update();
        LocalDate base = c.next() != null ? c.next() : paidOn;
        LocalDate next = switch (c.period()) {
            case "YEARLY" -> base.plusYears(1);
            case "ONCE" -> null;
            default -> base.plusMonths(1);
        };
        jdbc.sql("UPDATE platform_costs SET next_due = :d WHERE id = :id").param("d", next).param("id", id).update();
        return one(id);
    }

    @DeleteMapping("/api/admin/costs/{id}")
    public Map<String, Boolean> remove(@PathVariable UUID id) {
        tenant.requireSuperAdmin();
        boolean used = jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_cost_payments WHERE cost_id = :id)").param("id", id).query(Boolean.class).single();
        if (used) jdbc.sql("UPDATE platform_costs SET active = FALSE WHERE id = :id").param("id", id).update();
        else jdbc.sql("DELETE FROM platform_costs WHERE id = :id").param("id", id).update();
        return Map.of("deleted", !used);
    }

    // ---------- helpers ----------

    private Cost one(UUID id) {
        return load(tenant.today()).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private List<Cost> load(LocalDate today) {
        Map<UUID, List<Payment>> pays = new HashMap<>();
        jdbc.sql("""
                SELECT p.id, p.cost_id, p.paid_on, p.amount, p.currency, p.note, u.full_name, p.created_at
                FROM platform_cost_payments p JOIN users u ON u.id = p.created_by ORDER BY p.paid_on DESC, p.created_at DESC
                """).query((rs, n) -> Map.entry(rs.getObject("cost_id", UUID.class), new Payment(rs.getObject("id", UUID.class),
                        rs.getObject("paid_on", LocalDate.class), Money.of(rs.getBigDecimal("amount")), rs.getString("currency"),
                        rs.getString("note"), rs.getString("full_name"), rs.getObject("created_at", OffsetDateTime.class))))
                .list().forEach(e -> pays.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return jdbc.sql("""
                SELECT id, name, provider, url, amount, currency, period, next_due, note, active
                FROM platform_costs ORDER BY active DESC, sort_order, created_at
                """).query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    BigDecimal amount = Money.of(rs.getBigDecimal("amount"));
                    String currency = rs.getString("currency");
                    String period = rs.getString("period");
                    LocalDate due = rs.getObject("next_due", LocalDate.class);
                    Long days = due == null ? null : ChronoUnit.DAYS.between(today, due);
                    String status = amount.signum() == 0 && !"YEARLY".equals(period) ? "FREE"
                            : due == null ? "NO_DATE" : days < 0 ? "OVERDUE" : days == 0 ? "DUE" : days <= 7 ? "SOON" : "OK";
                    BigDecimal monthly = switch (period) {
                        case "YEARLY" -> toAzn(amount, currency).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
                        case "ONCE" -> BigDecimal.ZERO;
                        default -> toAzn(amount, currency);
                    };
                    List<Payment> list = pays.getOrDefault(id, List.of());
                    return new Cost(id, rs.getString("name"), rs.getString("provider"), rs.getString("url"), amount, currency, period,
                            due, days, status, rs.getString("note"), rs.getBoolean("active"), Money.of(monthly),
                            list.size() > 12 ? list.subList(0, 12) : list);
                }).list();
    }

    private static BigDecimal toAzn(BigDecimal amount, String currency) {
        return amount.multiply(TO_AZN.getOrDefault(currency, BigDecimal.ONE));
    }

    private static void validate(CostRequest req) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 100) {
            throw ApiException.badRequest("INVALID_NAME", "Xərcin adını yazın");
        }
        if (req.amount() != null && req.amount().signum() < 0) throw ApiException.badRequest("INVALID_AMOUNT", "Məbləğ mənfi ola bilməz");
        if (req.url() != null && !req.url().isBlank() && !req.url().trim().startsWith("https://")) {
            throw ApiException.badRequest("INVALID_URL", "Keçid https:// ilə başlamalıdır");
        }
    }

    private static String currency(String c) {
        return c != null && TO_AZN.containsKey(c) ? c : "USD";
    }

    private static String period(String p) {
        return "YEARLY".equals(p) || "ONCE".equals(p) ? p : "MONTHLY";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
