package az.borcdefteri.dashboard;

import az.borcdefteri.common.Money;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.payments.PaymentService;
import az.borcdefteri.payments.PaymentService.PaymentView;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** All dashboard figures are computed in PostgreSQL from the store's own rows. */
@Service
public class DashboardService {

    public static final String TZ = "Asia/Baku";

    public record Summary(BigDecimal customerDebts, BigDecimal companyReceivables, BigDecimal companyPayables,
                          BigDecimal cashReceived, BigDecimal cardReceived,
                          BigDecimal todayTotal, BigDecimal todayCash, BigDecimal todayCard,
                          BigDecimal monthTotal, BigDecimal monthCash, BigDecimal monthCard) {}

    public record DailyPoint(LocalDate date, BigDecimal cash, BigDecimal card, BigDecimal total) {}

    public record MonthlyPoint(String month, BigDecimal cash, BigDecimal card, BigDecimal total) {}

    public record OutstandingDebt(UUID id, DebtType debtType, String partyName, BigDecimal remaining,
                                  LocalDate dueDate, boolean overdue) {}

    public record Dashboard(Summary summary, List<DailyPoint> daily, List<MonthlyPoint> monthly,
                            List<PaymentView> recentTransactions, List<OutstandingDebt> outstanding) {}

    public record Notification(String type, String severity, String title, String message, LocalDate date) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PaymentService payments;

