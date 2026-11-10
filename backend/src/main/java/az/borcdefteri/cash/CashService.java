package az.borcdefteri.cash;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cash desks, bank and card accounts and every money movement (cash_transactions, append-only).
 * A balance is always computed from the movements. Transfers are two rows with one transfer id and are never
 * income or expense. Debt collections and supplier payments move money but are not revenue / expense.
 */
@Service
public class CashService {

    public static final String PERM_CASH = "CASH";

    public record Account(UUID id, String name, String kind, boolean isDefault, boolean active, BigDecimal balance,
                          OffsetDateTime createdAt) {}

    public record Movement(UUID id, UUID accountId, String accountName, String direction, BigDecimal amount, String kind,
                           LocalDate occurredOn, UUID transferId, UUID paymentId, UUID expenseId, String note,
                           String actorName, OffsetDateTime createdAt) {}

    public record AccountRequest(
            @jakarta.validation.constraints.NotBlank(message = "Adı daxil edin")
            @jakarta.validation.constraints.Size(max = 100) String name,
            @jakarta.validation.constraints.Pattern(regexp = "CASH|BANK|CARD", message = "Növ yalnız CASH, BANK və ya CARD ola bilər") String kind,
            Boolean isDefault,
            Boolean active) {}

    public record MoveRequest(
            @jakarta.validation.constraints.NotNull UUID accountId,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
            LocalDate date,
            @jakarta.validation.constraints.Size(max = 500) String note,
            UUID clientRequestId) {}

    public record TransferRequest(
            @jakarta.validation.constraints.NotNull UUID fromAccountId,
            @jakarta.validation.constraints.NotNull UUID toAccountId,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
            LocalDate date,
            @jakarta.validation.constraints.Size(max = 500) String note,
            UUID clientRequestId) {}

    /** Inventory of a cash desk: the counted money; the difference is booked as COUNT_ADJUSTMENT. */
    public record CountRequest(
            @jakarta.validation.constraints.NotNull UUID accountId,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0.00", message = "Sayılan məbləğ mənfi ola bilməz")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal countedAmount,
            @jakarta.validation.constraints.Size(max = 500) String note) {}

    public record CountResult(UUID accountId, BigDecimal expected, BigDecimal counted, BigDecimal difference, Movement adjustment) {}

    public record DayLine(UUID accountId, String accountName, String kind, BigDecimal opening, BigDecimal in, BigDecimal out,
                          BigDecimal closing, Map<String, BigDecimal> byKind) {}

    public record DayReport(LocalDate date, List<DayLine> accounts, BigDecimal totalOpening, BigDecimal totalIn,
                            BigDecimal totalOut, BigDecimal totalClosing) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public CashService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    public CurrentMember requireCash() {
        return tenant.requirePermission(PERM_CASH);
    }

    // ----- accounts -----

    @Transactional
    public List<Account> accounts() {
        CurrentMember m = requireCash();
        defaultAccount(m.storeId(), "CASH", m.userId());
        return jdbc.sql(ACCOUNT_SELECT + " WHERE a.store_id = :s GROUP BY a.id ORDER BY a.kind, a.is_default DESC, a.name")
                .param("s", m.storeId()).query((rs, n) -> mapAccount(rs)).list();
    }

    @Transactional
    public Account createAccount(AccountRequest req) {
        CurrentMember m = tenant.requireOwner();
        String kind = req.kind() == null ? "CASH" : req.kind();
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO cash_accounts (id, store_id, name, kind, created_by) VALUES (:id, :s, :n, :k, :by)")
                    .param("id", id).param("s", m.storeId()).param("n", req.name().trim()).param("k", kind)
                    .param("by", m.userId()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_NAME", "Bu adda hesab artıq var");
        }
        if (Boolean.TRUE.equals(req.isDefault())) {
            makeDefault(m.storeId(), id, kind);
        }
        audit.log(m.storeId(), m.userId(), "CASH_ACCOUNT_CREATED", "CASH_ACCOUNT", id.toString(), kind + " " + req.name());
        return account(m.storeId(), id);
    }

