package az.borcdefteri.employees;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.expenses.ExpenseService;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * İşçi maaşı. Each employee has a monthly salary or an hourly rate, and a deduction per late day. For a month the
 * owner sees: days and hours from Davamiyyət, late days, the gross pay, the late deduction, the advances given and
 * what was already paid, and what is left to pay. An advance or a salary payment is also an "İşçi maaşı" expense,
 * so it leaves the cash desk and counts in the profit.
 */
@Service
@RestController
@RequestMapping("/api/payroll")
public class PayrollService {

    static final UUID SALARY_CATEGORY = UUID.fromString("10000000-0000-0000-0000-000000000002");

    public record Settings(@Pattern(regexp = "MONTHLY|HOURLY", message = "Maaş növü: aylıq və ya saatlıq") String payType,
                           @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal rate,
                           @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal latePenalty) {}

    public record Money2(@NotNull @DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
                         @Digits(integer = 12, fraction = 2) BigDecimal amount,
                         /** "2026-11"; null = this month. */
                         @Pattern(regexp = "\\d{4}-\\d{2}") String month,
                         @Pattern(regexp = "CASH|CARD|TRANSFER") String paymentMethod,
                         @Size(max = 300) String note) {}

    public record Row(UUID userId, String fullName, String jobTitle, String payType, BigDecimal rate, BigDecimal latePenalty,
                      long days, long minutes, long lateDays, BigDecimal gross, BigDecimal lateDeduction, BigDecimal advances,
                      BigDecimal paid, BigDecimal toPay) {}

    public record Sheet(String month, List<Row> rows, BigDecimal totalGross, BigDecimal totalToPay) {}

