package az.borcdefteri.debts;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DebtService {

    public record DebtView(UUID id, DebtType debtType, UUID partyId, String partyName, String partyPhone,
                           BigDecimal amount, BigDecimal paidAmount, BigDecimal remainingAmount, String status,
                           LocalDate debtDate, LocalDate dueDate, String description, String note,
                           OffsetDateTime createdAt, long version) {}

    public record Totals(BigDecimal amount, BigDecimal paid, BigDecimal remaining) {}

    public record DebtPage(List<DebtView> items, long total, int page, int size, Totals totals) {}

    public record LedgerEntry(UUID id, String type, BigDecimal amount, BigDecimal balanceAfter,
                              UUID paymentId, UUID actorId, String actorName, OffsetDateTime createdAt,
                              LocalDate entryDate, String receiptNumber, String note) {}

    static final String SELECT = """
            SELECT d.id, d.debt_type, d.customer_id, d.company_id,
                   COALESCE(c.name, co.name) AS party_name, COALESCE(c.phone, co.phone) AS party_phone,
                   d.amount, d.paid_amount, d.remaining_amount, d.status, d.debt_date, d.due_date,
                   d.description, d.note, d.created_at, d.version
            FROM debts d
            LEFT JOIN customers c ON c.id = d.customer_id
            LEFT JOIN companies co ON co.id = d.company_id
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final LedgerWriter ledger;

    public DebtService(JdbcClient jdbc, TenantService tenant, AuditService audit, LedgerWriter ledger) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.ledger = ledger;
    }

    public DebtPage list(DebtType type, String status, String q, LocalDate from, LocalDate to, int page, int size) {
        CurrentMember m = tenant.requirePermission(type.permission());
        Filter f = filter(m, type, status, q, from, to);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        List<DebtView> items = f.apply(jdbc.sql(SELECT + f.where + " ORDER BY d.debt_date DESC, d.created_at DESC LIMIT "
                        + safeSize + " OFFSET " + ((long) safePage * safeSize)))
                .query((rs, n) -> map(rs)).list();
        long total = f.apply(jdbc.sql("SELECT COUNT(*) FROM debts d LEFT JOIN customers c ON c.id = d.customer_id "
                        + "LEFT JOIN companies co ON co.id = d.company_id" + f.where))
                .query(Long.class).single();
        Totals totals = f.apply(jdbc.sql("""
                SELECT COALESCE(SUM(d.amount), 0) AS a, COALESCE(SUM(d.paid_amount), 0) AS p,
                       COALESCE(SUM(d.remaining_amount), 0) AS r
                FROM debts d LEFT JOIN customers c ON c.id = d.customer_id LEFT JOIN companies co ON co.id = d.company_id
                """ + f.where))
                .query((rs, n) -> new Totals(Money.of(rs.getBigDecimal("a")), Money.of(rs.getBigDecimal("p")),
                        Money.of(rs.getBigDecimal("r"))))
                .single();
        return new DebtPage(items, total, safePage, safeSize, totals);
    }

    /** All matching rows (used by exports). Capped to protect memory. */
    public List<DebtView> listAll(DebtType type, String status, LocalDate from, LocalDate to) {
        CurrentMember m = tenant.requirePermission(type.permission());
        Filter f = filter(m, type, status, null, from, to);
        return f.apply(jdbc.sql(SELECT + f.where + " ORDER BY d.debt_date DESC LIMIT 5000")).query((rs, n) -> map(rs)).list();
    }

    public DebtView get(UUID id) {
        CurrentMember m = tenant.requireBusinessMember();
        DebtView view = load(m, id);
        requireAccess(m, view.debtType());
        return view;
    }

    public List<LedgerEntry> history(UUID id) {
        CurrentMember m = tenant.requireBusinessMember();
        requireAccess(m, load(m, id).debtType());
        return jdbc.sql("""
                SELECT t.id, t.transaction_type, t.amount, t.balance_after, t.payment_id, t.actor_id,
                       u.full_name AS actor_name, t.created_at, t.entry_date, t.receipt_number, t.note
                FROM financial_transactions t JOIN users u ON u.id = t.actor_id
                WHERE t.debt_id = :debtId AND t.store_id = :storeId
                ORDER BY t.created_at, t.id
                """).param("debtId", id).param("storeId", m.storeId())
                .query((rs, n) -> new LedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("transaction_type"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("entry_date", LocalDate.class),
                        rs.getString("receipt_number"),
                        rs.getString("note")))
                .list();
    }

    @Transactional
    public DebtView create(DebtRequest req) {
        return create(req, null);
    }

    /** Same, with the receipt (çek) number kept on the first ledger entry (searchable, shown in history). */
    @Transactional
    public DebtView create(DebtRequest req, String receiptNumber) {
        CurrentMember m = tenant.requireBusinessMember();
        requireAccess(m, req.debtType());
        LocalDate debtDate = req.debtDate();
        if (req.dueDate() != null && req.dueDate().isBefore(debtDate)) {
            throw ApiException.badRequest("INVALID_DUE_DATE", "Son ödəmə tarixi borc tarixindən əvvəl ola bilməz");
        }
        boolean customer = req.debtType().isCustomer();
        String partyTable = customer ? "customers" : "companies";
        boolean partyExists = jdbc.sql("SELECT COUNT(*) FROM " + partyTable + " WHERE id = :id AND store_id = :s")
                .param("id", req.partyId()).param("s", m.storeId()).query(Long.class).single() > 0;
        if (!partyExists) {
            throw ApiException.notFound(customer ? "Müştəri tapılmadı" : "Şirkət tapılmadı");
        }
        BigDecimal amount = Money.of(req.amount());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO debts (id, store_id, debt_type, customer_id, company_id, amount, paid_amount,
                                   remaining_amount, status, debt_date, due_date, description, note, created_by)
                VALUES (:id, :storeId, :type, :customerId, :companyId, :amount, 0, :amount, 'OPEN',
                        :debtDate, :dueDate, :description, :note, :by)
                """)
                .param("id", id).param("storeId", m.storeId()).param("type", req.debtType().name())
                .param("customerId", customer ? req.partyId() : null)
                .param("companyId", customer ? null : req.partyId())
                .param("amount", amount).param("debtDate", debtDate).param("dueDate", req.dueDate())
                .param("description", blankToNull(req.description())).param("note", blankToNull(req.note()))
                .param("by", m.userId())
                .update();
        ledger.append(m.storeId(), id, null, "DEBT_CREATED", amount, amount, m.userId(),
                debtDate, blankToNull(receiptNumber), null);
        audit.log(m.storeId(), m.userId(), "DEBT_CREATED", "DEBT", id.toString(),
                req.debtType() + " " + amount + " AZN");
        return load(m, id);
    }

    @Transactional
    public DebtView update(UUID id, DebtUpdateRequest req) {
        CurrentMember m = tenant.requireBusinessMember();
        DebtView current = lockAndLoad(m, id);
        requireAccess(m, current.debtType());
        if (current.version() != req.version()) {
            throw ApiException.conflict("VERSION_CONFLICT", "Qeyd başqa istifadəçi tərəfindən dəyişdirilib. Yenidən yükləyin");
        }
        BigDecimal newAmount = Money.of(req.amount());
        if (newAmount.compareTo(current.paidAmount()) < 0) {
            throw ApiException.badRequest("AMOUNT_BELOW_PAID",
                    "Borc məbləği artıq ödənilmiş məbləğdən (" + current.paidAmount() + " AZN) az ola bilməz");
        }
        if (req.dueDate() != null && req.dueDate().isBefore(req.debtDate())) {
            throw ApiException.badRequest("INVALID_DUE_DATE", "Son ödəmə tarixi borc tarixindən əvvəl ola bilməz");
        }
        BigDecimal remaining = newAmount.subtract(current.paidAmount());
        String status = statusFor(current.paidAmount(), remaining);
        jdbc.sql("""
                UPDATE debts SET amount = :amount, remaining_amount = :remaining, status = :status,
                       debt_date = :debtDate, due_date = :dueDate, description = :description, note = :note,
                       updated_at = now(), version = version + 1
                WHERE id = :id AND store_id = :storeId AND version = :version
                """)
                .param("amount", newAmount).param("remaining", remaining).param("status", status)
                .param("debtDate", req.debtDate()).param("dueDate", req.dueDate())
                .param("description", blankToNull(req.description())).param("note", blankToNull(req.note()))
                .param("id", id).param("storeId", m.storeId()).param("version", req.version())
                .update();
        BigDecimal delta = newAmount.subtract(current.amount());
        if (delta.signum() != 0) {
            ledger.append(m.storeId(), id, null, "DEBT_ADJUSTED", delta, remaining, m.userId());
        }
        audit.log(m.storeId(), m.userId(), "DEBT_UPDATED", "DEBT", id.toString(),
                "amount " + current.amount() + " -> " + newAmount);
        return load(m, id);
    }

    /**
     * "Borc artır": the party took more on credit. Amount and remaining grow by the same value; what was
     * already paid stays. One ledger row records the date, receipt number and note, so history is kept.
     */
    @Transactional
    public DebtView increase(UUID id, DebtIncreaseRequest req) {
        CurrentMember m = tenant.requireBusinessMember();
        DebtView current = lockAndLoad(m, id);
        requireAccess(m, current.debtType());
        if (current.version() != req.version()) {
            throw ApiException.conflict("VERSION_CONFLICT", "Qeyd başqa istifadəçi tərəfindən dəyişdirilib. Yenidən yükləyin");
        }
        return increaseLocked(m, current, req.amount(), req.date(), req.receiptNumber(), req.note());
    }

    /**
     * Adds to a debt the caller has already locked ({@link #lockAndLoad}) and may write.
     * Amount and remaining grow together; what was already paid stays; one ledger row keeps the details.
     */
    public DebtView increaseLocked(CurrentMember m, DebtView current, BigDecimal amount, LocalDate entryDate,
                                   String receiptNumber, String note) {
        UUID id = current.id();
        BigDecimal add = Money.of(amount);
        if (add.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Məbləğ 0-dan böyük olmalıdır");
        }
        LocalDate today = tenant.today();
        LocalDate date = entryDate != null ? entryDate : today;
        if (date.isAfter(today)) {
            throw ApiException.badRequest("FUTURE_DATE", "Tarix gələcəkdə ola bilməz");
        }
        BigDecimal newAmount = Money.of(current.amount().add(add));
        BigDecimal newRemaining = Money.of(current.remainingAmount().add(add));
        if (newAmount.precision() - newAmount.scale() > 12) {
            throw ApiException.badRequest("AMOUNT_TOO_LARGE", "Borc məbləği çox böyükdür");
        }
        String status = statusFor(current.paidAmount(), newRemaining);
        jdbc.sql("""
                UPDATE debts SET amount = :amount, remaining_amount = :remaining, status = :status,
                       updated_at = now(), version = version + 1
                WHERE id = :id AND store_id = :storeId AND version = :version
                """)
                .param("amount", newAmount).param("remaining", newRemaining).param("status", status)
                .param("id", id).param("storeId", m.storeId()).param("version", current.version())
                .update();
        ledger.append(m.storeId(), id, null, "DEBT_INCREASED", add, newRemaining, m.userId(),
                date, blankToNull(receiptNumber), blankToNull(note));
        audit.log(m.storeId(), m.userId(), "DEBT_INCREASED", "DEBT", id.toString(),
                current.partyName() + ": " + current.amount() + " -> " + newAmount + " AZN (+" + add + ")");
        return load(m, id);
    }

    /** Sets the "son ödəmə tarixi" of a debt the caller has locked. */
    public DebtView setDueDateLocked(CurrentMember m, DebtView debt, LocalDate due) {
        if (due.isBefore(debt.debtDate())) {
            throw ApiException.badRequest("INVALID_DUE_DATE", "Son ödəmə tarixi borc tarixindən əvvəl ola bilməz");
        }
        jdbc.sql("UPDATE debts SET due_date = :due, updated_at = now(), version = version + 1 WHERE id = :id AND store_id = :s")
                .param("due", due).param("id", debt.id()).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "DEBT_DUE_DATE_SET", "DEBT", debt.id().toString(), String.valueOf(due));
        return load(m, debt.id());
    }

    /** Locks every debt of one party in one section (oldest first) for a balance-changing operation. */
    public List<DebtView> lockForParty(CurrentMember m, DebtType type, UUID partyId) {
        String column = type.isCustomer() ? "customer_id" : "company_id";
        List<UUID> ids = jdbc.sql("SELECT id FROM debts WHERE store_id = :s AND debt_type = :t AND " + column
                        + " = :p ORDER BY debt_date, created_at, id FOR UPDATE")
                .param("s", m.storeId()).param("t", type.name()).param("p", partyId)
                .query(UUID.class).list();
        List<DebtView> out = new ArrayList<>();
        for (UUID id : ids) {
            out.add(load(m, id));
        }
        return out;
    }

    /** Debts of one customer or company, limited to the sections the member may see. Newest first. */
    public List<DebtView> forParty(CurrentMember m, boolean customer, UUID partyId) {
        List<String> types = new ArrayList<>();
        for (DebtType t : DebtType.values()) {
            if (t.isCustomer() == customer && m.has(t.permission())) {
                types.add(t.name());
            }
        }
        if (types.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE d.store_id = :storeId AND d." + (customer ? "customer_id" : "company_id")
                        + " = :partyId AND d.debt_type IN (:types) ORDER BY d.debt_date DESC, d.created_at DESC LIMIT 1000")
                .param("storeId", m.storeId()).param("partyId", partyId).param("types", types)
                .query((rs, n) -> map(rs)).list();
    }

    // ----- helpers -----

    /** Locks the debt row for the duration of the transaction (used by payments and edits). */
    public DebtView lockAndLoad(CurrentMember m, UUID id) {
        boolean exists = jdbc.sql("SELECT id FROM debts WHERE id = :id AND store_id = :s FOR UPDATE")
                .param("id", id).param("s", m.storeId()).query(UUID.class).optional().isPresent();
        if (!exists) {
            throw ApiException.notFound("Borc qeydi tapılmadı");
        }
        return load(m, id);
    }

    public DebtView load(CurrentMember m, UUID id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id AND d.store_id = :storeId")
                .param("id", id).param("storeId", m.storeId())
                .query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Borc qeydi tapılmadı"));
    }

    public void requireAccess(CurrentMember m, DebtType type) {
        if (!m.has(type.permission())) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
    }

    public static String statusFor(BigDecimal paid, BigDecimal remaining) {
        if (remaining.signum() == 0) {
            return "PAID";
        }
        return paid.signum() > 0 ? "PARTIAL" : "OPEN";
    }

    private Filter filter(CurrentMember m, DebtType type, String status, String q, LocalDate from, LocalDate to) {
        Filter f = new Filter();
        f.clause("d.store_id = :storeId", "storeId", m.storeId());
        f.clause("d.debt_type = :debtType", "debtType", type.name());
        if (status != null && !status.isBlank()) {
            switch (status.toUpperCase(java.util.Locale.ROOT)) {
                case "OPEN" -> f.clause("d.status = 'OPEN'", null, null);
                case "PARTIAL" -> f.clause("d.status = 'PARTIAL'", null, null);
                case "PAID" -> f.clause("d.status = 'PAID'", null, null);
                case "UNPAID" -> f.clause("d.status <> 'PAID'", null, null);
                default -> throw ApiException.badRequest("INVALID_STATUS", "Status filtri yanlışdır");
            }
        }
        if (q != null && !q.isBlank()) {
            f.clause("(LOWER(COALESCE(c.name, co.name)) LIKE LOWER(:q) OR COALESCE(c.phone, co.phone, '') LIKE :q "
                    + "OR LOWER(COALESCE(d.description, '')) LIKE LOWER(:q))", "q", az.borcdefteri.common.Search.contains(q));
        }
        if (from != null) {
            f.clause("d.debt_date >= :from", "from", from);
        }
        if (to != null) {
            f.clause("d.debt_date <= :to", "to", to);
        }
        return f;
    }

    /** Builds a WHERE clause from constant fragments with bound parameters only. */
    static final class Filter {
        final StringBuilder sb = new StringBuilder();
        final Map<String, Object> params = new LinkedHashMap<>();
        String where = "";

        void clause(String sql, String name, Object value) {
            sb.append(sb.length() == 0 ? " WHERE " : " AND ").append(sql);
            if (name != null) {
                params.put(name, value);
            }
            where = sb.toString();
        }

        JdbcClient.StatementSpec apply(JdbcClient.StatementSpec spec) {
            JdbcClient.StatementSpec s = spec;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                s = s.param(e.getKey(), e.getValue());
            }
            return s;
        }
    }

    private static DebtView map(ResultSet rs) throws SQLException {
        UUID customerId = rs.getObject("customer_id", UUID.class);
        UUID companyId = rs.getObject("company_id", UUID.class);
        return new DebtView(
                rs.getObject("id", UUID.class),
                DebtType.valueOf(rs.getString("debt_type")),
                customerId != null ? customerId : companyId,
                rs.getString("party_name"),
                rs.getString("party_phone"),
                Money.of(rs.getBigDecimal("amount")),
                Money.of(rs.getBigDecimal("paid_amount")),
                Money.of(rs.getBigDecimal("remaining_amount")),
                rs.getString("status"),
                rs.getObject("debt_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class),
                rs.getString("description"),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version"));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
