package az.borcdefteri.sales;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Kassir hesabatı: per cashier for a period — sales (cash / card / on credit), discounts given, returns made,
 * sales cancelled and the shortage / surplus of their closed shifts. Helps the owner see who does what at the till.
 */
@Service
@RestController
public class CashierReportService {

    public record CashierRow(UUID userId, String name, String jobTitle, boolean active,
                             long salesCount, BigDecimal salesTotal, BigDecimal paidCash, BigDecimal paidCard,
                             BigDecimal credit, BigDecimal discount, BigDecimal averageSale,
                             long returnsCount, BigDecimal returnsTotal,
                             long cancelledCount, BigDecimal cancelledTotal,
                             long shiftsClosed, BigDecimal shiftShortage, BigDecimal shiftSurplus) {}

    public record CashierReport(LocalDate from, LocalDate to, List<CashierRow> rows) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public CashierReportService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @GetMapping("/api/reports/cashiers")
    public CashierReport report(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        LocalDate hi = to != null ? to : tenant.today();
        LocalDate lo = from != null ? from : hi.withDayOfMonth(1);
        if (lo.isAfter(hi) || lo.isBefore(hi.minusYears(2))) {
            throw ApiException.badRequest("INVALID_PERIOD", "Dövr düzgün deyil (ən çox 2 il)");
        }
        List<CashierRow> rows = jdbc.sql("""
                WITH members AS (
                    SELECT u.id, u.full_name, sm.job_title, sm.active, sm.role
                    FROM store_members sm JOIN users u ON u.id = sm.user_id WHERE sm.store_id = :s
                ), s AS (
                    SELECT created_by, COUNT(*) AS n, SUM(total) AS total, SUM(paid_cash) AS cash, SUM(paid_card) AS card,
                           SUM(credit_amount) AS credit, SUM(discount) AS discount
                    FROM sales WHERE store_id = :s AND status = 'COMPLETED' AND sold_on BETWEEN :lo AND :hi
                    GROUP BY created_by
                ), r AS (
                    SELECT rt.created_by, COUNT(*) AS n, SUM(rt.amount) AS total
                    FROM sale_returns rt JOIN sales sr ON sr.id = rt.sale_id
                    WHERE rt.store_id = :s AND rt.returned_on BETWEEN :lo AND :hi AND sr.status = 'COMPLETED'
                    GROUP BY rt.created_by
                ), c AS (
                    SELECT a.actor_id, COUNT(*) AS n, COALESCE(SUM(sa.total), 0) AS total
                    FROM audit_logs a LEFT JOIN sales sa ON sa.id::text = a.entity_id
                    WHERE a.store_id = :s AND a.action = 'SALE_CANCELLED'
                      AND CAST(a.created_at AT TIME ZONE 'Asia/Baku' AS date) BETWEEN :lo AND :hi
                    GROUP BY a.actor_id
                ), sh AS (
                    SELECT user_id, COUNT(*) AS n,
                           SUM(CASE WHEN difference < 0 THEN -difference ELSE 0 END) AS shortage,
                           SUM(CASE WHEN difference > 0 THEN difference ELSE 0 END) AS surplus
                    FROM cash_shifts WHERE store_id = :s AND closed_at IS NOT NULL
                      AND CAST(closed_at AT TIME ZONE 'Asia/Baku' AS date) BETWEEN :lo AND :hi
                    GROUP BY user_id
                )
                SELECT mb.id, mb.full_name, mb.job_title, mb.active, mb.role,
                       COALESCE(s.n, 0) AS sales_n, COALESCE(s.total, 0) AS sales_total, COALESCE(s.cash, 0) AS cash,
                       COALESCE(s.card, 0) AS card, COALESCE(s.credit, 0) AS credit, COALESCE(s.discount, 0) AS discount,
                       COALESCE(r.n, 0) AS ret_n, COALESCE(r.total, 0) AS ret_total,
                       COALESCE(c.n, 0) AS can_n, COALESCE(c.total, 0) AS can_total,
                       COALESCE(sh.n, 0) AS sh_n, COALESCE(sh.shortage, 0) AS shortage, COALESCE(sh.surplus, 0) AS surplus
                FROM members mb
                LEFT JOIN s ON s.created_by = mb.id
                LEFT JOIN r ON r.created_by = mb.id
                LEFT JOIN c ON c.actor_id = mb.id
                LEFT JOIN sh ON sh.user_id = mb.id
                WHERE mb.active OR s.n IS NOT NULL OR r.n IS NOT NULL OR c.n IS NOT NULL OR sh.n IS NOT NULL
                ORDER BY COALESCE(s.total, 0) DESC, mb.full_name
                """)
                .param("s", m.storeId()).param("lo", lo).param("hi", hi)
                .query((rs, n) -> {
                    long count = rs.getLong("sales_n");
                    BigDecimal total = Money.of(rs.getBigDecimal("sales_total"));
                    return new CashierRow(rs.getObject("id", UUID.class), rs.getString("full_name"),
                            "STORE_OWNER".equals(rs.getString("role")) ? "OWNER" : rs.getString("job_title"),
                            rs.getBoolean("active"), count, total,
                            Money.of(rs.getBigDecimal("cash")), Money.of(rs.getBigDecimal("card")),
                            Money.of(rs.getBigDecimal("credit")), Money.of(rs.getBigDecimal("discount")),
                            count == 0 ? Money.zero() : total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP),
                            rs.getLong("ret_n"), Money.of(rs.getBigDecimal("ret_total")),
                            rs.getLong("can_n"), Money.of(rs.getBigDecimal("can_total")),
                            rs.getLong("sh_n"), Money.of(rs.getBigDecimal("shortage")), Money.of(rs.getBigDecimal("surplus")));
                }).list();
        return new CashierReport(lo, hi, rows);
    }
}