    public DashboardService(JdbcClient jdbc, TenantService tenant, PaymentService payments) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.payments = payments;
    }

    public Dashboard dashboard() {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        LocalDate today = tenant.today();
        LocalDate monthStart = today.withDayOfMonth(1);

        Map<DebtType, BigDecimal> remaining = new HashMap<>();
        jdbc.sql("SELECT debt_type, COALESCE(SUM(remaining_amount), 0) AS r FROM debts WHERE store_id = :s GROUP BY debt_type")
                .param("s", m.storeId())
                .query((rs, n) -> {
                    remaining.put(DebtType.valueOf(rs.getString("debt_type")), Money.of(rs.getBigDecimal("r")));
                    return null;
                }).list();

        BigDecimal cashAll = BigDecimal.ZERO;
        BigDecimal cardAll = BigDecimal.ZERO;
        for (Map<String, Object> row : jdbc.sql("""
                SELECT payment_method, COALESCE(SUM(amount), 0) AS total FROM payments WHERE store_id = :s
                GROUP BY payment_method""").param("s", m.storeId()).query().listOfRows()) {
            BigDecimal total = (BigDecimal) row.get("total");
            if ("CASH".equals(row.get("payment_method"))) {
                cashAll = total;
            } else {
                cardAll = total;
            }
        }

        Map<String, BigDecimal> todayMap = byMethod(m.storeId(), today, today);
        Map<String, BigDecimal> monthMap = byMethod(m.storeId(), monthStart, today);
        Summary summary = new Summary(
                remaining.getOrDefault(DebtType.CUSTOMER_DEBT, Money.zero()),
                remaining.getOrDefault(DebtType.COMPANY_RECEIVABLE, Money.zero()),
                remaining.getOrDefault(DebtType.COMPANY_PAYABLE, Money.zero()),
                Money.of(cashAll), Money.of(cardAll),
                todayMap.get("CASH").add(todayMap.get("CARD")), todayMap.get("CASH"), todayMap.get("CARD"),
                monthMap.get("CASH").add(monthMap.get("CARD")), monthMap.get("CASH"), monthMap.get("CARD"));

        return new Dashboard(summary, daily(m.storeId(), today), monthly(m.storeId(), today),
                payments.list(null, null, null, null, 10), outstanding(m.storeId(), today));
    }

    /** Cash/card totals for a date range (business time zone, inclusive). */
    private Map<String, BigDecimal> byMethod(UUID storeId, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> out = new HashMap<>(Map.of("CASH", Money.zero(), "CARD", Money.zero()));
        jdbc.sql("""
                SELECT payment_method, COALESCE(SUM(amount), 0) AS total FROM payments
                WHERE store_id = :s AND (paid_at AT TIME ZONE :tz)::date BETWEEN :from AND :to
                GROUP BY payment_method""")
                .param("s", storeId).param("tz", TZ).param("from", from).param("to", to)
                .query((rs, n) -> {
                    out.put(rs.getString("payment_method"), Money.of(rs.getBigDecimal("total")));
                    return null;
                }).list();
        return out;
    }

    private List<DailyPoint> daily(UUID storeId, LocalDate today) {
        LocalDate from = today.minusDays(13);
        Map<LocalDate, Map<String, BigDecimal>> data = new HashMap<>();
        jdbc.sql("""
                SELECT (paid_at AT TIME ZONE :tz)::date AS d, payment_method, COALESCE(SUM(amount), 0) AS total
                FROM payments WHERE store_id = :s AND (paid_at AT TIME ZONE :tz)::date BETWEEN :from AND :to
                GROUP BY 1, 2""")
                .param("tz", TZ).param("s", storeId).param("from", from).param("to", today)
                .query((rs, n) -> {
                    data.computeIfAbsent(rs.getObject("d", LocalDate.class), k -> new HashMap<>())
                            .put(rs.getString("payment_method"), Money.of(rs.getBigDecimal("total")));
                    return null;
                }).list();
        List<DailyPoint> points = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            Map<String, BigDecimal> day = data.getOrDefault(d, Map.of());
            BigDecimal cash = day.getOrDefault("CASH", Money.zero());
            BigDecimal card = day.getOrDefault("CARD", Money.zero());
            points.add(new DailyPoint(d, cash, card, cash.add(card)));
        }
        return points;
    }

    private List<MonthlyPoint> monthly(UUID storeId, LocalDate today) {
        LocalDate from = today.withDayOfMonth(1).minusMonths(5);
        Map<YearMonth, Map<String, BigDecimal>> data = new HashMap<>();
        jdbc.sql("""
                SELECT date_trunc('month', paid_at AT TIME ZONE :tz)::date AS m, payment_method,
                       COALESCE(SUM(amount), 0) AS total
                FROM payments WHERE store_id = :s AND (paid_at AT TIME ZONE :tz)::date >= :from
                GROUP BY 1, 2""")
                .param("tz", TZ).param("s", storeId).param("from", from)
                .query((rs, n) -> {
                    YearMonth ym = YearMonth.from(rs.getObject("m", LocalDate.class));
                    data.computeIfAbsent(ym, k -> new HashMap<>())
                            .put(rs.getString("payment_method"), Money.of(rs.getBigDecimal("total")));
                    return null;
                }).list();
        List<MonthlyPoint> points = new ArrayList<>();
        YearMonth start = YearMonth.from(from);
        YearMonth end = YearMonth.from(today);
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            Map<String, BigDecimal> month = data.getOrDefault(ym, Map.of());
            BigDecimal cash = month.getOrDefault("CASH", Money.zero());
            BigDecimal card = month.getOrDefault("CARD", Money.zero());
            points.add(new MonthlyPoint(ym.toString(), cash, card, cash.add(card)));
        }
        return points;
    }

    private List<OutstandingDebt> outstanding(UUID storeId, LocalDate today) {
        return jdbc.sql("""
                SELECT d.id, d.debt_type, COALESCE(c.name, co.name) AS party_name, d.remaining_amount, d.due_date
                FROM debts d
                LEFT JOIN customers c ON c.id = d.customer_id
                LEFT JOIN companies co ON co.id = d.company_id
                WHERE d.store_id = :s AND d.status <> 'PAID'
                ORDER BY d.remaining_amount DESC LIMIT 10
                """).param("s", storeId)
                .query((rs, n) -> {
                    LocalDate due = rs.getObject("due_date", LocalDate.class);
                    return new OutstandingDebt(rs.getObject("id", UUID.class),
                            DebtType.valueOf(rs.getString("debt_type")), rs.getString("party_name"),
                            Money.of(rs.getBigDecimal("remaining_amount")), due,
                            due != null && due.isBefore(today));
                }).list();
    }

    /** In-app notifications: subscription renewal reminders and debts due soon or overdue. */
    public List<Notification> notifications() {
        CurrentMember m = tenant.requireMember();
        List<Notification> out = new ArrayList<>();
        LocalDate today = tenant.today();
        LocalDate subEnd = jdbc.sql("""
                SELECT MAX(end_date) FROM subscriptions WHERE store_id = :s AND status = 'ACTIVE' AND end_date >= :today
                """).param("s", m.storeId()).param("today", today).query(LocalDate.class).optional().orElse(null);
        if (subEnd != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, subEnd);
            if (days <= 7) {
                out.add(new Notification("SUBSCRIPTION_RENEWAL", days <= 2 ? "DANGER" : "WARNING",
                        "Abunəliyin yenilənməsi",
                        "Abunəliyinizin bitmə tarixi: " + subEnd + " (" + days + " gün qalıb)", subEnd));
            }
        }
        jdbc.sql("""
                SELECT s.status FROM subscriptions s WHERE s.store_id = :s AND s.status IN ('PENDING', 'REJECTED')
                ORDER BY s.requested_at DESC LIMIT 1
                """).param("s", m.storeId()).query(String.class).optional().ifPresent(status -> out.add(
                "PENDING".equals(status)
                        ? new Notification("SUBSCRIPTION_PENDING", "INFO", "Ödəniş təsdiqi gözlənilir",
                        "Abunəlik sorğunuz təsdiq gözləyir", today)
                        : new Notification("SUBSCRIPTION_REJECTED", "DANGER", "Abunəlik rədd edildi",
                        "Son abunəlik sorğunuz rədd edilib. Yenidən göndərə bilərsiniz", today)));

        jdbc.sql("""
                SELECT d.debt_type, COALESCE(c.name, co.name) AS party_name, d.remaining_amount, d.due_date
                FROM debts d
                LEFT JOIN customers c ON c.id = d.customer_id
                LEFT JOIN companies co ON co.id = d.company_id
                WHERE d.store_id = :s AND d.status <> 'PAID' AND d.due_date IS NOT NULL AND d.due_date <= :horizon
                ORDER BY d.due_date LIMIT 20
                """).param("s", m.storeId()).param("horizon", today.plusDays(3))
                .query((rs, n) -> {
                    DebtType type = DebtType.valueOf(rs.getString("debt_type"));
                    if (!m.has(type.permission())) {
                        return null;
                    }
                    LocalDate due = rs.getObject("due_date", LocalDate.class);
                    boolean overdue = due.isBefore(today);
                    return new Notification(overdue ? "DEBT_OVERDUE" : "DEBT_DUE_SOON",
                            overdue ? "DANGER" : "WARNING",
                            overdue ? "Vaxtı keçmiş borc" : "Yaxınlaşan ödəniş tarixi",
                            rs.getString("party_name") + " — " + rs.getBigDecimal("remaining_amount")
                                    + " AZN (son tarix: " + due + ")", due);
                }).list().stream().filter(java.util.Objects::nonNull).forEach(out::add);
        return out;
    }
}