    @Transactional
    public Account updateAccount(UUID id, AccountRequest req) {
        CurrentMember m = tenant.requireOwner();
        Account cur = account(m.storeId(), id);
        boolean active = req.active() == null || req.active();
        if (!active && cur.isDefault()) {
            throw ApiException.badRequest("DEFAULT_ACCOUNT", "Əsas hesab deaktiv edilə bilməz; əvvəlcə başqa hesabı əsas edin");
        }
        try {
            jdbc.sql("UPDATE cash_accounts SET name = :n, active = :a WHERE id = :id AND store_id = :s")
                    .param("n", req.name().trim()).param("a", active).param("id", id).param("s", m.storeId()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_NAME", "Bu adda hesab artıq var");
        }
        if (Boolean.TRUE.equals(req.isDefault()) && active) {
            makeDefault(m.storeId(), id, cur.kind());
        }
        audit.log(m.storeId(), m.userId(), "CASH_ACCOUNT_UPDATED", "CASH_ACCOUNT", id.toString(), req.name());
        return account(m.storeId(), id);
    }

    private void makeDefault(UUID storeId, UUID id, String kind) {
        jdbc.sql("UPDATE cash_accounts SET is_default = FALSE WHERE store_id = :s AND kind = :k AND is_default")
                .param("s", storeId).param("k", kind).update();
        jdbc.sql("UPDATE cash_accounts SET is_default = TRUE WHERE id = :id AND store_id = :s")
                .param("id", id).param("s", storeId).update();
    }

    /** The account payments of this kind land in; created on first use ("Əsas kassa", "Bank hesabı", "Kart (POS)"). */
    public UUID defaultAccount(UUID storeId, String kind, UUID actorId) {
        var existing = jdbc.sql("SELECT id FROM cash_accounts WHERE store_id = :s AND kind = :k AND is_default")
                .param("s", storeId).param("k", kind).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        String name = switch (kind) {
            case "CASH" -> "Əsas kassa";
            case "CARD" -> "Kart (POS)";
            default -> "Bank hesabı";
        };
        UUID id = UUID.randomUUID();
        int n = jdbc.sql("""
                INSERT INTO cash_accounts (id, store_id, name, kind, is_default, created_by)
                VALUES (:id, :s, :n, :k, TRUE, :by) ON CONFLICT DO NOTHING
                """).param("id", id).param("s", storeId).param("n", name).param("k", kind).param("by", actorId).update();
        if (n == 0) { // created concurrently, or a non-default account already uses this name
            return jdbc.sql("SELECT id FROM cash_accounts WHERE store_id = :s AND kind = :k ORDER BY is_default DESC, created_at LIMIT 1")
                    .param("s", storeId).param("k", kind).query(UUID.class).single();
        }
        return id;
    }

    /** Account for a payment method: cash → cash desk, card → card account, transfer / other → bank. */
    public UUID accountFor(UUID storeId, String paymentMethod, UUID requested, UUID actorId) {
        if (requested != null) {
            requireActiveAccount(storeId, requested);
            return requested;
        }
        String kind = switch (paymentMethod) {
            case "CASH" -> "CASH";
            case "CARD" -> "CARD";
            default -> "BANK";
        };
        return defaultAccount(storeId, kind, actorId);
    }

    private void requireActiveAccount(UUID storeId, UUID id) {
        Boolean active = jdbc.sql("SELECT active FROM cash_accounts WHERE id = :id AND store_id = :s")
                .param("id", id).param("s", storeId).query(Boolean.class).optional()
                .orElseThrow(() -> ApiException.notFound("Kassa / hesab tapılmadı"));
        if (!active) {
            throw ApiException.badRequest("ACCOUNT_INACTIVE", "Bu hesab deaktivdir");
        }
    }

    // ----- movements -----

    public List<Movement> movements(UUID accountId, LocalDate from, LocalDate to, String kind, int limit) {
        CurrentMember m = requireCash();
        StringBuilder where = new StringBuilder(" WHERE t.store_id = :s");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("s", m.storeId());
        if (accountId != null) { where.append(" AND t.account_id = :a"); p.put("a", accountId); }
        if (from != null) { where.append(" AND t.occurred_on >= :f"); p.put("f", from); }
        if (to != null) { where.append(" AND t.occurred_on <= :t"); p.put("t", to); }
        if (kind != null && !kind.isBlank()) { where.append(" AND t.kind = :k"); p.put("k", kind.toUpperCase(Locale.ROOT)); }
        var spec = jdbc.sql(MOVE_SELECT + where + " ORDER BY t.occurred_on DESC, t.created_at DESC, t.id LIMIT "
                + Math.min(Math.max(limit, 1), 1000));
        for (var e : p.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec.query((rs, n) -> mapMove(rs)).list();
    }

    @Transactional
    public Movement deposit(MoveRequest req) {
        CurrentMember m = requireCash();
        return manual(m, req, "IN", "DEPOSIT");
    }

    @Transactional
    public Movement withdraw(MoveRequest req) {
        CurrentMember m = requireCash();
        return manual(m, req, "OUT", "WITHDRAWAL");
    }

    /** "Kassa açılışı": the real money in the desk when HesabPro starts tracking it. Only once per account. */
    @Transactional
    public Movement opening(MoveRequest req) {
        CurrentMember m = tenant.requireOwner();
        lockAccount(m.storeId(), req.accountId());
        long existing = jdbc.sql("SELECT COUNT(*) FROM cash_transactions WHERE account_id = :a AND kind = 'OPENING'")
                .param("a", req.accountId()).query(Long.class).single();
        if (existing > 0) {
            throw ApiException.conflict("OPENING_EXISTS", "Bu hesabın açılış qalığı artıq daxil edilib. Fərqi sayım (inventarizasiya) ilə düzəldin");
        }
        return manual(m, req, "IN", "OPENING");
    }

    private Movement manual(CurrentMember m, MoveRequest req, String direction, String kind) {
        var dup = duplicate(m.storeId(), req.clientRequestId(), direction);
        if (dup != null) return dup;
        requireActiveAccount(m.storeId(), req.accountId());
        BigDecimal amount = Money.of(req.amount());
        LocalDate date = checkDate(req.date());
        lockAccount(m.storeId(), req.accountId());
        if ("OUT".equals(direction)) {
            requireFunds(m.storeId(), req.accountId(), amount);
        }
        UUID id = insert(m.storeId(), req.accountId(), direction, amount, kind, date, null, null, null, req.note(),
                m.userId(), req.clientRequestId());
        audit.log(m.storeId(), m.userId(), "CASH_" + kind, "CASH_ACCOUNT", req.accountId().toString(), amount + " AZN");
        return movement(m.storeId(), id);
    }

    /** Between two own accounts (cash ↔ cash, cash ↔ bank). Two rows, one transfer id; not income or expense. */
    @Transactional
    public List<Movement> transfer(TransferRequest req) {
        CurrentMember m = requireCash();
        if (req.fromAccountId().equals(req.toAccountId())) {
            throw ApiException.badRequest("SAME_ACCOUNT", "Eyni hesaba köçürmə olmaz");
        }
        var dup = duplicate(m.storeId(), req.clientRequestId(), "OUT");
        if (dup != null) {
            return movements(null, null, null, null, 1000).stream().filter(x -> dup.transferId().equals(x.transferId())).toList();
        }
        requireActiveAccount(m.storeId(), req.fromAccountId());
        requireActiveAccount(m.storeId(), req.toAccountId());
        BigDecimal amount = Money.of(req.amount());
        LocalDate date = checkDate(req.date());
        // lock both in a stable order so two opposite transfers cannot deadlock
        for (UUID id : req.fromAccountId().compareTo(req.toAccountId()) < 0
                ? List.of(req.fromAccountId(), req.toAccountId()) : List.of(req.toAccountId(), req.fromAccountId())) {
            lockAccount(m.storeId(), id);
        }
        requireFunds(m.storeId(), req.fromAccountId(), amount);
        UUID transferId = UUID.randomUUID();
        UUID out = insert(m.storeId(), req.fromAccountId(), "OUT", amount, "TRANSFER_OUT", date, transferId, null, null,
                req.note(), m.userId(), req.clientRequestId());
        UUID in = insert(m.storeId(), req.toAccountId(), "IN", amount, "TRANSFER_IN", date, transferId, null, null,
                req.note(), m.userId(), req.clientRequestId());
        audit.log(m.storeId(), m.userId(), "CASH_TRANSFER", "CASH_ACCOUNT", req.fromAccountId().toString(),
                amount + " AZN -> " + req.toAccountId());
        return List.of(movement(m.storeId(), out), movement(m.storeId(), in));
    }

    /** "Kassa inventarizasiyası": books the difference between counted and expected money. */
    @Transactional
    public CountResult count(CountRequest req) {
        CurrentMember m = requireCash();
        requireActiveAccount(m.storeId(), req.accountId());
        lockAccount(m.storeId(), req.accountId());
        BigDecimal expected = balance(m.storeId(), req.accountId());
        BigDecimal counted = Money.of(req.countedAmount());
        BigDecimal diff = Money.of(counted.subtract(expected));
        Movement adj = null;
        if (diff.signum() != 0) {
            UUID id = insert(m.storeId(), req.accountId(), diff.signum() > 0 ? "IN" : "OUT", diff.abs(), "COUNT_ADJUSTMENT",
                    tenant.today(), null, null, null,
                    "Sayım: gözlənilən " + expected + ", sayılan " + counted + (req.note() == null ? "" : " — " + req.note()),
                    m.userId(), null);
            adj = movement(m.storeId(), id);
        }
        audit.log(m.storeId(), m.userId(), "CASH_COUNT", "CASH_ACCOUNT", req.accountId().toString(),
                "gözlənilən " + expected + ", sayılan " + counted + ", fərq " + diff);
        return new CountResult(req.accountId(), expected, counted, diff, adj);
    }

    /** "Gün sonu hesabatı": per account opening, money in / out by kind, closing for one business day. */
    public DayReport dayReport(LocalDate date) {
        CurrentMember m = requireCash();
        LocalDate day = date != null ? date : tenant.today();
        List<DayLine> lines = new ArrayList<>();
        BigDecimal to = Money.zero(), ti = Money.zero(), tout = Money.zero(), tc = Money.zero();
        record Acc(UUID id, String name, String kind) {}
        List<Acc> accs = jdbc.sql("SELECT id, name, kind FROM cash_accounts WHERE store_id = :s ORDER BY kind, is_default DESC, name")
                .param("s", m.storeId()).query((rs, n) -> new Acc(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("kind"))).list();
        for (Acc a : accs) {
            BigDecimal opening = jdbc.sql("""
                    SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0)
                    FROM cash_transactions WHERE account_id = :a AND occurred_on < :d""")
                    .param("a", a.id()).param("d", day).query(BigDecimal.class).single();
            Map<String, BigDecimal> byKind = new LinkedHashMap<>();
            BigDecimal[] io = {Money.zero(), Money.zero()};
            jdbc.sql("""
                    SELECT kind, direction, SUM(amount) AS s FROM cash_transactions
                    WHERE account_id = :a AND occurred_on = :d GROUP BY kind, direction ORDER BY kind""")
                    .param("a", a.id()).param("d", day)
                    .query((rs, n) -> {
                        BigDecimal s = Money.of(rs.getBigDecimal("s"));
                        boolean in = "IN".equals(rs.getString("direction"));
                        byKind.merge(rs.getString("kind"), in ? s : s.negate(), BigDecimal::add);
                        if (in) io[0] = io[0].add(s); else io[1] = io[1].add(s);
                        return null;
                    }).list();
            BigDecimal op = Money.of(opening);
            BigDecimal close = Money.of(op.add(io[0]).subtract(io[1]));
            lines.add(new DayLine(a.id(), a.name(), a.kind(), op, Money.of(io[0]), Money.of(io[1]), close, byKind));
            to = to.add(op); ti = ti.add(io[0]); tout = tout.add(io[1]); tc = tc.add(close);
        }
        return new DayReport(day, lines, Money.of(to), Money.of(ti), Money.of(tout), Money.of(tc));
    }

    // ----- used by payments, expenses, sales -----

    /**
     * Books a money movement created by another module inside the caller's transaction.
     * Automatic movements may take an account below zero (the store may not have entered its opening balance yet);
     * manual withdrawals and transfers may not.
     */
    public UUID post(UUID storeId, UUID accountId, String direction, BigDecimal amount, String kind, LocalDate date,
                     UUID paymentId, UUID expenseId, String note, UUID actorId) {
        lockAccount(storeId, accountId);
        return insert(storeId, accountId, direction, Money.of(amount), kind, date, null, paymentId, expenseId, note, actorId, null);
    }

    /** Money of a sale or a sale refund (kind SALE / SALE_REFUND), linked to the sale. */
    public UUID postSale(UUID storeId, UUID accountId, String direction, BigDecimal amount, String kind, LocalDate date,
                         UUID saleId, String note, UUID actorId) {
        lockAccount(storeId, accountId);
        return insert(storeId, accountId, direction, Money.of(amount), kind, date, null, null, null, note, actorId, null, saleId);
    }

    public BigDecimal balance(UUID storeId, UUID accountId) {
        return Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0)
                FROM cash_transactions WHERE account_id = :a AND store_id = :s""")
                .param("a", accountId).param("s", storeId).query(BigDecimal.class).single());
    }

    // ----- helpers -----

    private Movement duplicate(UUID storeId, UUID requestId, String direction) {
        if (requestId == null) return null;
        return jdbc.sql(MOVE_SELECT + " WHERE t.store_id = :s AND t.client_request_id = :r AND t.direction = :d")
                .param("s", storeId).param("r", requestId).param("d", direction)
                .query((rs, n) -> mapMove(rs)).optional().orElse(null);
    }

    private void lockAccount(UUID storeId, UUID id) {
        jdbc.sql("SELECT id FROM cash_accounts WHERE id = :id AND store_id = :s FOR UPDATE")
                .param("id", id).param("s", storeId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Kassa / hesab tapılmadı"));
    }

    private void requireFunds(UUID storeId, UUID accountId, BigDecimal amount) {
        BigDecimal bal = balance(storeId, accountId);
        if (bal.compareTo(amount) < 0) {
            throw ApiException.conflict("INSUFFICIENT_FUNDS", "Hesabda kifayət qədər pul yoxdur (qalıq: " + bal + " AZN)");
        }
    }

    private LocalDate checkDate(LocalDate date) {
        LocalDate today = tenant.today();
        LocalDate d = date != null ? date : today;
        if (d.isAfter(today)) {
            throw ApiException.badRequest("FUTURE_DATE", "Tarix gələcəkdə ola bilməz");
        }
        return d;
    }

    private UUID insert(UUID storeId, UUID accountId, String direction, BigDecimal amount, String kind, LocalDate date,
                        UUID transferId, UUID paymentId, UUID expenseId, String note, UUID actorId, UUID requestId) {
        return insert(storeId, accountId, direction, amount, kind, date, transferId, paymentId, expenseId, note, actorId, requestId, null);
    }

    private UUID insert(UUID storeId, UUID accountId, String direction, BigDecimal amount, String kind, LocalDate date,
                        UUID transferId, UUID paymentId, UUID expenseId, String note, UUID actorId, UUID requestId, UUID saleId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO cash_transactions (id, store_id, account_id, direction, amount, kind, occurred_on, transfer_id,
                                               payment_id, expense_id, note, actor_id, client_request_id, sale_id)
                VALUES (:id, :s, :a, :dir, :amt, :k, :d, :tr, :p, :e, :note, :actor, :req, :sale)
                """).param("id", id).param("s", storeId).param("a", accountId).param("dir", direction)
                .param("amt", amount).param("k", kind).param("d", date).param("tr", transferId).param("p", paymentId)
                .param("e", expenseId).param("note", note == null || note.isBlank() ? null : note.trim())
                .param("actor", actorId).param("req", requestId).param("sale", saleId).update();
        return id;
    }

