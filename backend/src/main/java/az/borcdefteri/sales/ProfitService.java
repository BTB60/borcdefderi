package az.borcdefteri.sales;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Profit and loss from correctly classified figures only:
 * net sales = sales − returns; cost of goods = purchase price of goods sold − of goods returned;
 * gross profit = net sales − cost of goods; net result = gross profit − expenses.
 * Debt collections, supplier payments, transfers and owner deposits are shown separately and never counted.
 */
@Service
public class ProfitService {

    public record Pnl(LocalDate from, LocalDate to, BigDecimal sales, BigDecimal returns, BigDecimal netSales, BigDecimal costOfGoods,
                      BigDecimal grossProfit, BigDecimal expenses, BigDecimal netResult, long saleCount,
                      BigDecimal salesPaidNow, BigDecimal salesOnCredit, BigDecimal debtCollections, BigDecimal supplierPayments,
                      Comparison previous) {}

    public record Comparison(LocalDate from, LocalDate to, BigDecimal netSales, BigDecimal expenses, BigDecimal netResult) {}

    public record TopProduct(UUID productId, String name, BigDecimal quantity, BigDecimal revenue, BigDecimal profit) {}

    public record MonthPoint(String month, BigDecimal netSales, BigDecimal costOfGoods, BigDecimal expenses, BigDecimal netResult) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public ProfitService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    private CurrentMember require() {
        return tenant.requirePermission(TenantService.PERM_REPORTS);
    }

    public Pnl pnl(LocalDate from, LocalDate to) {
        CurrentMember m = require();
        LocalDate today = tenant.today();
        LocalDate lo = from != null ? from : today.withDayOfMonth(1);
        LocalDate hi = to != null ? to : today;
        if (lo.isAfter(hi)) throw ApiException.badRequest("INVALID_RANGE", "Başlanğıc tarix son tarixdən sonra ola bilməz");
        UUID s = m.storeId();
        Totals t = totals(s, lo, hi);
        long days = ChronoUnit.DAYS.between(lo, hi) + 1;
        LocalDate pHi = lo.minusDays(1), pLo = pHi.minusDays(days - 1);
        if (lo.getDayOfMonth() == 1 && hi.equals(hi.withDayOfMonth(hi.lengthOfMonth())) || (lo.getDayOfMonth() == 1 && hi.equals(today))) {
            // a calendar month (or this month so far) is compared with the same days of the previous month
            pLo = lo.minusMonths(1);
            pHi = pLo.plusDays(Math.min(days, pLo.lengthOfMonth()) - 1);
        }
        Totals p = totals(s, pLo, pHi);
        BigDecimal paidNow = sum("SELECT COALESCE(SUM(paid_cash + paid_card), 0) FROM sales WHERE store_id = :s AND sold_on BETWEEN :f AND :t", s, lo, hi);
        BigDecimal credit = sum("SELECT COALESCE(SUM(credit_amount), 0) FROM sales WHERE store_id = :s AND sold_on BETWEEN :f AND :t", s, lo, hi);
        // cancelled / corrected debt payments are booked back as PAYMENT_REVERSAL in the opposite direction
        BigDecimal collections = sum("SELECT COALESCE(SUM(CASE WHEN kind = 'DEBT_COLLECTION' THEN amount ELSE -amount END), 0) FROM cash_transactions WHERE store_id = :s AND (kind = 'DEBT_COLLECTION' OR (kind = 'PAYMENT_REVERSAL' AND direction = 'OUT')) AND occurred_on BETWEEN :f AND :t", s, lo, hi);
        BigDecimal supplier = sum("SELECT COALESCE(SUM(CASE WHEN kind = 'SUPPLIER_PAYMENT' THEN amount ELSE -amount END), 0) FROM cash_transactions WHERE store_id = :s AND (kind = 'SUPPLIER_PAYMENT' OR (kind = 'PAYMENT_REVERSAL' AND direction = 'IN')) AND occurred_on BETWEEN :f AND :t", s, lo, hi);
        long count = jdbc.sql("SELECT COUNT(*) FROM sales WHERE store_id = :s AND sold_on BETWEEN :f AND :t")
                .param("s", s).param("f", lo).param("t", hi).query(Long.class).single();
        return new Pnl(lo, hi, t.sales, t.returns, t.netSales(), t.cogs(), t.gross(), t.expenses, t.net(), count, paidNow, credit,
                collections, supplier, new Comparison(pLo, pHi, p.netSales(), p.expenses, p.net()));
    }

