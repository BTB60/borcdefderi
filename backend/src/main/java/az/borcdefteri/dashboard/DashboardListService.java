package az.borcdefteri.dashboard;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.common.Search;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * HesabPro 2.0 dashboard: the card figures and the list behind each card. A card's figure is computed by the
 * very same query as its list (count + sum over the same WHERE), so the two always agree.
 * Customer debts (money owed to the store) and company payables (money the store owes) are never mixed.
 */
@Service
public class DashboardListService {

    public static final String TZ = DashboardService.TZ;

    public enum Kind {
        CUSTOMER_DEBTS, COMPANY_RECEIVABLES, COMPANY_PAYABLES, ACTIVE_CUSTOMERS,
        TODAY_DEBTS, ACTIVE_DEBTS, CLOSED_DEBTS, OVERDUE_DEBTS, PAYMENTS_OUT, PAYMENTS_IN;

        static Kind parse(String s) {
            try {
                return Kind.valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("INVALID_LIST", "Siyahı növü yanlışdır");
            }
        }
    }

    /** A customer or company with its balance in one section. */
    public record PartyRow(UUID partyId, String partyKind, String debtType, String name, String code, String phone,
                           BigDecimal totalDebt, BigDecimal paid, BigDecimal remaining,
                           OffsetDateTime lastActivityAt, OffsetDateTime lastPaymentAt, long overdueDebts) {}

    public record DebtRow(UUID debtId, String debtType, UUID partyId, String partyKind, String name, String code,
                          String phone, LocalDate debtDate, LocalDate dueDate, long overdueDays, BigDecimal amount,
                          BigDecimal paid, BigDecimal remaining, String status, OffsetDateTime lastPaymentAt,
                          String receiptNumber, String createdByName) {}

    /** One "debt written" ledger entry (new debt or increase). */
    public record EntryRow(UUID entryId, UUID debtId, String debtType, UUID partyId, String partyKind, String name,
                           String code, String phone, OffsetDateTime createdAt, LocalDate entryDate,
                           String receiptNumber, String note, BigDecimal amount, BigDecimal debtPaid,
                           BigDecimal debtRemaining, String actorName) {}

    public record PaymentRow(UUID paymentId, UUID debtId, String debtType, UUID partyId, String partyKind, String name,
                             String code, String phone, OffsetDateTime paidAt, BigDecimal amount, String paymentMethod,
                             String recordedByName, String description) {}

    public record Page<T>(String kind, List<T> items, long total, BigDecimal sum, int page, int size) {}

    public record Figure(long count, BigDecimal sum) {}

    /** Card figures. Each is the total of the corresponding list with default filters. */
    public record Overview(Figure customerDebts, Figure companyReceivables, Figure companyPayables,
                           Figure activeCustomers, Figure todayDebts, Figure activeDebts, Figure closedDebts,
                           Figure overdueDebts, Figure paymentsOut, Figure paymentsIn, LocalDate today) {}

    public record Filters(String q, LocalDate from, LocalDate to, String partyKind) {
        static final Filters NONE = new Filters(null, null, null, null);
    }

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public DashboardListService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public Overview overview() {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        LocalDate today = tenant.today();
        return new Overview(
                figure(m, Kind.CUSTOMER_DEBTS, Filters.NONE),
                figure(m, Kind.COMPANY_RECEIVABLES, Filters.NONE),
                figure(m, Kind.COMPANY_PAYABLES, Filters.NONE),
                figure(m, Kind.ACTIVE_CUSTOMERS, Filters.NONE),
                figure(m, Kind.TODAY_DEBTS, Filters.NONE),
                figure(m, Kind.ACTIVE_DEBTS, Filters.NONE),
                figure(m, Kind.CLOSED_DEBTS, Filters.NONE),
                figure(m, Kind.OVERDUE_DEBTS, Filters.NONE),
                figure(m, Kind.PAYMENTS_OUT, Filters.NONE),
                figure(m, Kind.PAYMENTS_IN, Filters.NONE),
                today);
    }

