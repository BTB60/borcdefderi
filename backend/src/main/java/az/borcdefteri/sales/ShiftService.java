package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kassir növbəsi and the Z-report. Everything is counted for the cashier who opened the shift, between opening and
 * closing: their sales, returns and the money they moved through the cash-desk (CASH) accounts.
 */
@Service
public class ShiftService {

    public record OpenRequest(
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal openingCash) {}

    public record CloseRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal countedCash,
            @jakarta.validation.constraints.Size(max = 500) String note) {}

    public record Report(UUID id, UUID userId, String cashierName, OffsetDateTime openedAt, OffsetDateTime closedAt,
                         BigDecimal openingCash, long salesCount, BigDecimal salesTotal, BigDecimal paidCash, BigDecimal paidCard,
                         BigDecimal credit, BigDecimal discounts, long returnsCount, BigDecimal returnsTotal, BigDecimal refunded,
                         Map<String, BigDecimal> cashByKind, BigDecimal cashIn, BigDecimal cashOut, BigDecimal expectedCash,
                         BigDecimal countedCash, BigDecimal difference, String note, boolean open) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public ShiftService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    private CurrentMember requireSales() {
        return tenant.requirePermission(SalesService.PERM_SALES);
    }

    /** The caller's open shift with its running totals, or null. */
    public Report current() {
        CurrentMember m = requireSales();
        return jdbc.sql("SELECT id FROM cash_shifts WHERE user_id = :u AND store_id = :s AND closed_at IS NULL")
                .param("u", m.userId()).param("s", m.storeId()).query(UUID.class).optional()
                .map(id -> report(m.storeId(), id)).orElse(null);
    }

    @Transactional
    public Report open(OpenRequest req) {
        CurrentMember m = requireSales();
        if (current() != null) throw ApiException.conflict("SHIFT_OPEN", "Növbəniz artıq açıqdır");
        UUID id = UUID.randomUUID();
        BigDecimal opening = Money.of(req == null || req.openingCash() == null ? BigDecimal.ZERO : req.openingCash());
        jdbc.sql("INSERT INTO cash_shifts (id, store_id, user_id, opening_cash) VALUES (:id, :s, :u, :o)")
                .param("id", id).param("s", m.storeId()).param("u", m.userId()).param("o", opening).update();
        audit.log(m.storeId(), m.userId(), "SHIFT_OPENED", "SHIFT", id.toString(), "Kassada: " + opening + " AZN");
        return report(m.storeId(), id);
    }

    @Transactional
    public Report close(UUID id, CloseRequest req) {
        CurrentMember m = requireSales();
        var row = jdbc.sql("SELECT user_id, closed_at FROM cash_shifts WHERE id = :id AND store_id = :s FOR UPDATE")
                .param("id", id).param("s", m.storeId())
                .query((rs, n) -> new Object[]{rs.getObject("user_id", UUID.class), rs.getObject("closed_at", OffsetDateTime.class)})
                .optional().orElseThrow(() -> ApiException.notFound("Növbə tapılmadı"));
        if (!m.isOwner() && !m.userId().equals(row[0])) {
            throw ApiException.forbidden("NOT_YOUR_SHIFT", "Yalnız öz növbənizi bağlaya bilərsiniz");
        }
        if (row[1] != null) throw ApiException.conflict("SHIFT_CLOSED", "Növbə artıq bağlanıb");
        Report r = report(m.storeId(), id);
        BigDecimal counted = Money.of(req.countedCash());
        BigDecimal diff = Money.of(counted.subtract(r.expectedCash()));
        jdbc.sql("""
                UPDATE cash_shifts SET closed_at = now(), expected_cash = :e, counted_cash = :c, difference = :d, note = :n
                WHERE id = :id""").param("e", r.expectedCash()).param("c", counted).param("d", diff)
                .param("n", req.note() == null || req.note().isBlank() ? null : req.note().trim()).param("id", id).update();
        audit.log(m.storeId(), m.userId(), "SHIFT_CLOSED", "SHIFT", id.toString(),
                "gözlənilən " + r.expectedCash() + ", sayılan " + counted + ", fərq " + diff + " AZN");
        return report(m.storeId(), id);
    }

    /** Owner sees every cashier's shifts; an employee only their own. */
    public List<Report> list(int limit) {
        CurrentMember m = requireSales();
        String who = m.isOwner() ? "" : " AND user_id = :u";
        var spec = jdbc.sql("SELECT id FROM cash_shifts WHERE store_id = :s" + who + " ORDER BY opened_at DESC LIMIT "
                + Math.min(Math.max(limit, 1), 200)).param("s", m.storeId());
        if (!m.isOwner()) spec = spec.param("u", m.userId());
        return spec.query(UUID.class).list().stream().map(id -> report(m.storeId(), id)).toList();
    }

    public Report get(UUID id) {
        CurrentMember m = requireSales();
        Report r = report(m.storeId(), id);
        if (!m.isOwner() && !m.userId().equals(r.userId())) throw ApiException.notFound("Növbə tapılmadı");
        return r;
    }

    private Report report(UUID storeId, UUID id) {
        record Shift(UUID user, String name, OffsetDateTime opened, OffsetDateTime closed, BigDecimal opening,
                     BigDecimal expected, BigDecimal counted, BigDecimal diff, String note) {}
        Shift s = jdbc.sql("""
                SELECT sh.*, u.full_name FROM cash_shifts sh JOIN users u ON u.id = sh.user_id
                WHERE sh.id = :id AND sh.store_id = :s""").param("id", id).param("s", storeId)
                .query((rs, n) -> new Shift(rs.getObject("user_id", UUID.class), rs.getString("full_name"),
                        rs.getObject("opened_at", OffsetDateTime.class), rs.getObject("closed_at", OffsetDateTime.class),
                        Money.of(rs.getBigDecimal("opening_cash")), rs.getBigDecimal("expected_cash"), rs.getBigDecimal("counted_cash"),
                        rs.getBigDecimal("difference"), rs.getString("note")))
                .optional().orElseThrow(() -> ApiException.notFound("Növbə tapılmadı"));
        OffsetDateTime to = s.closed() != null ? s.closed() : OffsetDateTime.now();
        Map<String, Object> p = Map.of("s", storeId, "u", s.user(), "f", s.opened(), "t", to);

        Object[] sales = bind(jdbc.sql("""
                SELECT COUNT(*) AS n, COALESCE(SUM(total), 0) AS total, COALESCE(SUM(paid_cash), 0) AS cash,
                       COALESCE(SUM(paid_card), 0) AS card, COALESCE(SUM(credit_amount), 0) AS credit, COALESCE(SUM(discount), 0) AS disc
                FROM sales WHERE store_id = :s AND created_by = :u AND created_at >= :f AND created_at <= :t"""), p)
                .query((rs, n) -> new Object[]{rs.getLong("n"), rs.getBigDecimal("total"), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("card"), rs.getBigDecimal("credit"), rs.getBigDecimal("disc")}).single();
        Object[] returns = bind(jdbc.sql("""
                SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS amount, COALESCE(SUM(refunded), 0) AS refunded
                FROM sale_returns WHERE store_id = :s AND created_by = :u AND created_at >= :f AND created_at <= :t"""), p)
                .query((rs, n) -> new Object[]{rs.getLong("n"), rs.getBigDecimal("amount"), rs.getBigDecimal("refunded")}).single();
        Map<String, BigDecimal> byKind = new LinkedHashMap<>();
        BigDecimal[] inOut = {Money.zero(), Money.zero()};
        bind(jdbc.sql("""
                SELECT t.kind, t.direction, SUM(t.amount) AS amount
                FROM cash_transactions t JOIN cash_accounts a ON a.id = t.account_id
                WHERE t.store_id = :s AND t.actor_id = :u AND a.kind = 'CASH' AND t.created_at >= :f AND t.created_at <= :t
                GROUP BY t.kind, t.direction ORDER BY t.kind"""), p)
                .query((rs, n) -> {
                    BigDecimal a = Money.of(rs.getBigDecimal("amount"));
                    boolean in = "IN".equals(rs.getString("direction"));
                    byKind.merge(rs.getString("kind"), in ? a : a.negate(), BigDecimal::add);
                    inOut[in ? 0 : 1] = inOut[in ? 0 : 1].add(a);
                    return null;
                }).list();
        BigDecimal expected = s.expected() != null ? Money.of(s.expected())
                : Money.of(s.opening().add(inOut[0]).subtract(inOut[1]));
        return new Report(id, s.user(), s.name(), s.opened(), s.closed(), s.opening(),
                (Long) sales[0], Money.of((BigDecimal) sales[1]), Money.of((BigDecimal) sales[2]), Money.of((BigDecimal) sales[3]),
                Money.of((BigDecimal) sales[4]), Money.of((BigDecimal) sales[5]),
                (Long) returns[0], Money.of((BigDecimal) returns[1]), Money.of((BigDecimal) returns[2]),
                byKind, Money.of(inOut[0]), Money.of(inOut[1]), expected,
                s.counted() == null ? null : Money.of(s.counted()), s.diff() == null ? null : Money.of(s.diff()), s.note(),
                s.closed() == null);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> p) {
        for (var e : p.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec;
    }
}
