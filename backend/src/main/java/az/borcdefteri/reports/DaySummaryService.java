package az.borcdefteri.reports;

import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gün sonu hesabatı: one day of the store in a few numbers — sales (cash / card / on credit), gross profit,
 * returns, expenses, debts collected, new customer debts and what is in each till / bank account now.
 * The phone and the web turn it into a WhatsApp message for the owner.
 */
@Service
@RestController
public class DaySummaryService {

    public record Account(String name, String kind, BigDecimal balance) {}

    public record DaySummary(LocalDate date, String storeName,
                             long salesCount, BigDecimal salesTotal, BigDecimal paidCash, BigDecimal paidCard,
                             BigDecimal credit, BigDecimal grossProfit, long returnsCount, BigDecimal returnsTotal,
                             BigDecimal expensesTotal, BigDecimal debtsCollected, BigDecimal supplierPaid,
                             List<Account> accounts, List<Map<String, Object>> topProducts) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public DaySummaryService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @GetMapping("/api/reports/day-summary")
    public DaySummary summary(@RequestParam(required = false) LocalDate date) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        return summaryFor(m.storeId(), m.storeName(), date != null ? date : tenant.today());
    }

    /** The same day summary for any store (the evening Telegram report, no signed-in user). */
    public DaySummary summaryFor(UUID s, String storeName, LocalDate day) {

        record Sales(long n, BigDecimal total, BigDecimal cash, BigDecimal card, BigDecimal credit, BigDecimal cost) {}
        Sales sales = jdbc.sql("""
                SELECT COUNT(*) AS n, COALESCE(SUM(total), 0) AS total, COALESCE(SUM(paid_cash), 0) AS cash,
                       COALESCE(SUM(paid_card), 0) AS card, COALESCE(SUM(credit_amount), 0) AS credit,
                       COALESCE(SUM(cost_total), 0) AS cost
                FROM sales WHERE store_id = :s AND status = 'COMPLETED' AND sold_on = :d
                """).param("s", s).param("d", day)
                .query((rs, n) -> new Sales(rs.getLong("n"), rs.getBigDecimal("total"), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("card"), rs.getBigDecimal("credit"), rs.getBigDecimal("cost"))).single();
        record Returns(long n, BigDecimal total, BigDecimal cost) {}
        Returns returns = jdbc.sql("""
                SELECT COUNT(*) AS n, COALESCE(SUM(r.amount), 0) AS total, COALESCE(SUM(r.cost_amount), 0) AS cost
                FROM sale_returns r JOIN sales sa ON sa.id = r.sale_id
                WHERE r.store_id = :s AND r.returned_on = :d AND sa.status = 'COMPLETED'
                """).param("s", s).param("d", day)
                .query((rs, n) -> new Returns(rs.getLong("n"), rs.getBigDecimal("total"), rs.getBigDecimal("cost"))).single();
        BigDecimal expenses = jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE store_id = :s AND expense_date = :d AND deleted_at IS NULL
                """).param("s", s).param("d", day).query(BigDecimal.class).single();
        BigDecimal collected = sumPayments(s, day, "d.debt_type IN ('CUSTOMER_DEBT', 'COMPANY_RECEIVABLE')");
        BigDecimal supplierPaid = sumPayments(s, day, "d.debt_type = 'COMPANY_PAYABLE'");
        List<Account> accounts = jdbc.sql("""
                SELECT a.name, a.kind,
                       COALESCE(SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END), 0) AS balance
                FROM cash_accounts a LEFT JOIN cash_transactions t ON t.account_id = a.id AND t.store_id = a.store_id
                WHERE a.store_id = :s AND a.active
                GROUP BY a.id, a.name, a.kind, a.is_default ORDER BY a.is_default DESC, a.name
                """).param("s", s)
                .query((rs, n) -> new Account(rs.getString("name"), rs.getString("kind"), Money.of(rs.getBigDecimal("balance")))).list();
        List<Map<String, Object>> top = jdbc.sql("""
                SELECT i.name, SUM(i.quantity - i.returned_qty) AS qty, SUM(i.line_total) AS total
                FROM sale_items i JOIN sales sa ON sa.id = i.sale_id
                WHERE sa.store_id = :s AND sa.status = 'COMPLETED' AND sa.sold_on = :d
                GROUP BY i.name ORDER BY SUM(i.line_total) DESC LIMIT 3
                """).param("s", s).param("d", day)
                .query((rs, n) -> Map.<String, Object>of("name", rs.getString("name"),
                        "quantity", rs.getBigDecimal("qty").stripTrailingZeros(), "total", Money.of(rs.getBigDecimal("total")))).list();

        BigDecimal profit = sales.total().subtract(sales.cost()).subtract(returns.total().subtract(returns.cost()));
        return new DaySummary(day, storeName, sales.n(), Money.of(sales.total()), Money.of(sales.cash()), Money.of(sales.card()),
                Money.of(sales.credit()), Money.of(profit), returns.n(), Money.of(returns.total()), Money.of(expenses),
                collected, supplierPaid, accounts, top);
    }

    private BigDecimal sumPayments(UUID store, LocalDate day, String typeCondition) {
        return Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(p.amount), 0) FROM payments p JOIN debts d ON d.id = p.debt_id
                WHERE p.store_id = :s AND p.voided_at IS NULL
                  AND CAST(p.paid_at AT TIME ZONE 'Asia/Baku' AS date) = :d AND\s""" + typeCondition)
                .param("s", store).param("d", day).query(BigDecimal.class).single());
    }
}