    public Page<?> list(String kindText, String q, LocalDate from, LocalDate to, String partyKind, int page, int size) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        Kind kind = Kind.parse(kindText);
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_RANGE", "Başlanğıc tarix son tarixdən sonra ola bilməz");
        }
        String pk = partyKind == null || partyKind.isBlank() ? null : partyKind.trim().toUpperCase(Locale.ROOT);
        if (pk != null && !pk.equals("CUSTOMER") && !pk.equals("COMPANY")) {
            throw ApiException.badRequest("INVALID_PARTY_KIND", "Filtr yalnız müştəri və ya şirkət ola bilər");
        }
        Filters f = new Filters(q, from, to, pk);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Query query = build(m, kind, f);
        Figure fig = totals(query);
        String paging = " ORDER BY " + query.order + " LIMIT " + safeSize + " OFFSET " + ((long) safePage * safeSize);
        var spec = query.bind(jdbc.sql(query.select + paging));
        List<?> items = switch (query.shape) {
            case PARTY -> spec.query((rs, n) -> partyRow(rs)).list();
            case DEBT -> spec.query((rs, n) -> debtRow(rs)).list();
            case ENTRY -> spec.query((rs, n) -> entryRow(rs)).list();
            case PAYMENT -> spec.query((rs, n) -> paymentRow(rs)).list();
        };
        return new Page<>(kind.name(), items, fig.count(), fig.sum(), safePage, safeSize);
    }

    // ------------------------------------------------------------------------------------------------

    private enum Shape { PARTY, DEBT, ENTRY, PAYMENT }

    /** A SELECT with bound parameters only; {@code sumColumn} is what the card adds up. */
    private static final class Query {
        Shape shape;
        String select;
        String sumColumn;
        String order;
        final Map<String, Object> params = new LinkedHashMap<>();

        JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
            JdbcClient.StatementSpec s = spec;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                s = s.param(e.getKey(), e.getValue());
            }
            return s;
        }
    }

    private Figure figure(CurrentMember m, Kind kind, Filters f) {
        return totals(build(m, kind, f));
    }

    private Figure totals(Query query) {
        return query.bind(jdbc.sql("SELECT COUNT(*) AS n, COALESCE(SUM(x." + query.sumColumn + "), 0) AS s FROM ("
                        + query.select + ") x"))
                .query((rs, n) -> new Figure(rs.getLong("n"), Money.of(rs.getBigDecimal("s")))).single();
    }

    private Query build(CurrentMember m, Kind kind, Filters f) {
        LocalDate today = tenant.today();
        Query q = new Query();
        q.params.put("storeId", m.storeId());
        q.params.put("today", today);
        q.params.put("tz", TZ);
        boolean hasQ = f.q() != null && !f.q().isBlank();
        if (hasQ) {
            q.params.put("q", Search.contains(f.q()));
        }
        switch (kind) {
            case CUSTOMER_DEBTS, ACTIVE_CUSTOMERS -> {
                requireType(m, DebtType.CUSTOMER_DEBT);
                partyQuery(q, true, DebtType.CUSTOMER_DEBT, f, hasQ);
            }
            case COMPANY_RECEIVABLES -> {
                requireType(m, DebtType.COMPANY_RECEIVABLE);
                partyQuery(q, false, DebtType.COMPANY_RECEIVABLE, f, hasQ);
            }
            case COMPANY_PAYABLES -> {
                requireType(m, DebtType.COMPANY_PAYABLE);
                partyQuery(q, false, DebtType.COMPANY_PAYABLE, f, hasQ);
            }
            case ACTIVE_DEBTS -> debtQuery(q, m, f, hasQ, "d.remaining_amount > 0", "d.remaining_amount");
            case OVERDUE_DEBTS -> debtQuery(q, m, f, hasQ, "d.remaining_amount > 0 AND d.due_date < :today", "d.remaining_amount");
            case CLOSED_DEBTS -> debtQuery(q, m, f, hasQ, "d.status = 'PAID'", "d.amount");
            case TODAY_DEBTS -> entryQuery(q, m, f, hasQ, today);
            case PAYMENTS_OUT -> paymentQuery(q, m, f, hasQ, List.of(DebtType.COMPANY_PAYABLE));
            case PAYMENTS_IN -> paymentQuery(q, m, f, hasQ, List.of(DebtType.CUSTOMER_DEBT, DebtType.COMPANY_RECEIVABLE));
        }
        return q;
    }

    private static void requireType(CurrentMember m, DebtType t) {
        if (!m.has(t.permission())) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
    }

    private static List<String> allowed(CurrentMember m, List<DebtType> wanted) {
        List<String> out = new ArrayList<>();
        for (DebtType t : wanted) {
            if (m.has(t.permission())) {
                out.add(t.name());
            }
        }
        return out.isEmpty() ? List.of("NONE") : out;
    }

    /** Party filter on a row that has c (customers) and co (companies) joined. */
    private static String partyKindClause(Filters f) {
        if ("CUSTOMER".equals(f.partyKind())) return " AND d.customer_id IS NOT NULL";
        if ("COMPANY".equals(f.partyKind())) return " AND d.company_id IS NOT NULL";
        return "";
    }

    private static final String PARTY_SEARCH = """
             AND (LOWER(COALESCE(c.name, co.name)) LIKE LOWER(:q) OR COALESCE(c.phone, co.phone, '') LIKE :q
                  OR LOWER(COALESCE(c.code, '')) LIKE LOWER(:q) OR LOWER(COALESCE(d.description, '')) LIKE LOWER(:q)
                  OR LOWER(COALESCE(d.note, '')) LIKE LOWER(:q)
                  OR EXISTS (SELECT 1 FROM financial_transactions t WHERE t.debt_id = d.id
                             AND LOWER(COALESCE(t.receipt_number, '')) LIKE LOWER(:q)))""";

    private void partyQuery(Query q, boolean customer, DebtType type, Filters f, boolean hasQ) {
        q.shape = Shape.PARTY;
        q.params.put("type", type.name());
        String fk = customer ? "d.customer_id" : "d.company_id";
        String partyTable = customer ? "customers p" : "companies p";
        String code = customer ? "p.code" : "NULL";
        StringBuilder where = new StringBuilder(" WHERE p.store_id = :storeId");
        if (f.from() != null) {
            where.append(" AND d.debt_date >= :from");
            q.params.put("from", f.from());
        }
        if (f.to() != null) {
            where.append(" AND d.debt_date <= :to");
            q.params.put("to", f.to());
        }
        if (hasQ) {
            where.append(" AND (LOWER(p.name) LIKE LOWER(:q) OR COALESCE(p.phone, '') LIKE :q OR LOWER(COALESCE(")
                    .append(code).append(", '')) LIKE LOWER(:q) OR EXISTS (SELECT 1 FROM financial_transactions t JOIN debts dd ON dd.id = t.debt_id ")
                    .append("WHERE dd.").append(customer ? "customer_id" : "company_id")
                    .append(" = p.id AND LOWER(COALESCE(t.receipt_number, '')) LIKE LOWER(:q)))");
        }
        q.select = "SELECT p.id AS party_id, '" + (customer ? "CUSTOMER" : "COMPANY") + "' AS party_kind, :type AS debt_type, "
                + "p.name, " + code + " AS code, p.phone, SUM(d.amount) AS total_debt, SUM(d.paid_amount) AS paid, "
                + "SUM(d.remaining_amount) AS remaining, MAX(d.updated_at) AS last_activity_at, "
                + "(SELECT MAX(pp.paid_at) FROM payments pp JOIN debts d2 ON d2.id = pp.debt_id WHERE pp.voided_at IS NULL AND " + fk.replace("d.", "d2.")
                + " = p.id AND d2.debt_type = :type) AS last_payment_at, "
                + "COUNT(*) FILTER (WHERE d.remaining_amount > 0 AND d.due_date < :today) AS overdue_debts "
                + "FROM " + partyTable + " JOIN debts d ON " + fk + " = p.id AND d.store_id = p.store_id AND d.debt_type = :type"
                + where + " GROUP BY p.id, p.name, p.phone" + (customer ? ", p.code" : "")
                + " HAVING SUM(d.remaining_amount) > 0";
        q.sumColumn = "remaining";
        q.order = "remaining DESC, name";
    }

    private static final String DEBT_FROM = """
             FROM debts d
             LEFT JOIN customers c ON c.id = d.customer_id
             LEFT JOIN companies co ON co.id = d.company_id
            """;

    private void debtQuery(Query q, CurrentMember m, Filters f, boolean hasQ, String condition, String sumExpr) {
        q.shape = Shape.DEBT;
        q.params.put("types", allowed(m, List.of(DebtType.values())));
        StringBuilder where = new StringBuilder(" WHERE d.store_id = :storeId AND d.debt_type IN (:types) AND ")
                .append(condition).append(partyKindClause(f));
        if (f.from() != null) {
            where.append(" AND d.debt_date >= :from");
            q.params.put("from", f.from());
        }
        if (f.to() != null) {
            where.append(" AND d.debt_date <= :to");
            q.params.put("to", f.to());
        }
        if (hasQ) {
            where.append(" ").append(PARTY_SEARCH);
        }
        q.select = """
                SELECT d.id AS debt_id, d.debt_type, COALESCE(d.customer_id, d.company_id) AS party_id,
                       CASE WHEN d.customer_id IS NOT NULL THEN 'CUSTOMER' ELSE 'COMPANY' END AS party_kind,
                       COALESCE(c.name, co.name) AS name, c.code AS code, COALESCE(c.phone, co.phone) AS phone,
                       d.debt_date, d.due_date,
                       CASE WHEN d.remaining_amount > 0 AND d.due_date < :today THEN (:today - d.due_date) ELSE 0 END AS overdue_days,
                       d.amount, d.paid_amount, d.remaining_amount, d.status, d.created_at,
                       (SELECT MAX(p.paid_at) FROM payments p WHERE p.debt_id = d.id AND p.voided_at IS NULL) AS last_payment_at,
                       (SELECT t.receipt_number FROM financial_transactions t WHERE t.debt_id = d.id
                          AND t.receipt_number IS NOT NULL ORDER BY t.created_at DESC LIMIT 1) AS receipt_number,
                       (SELECT u.full_name FROM users u WHERE u.id = d.created_by) AS created_by_name,
                       """ + sumExpr + " AS figure" + DEBT_FROM + where;
        q.sumColumn = "figure";
        q.order = sumExpr.equals("d.amount") ? "last_payment_at DESC NULLS LAST, debt_id" : "overdue_days DESC, remaining_amount DESC, debt_id";
    }

    private void entryQuery(Query q, CurrentMember m, Filters f, boolean hasQ, LocalDate today) {
        q.shape = Shape.ENTRY;
        q.params.put("types", allowed(m, List.of(DebtType.values())));
        q.params.put("from", f.from() != null ? f.from() : today);
        q.params.put("to", f.to() != null ? f.to() : today);
        StringBuilder where = new StringBuilder("""
                 WHERE t.store_id = :storeId AND d.debt_type IN (:types)
                   AND t.transaction_type IN ('DEBT_CREATED', 'DEBT_INCREASED')
                   AND (t.created_at AT TIME ZONE :tz)::date BETWEEN :from AND :to""").append(partyKindClause(f));
        if (hasQ) {
            where.append(" ").append(PARTY_SEARCH);
        }
        q.select = """
                SELECT t.id AS entry_id, d.id AS debt_id, d.debt_type, COALESCE(d.customer_id, d.company_id) AS party_id,
                       CASE WHEN d.customer_id IS NOT NULL THEN 'CUSTOMER' ELSE 'COMPANY' END AS party_kind,
                       COALESCE(c.name, co.name) AS name, c.code AS code, COALESCE(c.phone, co.phone) AS phone,
                       t.created_at, COALESCE(t.entry_date, d.debt_date) AS entry_date, t.receipt_number,
                       COALESCE(t.note, d.description) AS note, t.amount, d.paid_amount, d.remaining_amount,
                       u.full_name AS actor_name
                FROM financial_transactions t
                JOIN debts d ON d.id = t.debt_id AND d.store_id = t.store_id
                LEFT JOIN customers c ON c.id = d.customer_id
                LEFT JOIN companies co ON co.id = d.company_id
                JOIN users u ON u.id = t.actor_id
                """ + where;
        q.sumColumn = "amount";
        q.order = "created_at DESC, entry_id";
    }

    private void paymentQuery(Query q, CurrentMember m, Filters f, boolean hasQ, List<DebtType> types) {
        q.shape = Shape.PAYMENT;
        q.params.put("types", allowed(m, types));
        StringBuilder where = new StringBuilder(" WHERE p.store_id = :storeId AND p.voided_at IS NULL AND d.debt_type IN (:types)").append(partyKindClause(f));
        if (f.from() != null) {
            where.append(" AND (p.paid_at AT TIME ZONE :tz)::date >= :from");
            q.params.put("from", f.from());
        }
        if (f.to() != null) {
            where.append(" AND (p.paid_at AT TIME ZONE :tz)::date <= :to");
            q.params.put("to", f.to());
        }
        if (hasQ) {
            where.append(" ").append(PARTY_SEARCH);
        }
        q.select = """
                SELECT p.id AS payment_id, d.id AS debt_id, d.debt_type, COALESCE(d.customer_id, d.company_id) AS party_id,
                       CASE WHEN d.customer_id IS NOT NULL THEN 'CUSTOMER' ELSE 'COMPANY' END AS party_kind,
                       COALESCE(c.name, co.name) AS name, c.code AS code, COALESCE(c.phone, co.phone) AS phone,
                       p.paid_at, p.amount, p.payment_method, u.full_name AS recorded_by_name, p.description
                FROM payments p
                JOIN debts d ON d.id = p.debt_id AND d.store_id = p.store_id
                LEFT JOIN customers c ON c.id = d.customer_id
                LEFT JOIN companies co ON co.id = d.company_id
                JOIN users u ON u.id = p.recorded_by
                """ + where;
        q.sumColumn = "amount";
        q.order = "paid_at DESC, payment_id";
    }

    // ------------------------------------------------------------------------------------------------

    private static PartyRow partyRow(ResultSet rs) throws SQLException {
        return new PartyRow(rs.getObject("party_id", UUID.class), rs.getString("party_kind"), rs.getString("debt_type"),
                rs.getString("name"), rs.getString("code"), rs.getString("phone"),
                Money.of(rs.getBigDecimal("total_debt")), Money.of(rs.getBigDecimal("paid")),
                Money.of(rs.getBigDecimal("remaining")), rs.getObject("last_activity_at", OffsetDateTime.class),
                rs.getObject("last_payment_at", OffsetDateTime.class), rs.getLong("overdue_debts"));
    }

    private static DebtRow debtRow(ResultSet rs) throws SQLException {
        return new DebtRow(rs.getObject("debt_id", UUID.class), rs.getString("debt_type"),
                rs.getObject("party_id", UUID.class), rs.getString("party_kind"), rs.getString("name"),
                rs.getString("code"), rs.getString("phone"), rs.getObject("debt_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class), rs.getLong("overdue_days"),
                Money.of(rs.getBigDecimal("amount")), Money.of(rs.getBigDecimal("paid_amount")),
                Money.of(rs.getBigDecimal("remaining_amount")), rs.getString("status"),
                rs.getObject("last_payment_at", OffsetDateTime.class), rs.getString("receipt_number"),
                rs.getString("created_by_name"));
    }

    private static EntryRow entryRow(ResultSet rs) throws SQLException {
        return new EntryRow(rs.getObject("entry_id", UUID.class), rs.getObject("debt_id", UUID.class),
                rs.getString("debt_type"), rs.getObject("party_id", UUID.class), rs.getString("party_kind"),
                rs.getString("name"), rs.getString("code"), rs.getString("phone"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("entry_date", LocalDate.class),
                rs.getString("receipt_number"), rs.getString("note"), Money.of(rs.getBigDecimal("amount")),
                Money.of(rs.getBigDecimal("paid_amount")), Money.of(rs.getBigDecimal("remaining_amount")),
                rs.getString("actor_name"));
    }

    private static PaymentRow paymentRow(ResultSet rs) throws SQLException {
        return new PaymentRow(rs.getObject("payment_id", UUID.class), rs.getObject("debt_id", UUID.class),
                rs.getString("debt_type"), rs.getObject("party_id", UUID.class), rs.getString("party_kind"),
                rs.getString("name"), rs.getString("code"), rs.getString("phone"),
                rs.getObject("paid_at", OffsetDateTime.class), Money.of(rs.getBigDecimal("amount")),
                rs.getString("payment_method"), rs.getString("recorded_by_name"), rs.getString("description"));
    }
}