    public record Entry(UUID id, String kind, BigDecimal amount, String month, String note, String createdByName, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final ExpenseService expenses;
    private final AuditService audit;

    public PayrollService(JdbcClient jdbc, TenantService tenant, ExpenseService expenses, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.expenses = expenses;
        this.audit = audit;
    }

    @GetMapping
    public Sheet sheet(@RequestParam(required = false) String month) {
        CurrentMember m = tenant.requireOwner();
        YearMonth ym = month(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        List<Row> rows = jdbc.sql("""
                SELECT u.id, u.full_name, sm.job_title, sm.pay_type, sm.pay_rate, sm.late_penalty,
                       (SELECT COUNT(DISTINCT a.work_date) FROM attendance a WHERE a.store_id = sm.store_id AND a.user_id = u.id
                          AND a.work_date BETWEEN :f AND :t) AS days,
                       (SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(a.check_out, now()) - a.check_in)) / 60), 0)::bigint
                          FROM attendance a WHERE a.store_id = sm.store_id AND a.user_id = u.id AND a.work_date BETWEEN :f AND :t) AS minutes,
                       (SELECT COUNT(DISTINCT a.work_date) FROM attendance a WHERE a.store_id = sm.store_id AND a.user_id = u.id
                          AND a.late AND a.work_date BETWEEN :f AND :t) AS late_days,
                       (SELECT COALESCE(SUM(e.amount), 0) FROM payroll_entries e WHERE e.store_id = sm.store_id AND e.user_id = u.id
                          AND e.month = :f AND e.kind = 'ADVANCE') AS advances,
                       (SELECT COALESCE(SUM(e.amount), 0) FROM payroll_entries e WHERE e.store_id = sm.store_id AND e.user_id = u.id
                          AND e.month = :f AND e.kind = 'PAYMENT') AS paid
                FROM store_members sm JOIN users u ON u.id = sm.user_id
                WHERE sm.store_id = :s AND sm.role = 'EMPLOYEE' AND sm.active
                ORDER BY u.full_name""").param("s", m.storeId()).param("f", from).param("t", to)
                .query((rs, n) -> {
                    String type = rs.getString("pay_type");
                    BigDecimal rate = rs.getBigDecimal("pay_rate") == null ? null : Money.of(rs.getBigDecimal("pay_rate"));
                    BigDecimal penalty = Money.of(rs.getBigDecimal("late_penalty"));
                    long minutes = rs.getLong("minutes");
                    long late = rs.getLong("late_days");
                    BigDecimal gross = rate == null || type == null ? Money.zero()
                            : "HOURLY".equals(type) ? Money.of(rate.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP))
                            : rate;
                    BigDecimal lateDeduction = Money.of(penalty.multiply(BigDecimal.valueOf(late)));
                    BigDecimal advances = Money.of(rs.getBigDecimal("advances"));
                    BigDecimal paid = Money.of(rs.getBigDecimal("paid"));
                    BigDecimal toPay = Money.of(gross.subtract(lateDeduction).subtract(advances).subtract(paid).max(BigDecimal.ZERO));
                    return new Row(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("job_title"), type, rate,
                            penalty, rs.getLong("days"), minutes, late, gross, lateDeduction, advances, paid, toPay);
                }).list();
        return new Sheet(ym.toString(), rows, Money.of(rows.stream().map(Row::gross).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.of(rows.stream().map(Row::toPay).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @PutMapping("/{userId}/settings")
    @Transactional
    public Sheet settings(@PathVariable UUID userId, @Valid @RequestBody Settings req) {
        CurrentMember m = tenant.requireOwner();
        employee(m, userId);
        if (req.payType() != null && req.rate() == null) throw ApiException.badRequest("RATE_REQUIRED", "Maaşın məbləğini yazın");
        jdbc.sql("UPDATE store_members SET pay_type = :t, pay_rate = :r, late_penalty = :p WHERE store_id = :s AND user_id = :u")
                .param("t", req.payType()).param("r", req.payType() == null ? null : Money.of(req.rate()))
                .param("p", Money.of(req.latePenalty() == null ? BigDecimal.ZERO : req.latePenalty()))
                .param("s", m.storeId()).param("u", userId).update();
        audit.log(m.storeId(), m.userId(), "PAYROLL_SETTINGS", "USER", userId.toString(),
                req.payType() + " " + req.rate() + ", gecikmə " + req.latePenalty());
        return sheet(null);
    }

    /** Avans: money given ahead of the salary; it is taken off that month's pay. */
    @PostMapping("/{userId}/advance")
    @Transactional
    public Sheet advance(@PathVariable UUID userId, @Valid @RequestBody Money2 req) {
        return entry(userId, req, "ADVANCE");
    }

    /** Maaş ödənişi (all or part of what is left for the month). */
    @PostMapping("/{userId}/pay")
    @Transactional
    public Sheet pay(@PathVariable UUID userId, @Valid @RequestBody Money2 req) {
        return entry(userId, req, "PAYMENT");
    }

    @GetMapping("/{userId}/entries")
    public List<Entry> entries(@PathVariable UUID userId, @RequestParam(required = false) String month) {
        CurrentMember m = tenant.requireOwner();
        employee(m, userId);
        return jdbc.sql("""
                SELECT e.*, u.full_name FROM payroll_entries e JOIN users u ON u.id = e.created_by
                WHERE e.store_id = :s AND e.user_id = :u AND (:all OR e.month = :mo) ORDER BY e.created_at DESC LIMIT 200""")
                .param("s", m.storeId()).param("u", userId).param("all", month == null || month.isBlank())
                .param("mo", month(month).atDay(1))
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("kind"), Money.of(rs.getBigDecimal("amount")),
                        YearMonth.from(rs.getObject("month", LocalDate.class)).toString(), rs.getString("note"),
                        rs.getString("full_name"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private Sheet entry(UUID userId, Money2 req, String kind) {
        CurrentMember m = tenant.requireOwner();
        String name = employee(m, userId);
        YearMonth ym = month(req.month());
        String label = "ADVANCE".equals(kind) ? "Avans" : "Maaş";
        var expense = expenses.create(new ExpenseService.ExpenseRequest(label + " — " + name + " (" + ym + ")", SALARY_CATEGORY,
                req.amount(), tenant.today(), req.paymentMethod() == null ? "CASH" : req.paymentMethod(), null, name,
                req.note(), null, null));
        jdbc.sql("""
                INSERT INTO payroll_entries (id, store_id, user_id, month, kind, amount, expense_id, note, created_by)
                VALUES (:id, :s, :u, :mo, :k, :a, :e, :n, :by)""")
                .param("id", UUID.randomUUID()).param("s", m.storeId()).param("u", userId).param("mo", ym.atDay(1)).param("k", kind)
                .param("a", Money.of(req.amount())).param("e", expense.id())
                .param("n", req.note() == null || req.note().isBlank() ? null : req.note().trim()).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "PAYROLL_" + kind, "USER", userId.toString(), name + ": " + Money.of(req.amount()) + " AZN (" + ym + ")");
        return sheet(ym.toString());
    }

    private String employee(CurrentMember m, UUID userId) {
        return jdbc.sql("""
                SELECT u.full_name FROM store_members sm JOIN users u ON u.id = sm.user_id
                WHERE sm.store_id = :s AND sm.user_id = :u AND sm.role = 'EMPLOYEE'""")
                .param("s", m.storeId()).param("u", userId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("İşçi tapılmadı"));
    }

    private YearMonth month(String month) {
        if (month == null || month.isBlank()) return YearMonth.from(tenant.today());
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_MONTH", "Ay İİİİ-AA formatında olmalıdır (məs. 2026-11)");
        }
    }
}