    private Account account(UUID storeId, UUID id) {
        return jdbc.sql(ACCOUNT_SELECT + " WHERE a.store_id = :s AND a.id = :id GROUP BY a.id")
                .param("s", storeId).param("id", id).query((rs, n) -> mapAccount(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Kassa / hesab tapılmadı"));
    }

    private Movement movement(UUID storeId, UUID id) {
        return jdbc.sql(MOVE_SELECT + " WHERE t.store_id = :s AND t.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> mapMove(rs)).single();
    }

    private static final String ACCOUNT_SELECT = """
            SELECT a.id, a.name, a.kind, a.is_default, a.active, a.created_at,
                   COALESCE(SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END), 0) AS balance
            FROM cash_accounts a LEFT JOIN cash_transactions t ON t.account_id = a.id""";

    private static final String MOVE_SELECT = """
            SELECT t.id, t.account_id, a.name AS account_name, t.direction, t.amount, t.kind, t.occurred_on, t.transfer_id,
                   t.payment_id, t.expense_id, t.note, u.full_name AS actor_name, t.created_at
            FROM cash_transactions t JOIN cash_accounts a ON a.id = t.account_id JOIN users u ON u.id = t.actor_id""";

    private static Account mapAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("kind"),
                rs.getBoolean("is_default"), rs.getBoolean("active"), Money.of(rs.getBigDecimal("balance")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static Movement mapMove(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Movement(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getString("direction"), Money.of(rs.getBigDecimal("amount")), rs.getString("kind"),
                rs.getObject("occurred_on", LocalDate.class), rs.getObject("transfer_id", UUID.class),
                rs.getObject("payment_id", UUID.class), rs.getObject("expense_id", UUID.class), rs.getString("note"),
                rs.getString("actor_name"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
