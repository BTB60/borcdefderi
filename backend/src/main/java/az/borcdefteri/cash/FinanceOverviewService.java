package az.borcdefteri.cash;

import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Dashboard money figures: balances per account kind, money in / out (transfers excluded) and expenses.
 * Fields the caller has no permission for are null. Net profit is not shown here: it needs sales (stage 8);
 * a cash balance is never presented as profit.
 */
@Service
public class FinanceOverviewService {

    public record Overview(LocalDate today, BigDecimal cashBalance, BigDecimal bankBalance, BigDecimal cardBalance,
                           BigDecimal todayIn, BigDecimal todayOut, BigDecimal monthIn, BigDecimal monthOut,
                           BigDecimal todayExpenses, BigDecimal monthExpenses, BigDecimal previousMonthExpenses,
                           Long budgetsNear, Long budgetsOver, Long recurringDue, BigDecimal recurringDueAmount) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public FinanceOverviewService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public Overview overview() {
        CurrentMember m = tenant.requireBusinessMember();
        UUID s = m.storeId();
        LocalDate today = tenant.today();
        LocalDate monthStart = today.withDayOfMonth(1);
        boolean cash = m.has(CashService.PERM_CASH);
        boolean exp = m.has("EXPENSES");

        BigDecimal cashB = null, bankB = null, cardB = null, tIn = null, tOut = null, mIn = null, mOut = null;
        if (cash) {
            cashB = balance(s, "CASH");
            bankB = balance(s, "BANK");
            cardB = balance(s, "CARD");
            tIn = flow(s, "IN", today, today);
            tOut = flow(s, "OUT", today, today);
            mIn = flow(s, "IN", monthStart, today);
            mOut = flow(s, "OUT", monthStart, today);
        }
        BigDecimal tExp = null, mExp = null, pExp = null, dueAmt = null;
        Long near = null, over = null, due = null;
        if (exp) {
            tExp = expenses(s, today, today);
            mExp = expenses(s, monthStart, today);
            pExp = expenses(s, monthStart.minusMonths(1), monthStart.minusDays(1));
            Object[] b = jdbc.sql("""
                    SELECT COUNT(*) FILTER (WHERE actual > limit_amount) AS over,
                           COUNT(*) FILTER (WHERE actual <= limit_amount AND actual * 100 >= limit_amount * 80) AS near
                    FROM (SELECT b.limit_amount,
                                 COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.store_id = b.store_id
                                    AND e.category_id = b.category_id AND e.deleted_at IS NULL
                                    AND e.expense_date >= b.month AND e.expense_date < (b.month + INTERVAL '1 month')::date), 0) AS actual
                          FROM budgets b WHERE b.store_id = :s AND b.month = :m) x
                    """).param("s", s).param("m", monthStart)
                    .query((rs, n) -> new Object[]{rs.getLong("over"), rs.getLong("near")}).single();
            over = (Long) b[0];
            near = (Long) b[1];
            Object[] r = jdbc.sql("""
                    SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS a FROM recurring_expenses
                    WHERE store_id = :s AND active AND next_due_date <= :t
                    """).param("s", s).param("t", today)
                    .query((rs, n) -> new Object[]{rs.getLong("n"), Money.of(rs.getBigDecimal("a"))}).single();
            due = (Long) r[0];
            dueAmt = (BigDecimal) r[1];
        }
        return new Overview(today, cashB, bankB, cardB, tIn, tOut, mIn, mOut, tExp, mExp, pExp, near, over, due, dueAmt);
    }

    private BigDecimal balance(UUID s, String kind) {
        return Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END), 0)
                FROM cash_transactions t JOIN cash_accounts a ON a.id = t.account_id
                WHERE t.store_id = :s AND a.kind = :k""").param("s", s).param("k", kind).query(BigDecimal.class).single());
    }

    /** Money in or out of the business; transfers between own accounts and count adjustments are excluded. */
    private BigDecimal flow(UUID s, String dir, LocalDate from, LocalDate to) {
        return Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM cash_transactions
                WHERE store_id = :s AND direction = :d AND occurred_on BETWEEN :f AND :t
                  AND kind NOT IN ('TRANSFER_IN', 'TRANSFER_OUT', 'COUNT_ADJUSTMENT', 'OPENING')""")
                .param("s", s).param("d", dir).param("f", from).param("t", to).query(BigDecimal.class).single());
    }

    private BigDecimal expenses(UUID s, LocalDate from, LocalDate to) {
        return Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM expenses
                WHERE store_id = :s AND deleted_at IS NULL AND expense_date BETWEEN :f AND :t""")
                .param("s", s).param("f", from).param("t", to).query(BigDecimal.class).single());
    }
}