    /** Last {@code months} calendar months (current month so far included). */
    public List<MonthPoint> monthly(int months) {
        CurrentMember m = require();
        int n = Math.min(Math.max(months, 1), 36);
        YearMonth end = YearMonth.from(tenant.today());
        List<MonthPoint> out = new ArrayList<>();
        for (YearMonth ym = end.minusMonths(n - 1); !ym.isAfter(end); ym = ym.plusMonths(1)) {
            Totals t = totals(m.storeId(), ym.atDay(1), ym.atEndOfMonth());
            out.add(new MonthPoint(ym.toString(), t.netSales(), t.cogs(), t.expenses, t.net()));
        }
        return out;
    }

    /** Best-selling products in the period by sales amount, net of returned quantities; cancelled sales excluded. */
    public List<TopProduct> topProducts(LocalDate from, LocalDate to, int limit) {
        CurrentMember m = require();
        LocalDate today = tenant.today();
        LocalDate lo = from != null ? from : today.withDayOfMonth(1);
        LocalDate hi = to != null ? to : today;
        if (lo.isAfter(hi)) throw ApiException.badRequest("INVALID_RANGE", "Başlanğıc tarix son tarixdən sonra ola bilməz");
        int n = Math.min(Math.max(limit, 1), 50);
        return jdbc.sql("""
                        SELECT si.product_id,
                               MAX(si.name) AS name,
                               SUM(si.quantity - si.returned_qty) AS qty,
                               SUM(si.line_total * (si.quantity - si.returned_qty) / si.quantity) AS revenue,
                               SUM((si.quantity - si.returned_qty) * si.unit_cost) AS cost
                        FROM sale_items si
                        JOIN sales s ON s.id = si.sale_id AND s.store_id = si.store_id
                        WHERE s.store_id = :s AND s.status = 'COMPLETED' AND s.sold_on BETWEEN :f AND :t
                        GROUP BY si.product_id
                        HAVING SUM(si.quantity - si.returned_qty) > 0
                        ORDER BY revenue DESC
                        LIMIT :n
                        """)
                .param("s", m.storeId()).param("f", lo).param("t", hi).param("n", n)
                .query((rs, i) -> {
                    BigDecimal revenue = Money.of(rs.getBigDecimal("revenue"));
                    return new TopProduct(rs.getObject("product_id", UUID.class), rs.getString("name"),
                            rs.getBigDecimal("qty"), revenue,
                            Money.of(revenue.subtract(rs.getBigDecimal("cost"))));
                })
                .list();
    }

    private record Totals(BigDecimal sales, BigDecimal returns, BigDecimal saleCost, BigDecimal returnCost, BigDecimal expenses) {
        BigDecimal netSales() { return Money.of(sales.subtract(returns)); }
        BigDecimal cogs() { return Money.of(saleCost.subtract(returnCost)); }
        BigDecimal gross() { return Money.of(netSales().subtract(cogs())); }
        BigDecimal net() { return Money.of(gross().subtract(expenses)); }
    }

    private Totals totals(UUID s, LocalDate f, LocalDate t) {
        return new Totals(
                sum("SELECT COALESCE(SUM(total), 0) FROM sales WHERE store_id = :s AND sold_on BETWEEN :f AND :t", s, f, t),
                sum("SELECT COALESCE(SUM(amount), 0) FROM sale_returns WHERE store_id = :s AND returned_on BETWEEN :f AND :t", s, f, t),
                sum("SELECT COALESCE(SUM(cost_total), 0) FROM sales WHERE store_id = :s AND sold_on BETWEEN :f AND :t", s, f, t),
                sum("SELECT COALESCE(SUM(cost_amount), 0) FROM sale_returns WHERE store_id = :s AND returned_on BETWEEN :f AND :t", s, f, t),
                sum("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE store_id = :s AND deleted_at IS NULL AND expense_date BETWEEN :f AND :t", s, f, t));
    }

    private BigDecimal sum(String sql, UUID s, LocalDate f, LocalDate t) {
        return Money.of(jdbc.sql(sql).param("s", s).param("f", f).param("t", t).query(BigDecimal.class).single());
    }
}
