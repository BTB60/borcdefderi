package az.borcdefteri.expenses;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.cash.CashService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.common.Search;
import az.borcdefteri.subscriptions.ReceiptStorage;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Expenses (never mixed with debts), recurring expenses (planned until paid) and monthly budgets per category.
 * An expense always takes money out of a cash / bank account; edits and deletions post a reversal, so the
 * cash history is never rewritten.
 */
@Service
public class ExpenseService {

    public static final String PERM_EXPENSES = "EXPENSES";

    public record Category(UUID id, String code, String name, boolean custom) {}

    public record CategoryRequest(@jakarta.validation.constraints.NotBlank(message = "Adı daxil edin")
                                  @jakarta.validation.constraints.Size(max = 100) String name) {}

    public record Expense(UUID id, String title, UUID categoryId, String categoryName, BigDecimal amount,
                          LocalDate expenseDate, String paymentMethod, UUID accountId, String accountName, String payee,
                          String note, UUID recurringId, LocalDate periodDate, String createdByName,
                          OffsetDateTime createdAt, long version, long attachmentCount) {}

    public record ExpenseRequest(
            @jakarta.validation.constraints.NotBlank(message = "Xərcin adını daxil edin")
            @jakarta.validation.constraints.Size(max = 150) String title,
            @jakarta.validation.constraints.NotNull(message = "Kateqoriya seçin") UUID categoryId,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
            LocalDate expenseDate,
            @jakarta.validation.constraints.Pattern(regexp = "CASH|CARD|TRANSFER|OTHER") String paymentMethod,
            UUID accountId,
            @jakarta.validation.constraints.Size(max = 150) String payee,
            @jakarta.validation.constraints.Size(max = 1000) String note,
            Long version,
            UUID clientRequestId) {}

    public record ExpensePage(List<Expense> items, long total, BigDecimal sum, int page, int size) {}

    public record Attachment(UUID id, String originalName, String contentType, long sizeBytes, OffsetDateTime uploadedAt) {}

    public record SummaryRow(String key, String label, long count, BigDecimal total) {}

    public record Summary(LocalDate from, LocalDate to, String groupBy, List<SummaryRow> rows, BigDecimal total, long count) {}

    public record Recurring(UUID id, String title, UUID categoryId, String categoryName, BigDecimal amount, String frequency,
                            LocalDate startDate, LocalDate endDate, LocalDate nextDueDate, UUID accountId, String payee,
                            boolean active, boolean due, long version) {}

    public record RecurringRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 150) String title,
            @jakarta.validation.constraints.NotNull UUID categoryId,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal amount,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Pattern(regexp = "DAILY|WEEKLY|MONTHLY|YEARLY", message = "Tezlik düzgün deyil") String frequency,
            @jakarta.validation.constraints.NotNull LocalDate startDate,
            LocalDate endDate,
            UUID accountId,
            @jakarta.validation.constraints.Size(max = 150) String payee,
            Boolean active) {}

    /** Pay the current period of a recurring expense. {@code periodDate} must be the plan's next due date. */
    public record PayRecurringRequest(
            @jakarta.validation.constraints.NotNull LocalDate periodDate,
            @jakarta.validation.constraints.DecimalMin(value = "0.01")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal amount,
            LocalDate paidOn,
            @jakarta.validation.constraints.Pattern(regexp = "CASH|CARD|TRANSFER|OTHER") String paymentMethod,
            UUID accountId,
            @jakarta.validation.constraints.Size(max = 1000) String note) {}

    public record Budget(UUID id, UUID categoryId, String categoryName, LocalDate month, BigDecimal limit, BigDecimal actual,
                         BigDecimal remaining, int percent, String state) {}

    public record BudgetRequest(
            @jakarta.validation.constraints.NotNull UUID categoryId,
            @jakarta.validation.constraints.NotNull LocalDate month,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "Limit 0-dan böyük olmalıdır")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal limit) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final CashService cash;
    private final AuditService audit;
    private final ReceiptStorage files;

    public ExpenseService(JdbcClient jdbc, TenantService tenant, CashService cash, AuditService audit, ReceiptStorage files) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.cash = cash;
        this.audit = audit;
        this.files = files;
    }

    private CurrentMember requireExpenses() {
        return tenant.requirePermission(PERM_EXPENSES);
    }

    // ----- categories -----

    public List<Category> categories() {
        CurrentMember m = requireExpenses();
        return jdbc.sql("""
                SELECT id, code, name, store_id IS NOT NULL AS custom FROM expense_categories
                WHERE (store_id IS NULL OR store_id = :s) AND active ORDER BY store_id NULLS FIRST, code, name
                """).param("s", m.storeId())
                .query((rs, n) -> new Category(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("custom"))).list();
    }

    @Transactional
    public Category createCategory(CategoryRequest req) {
        CurrentMember m = requireExpenses();
        String name = req.name().trim();
        long clash = jdbc.sql("SELECT COUNT(*) FROM expense_categories WHERE (store_id IS NULL OR store_id = :s) AND "
                        + "LOWER(TRANSLATE(name, 'İIƏÖÜĞÇŞ', 'iıəöüğçş')) = LOWER(TRANSLATE(:n, 'İIƏÖÜĞÇŞ', 'iıəöüğçş'))")
                .param("s", m.storeId()).param("n", name).query(Long.class).single();
        if (clash > 0) {
            throw ApiException.conflict("DUPLICATE_NAME", "Bu adda kateqoriya artıq var");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO expense_categories (id, store_id, name) VALUES (:id, :s, :n)")
                .param("id", id).param("s", m.storeId()).param("n", name).update();
        audit.log(m.storeId(), m.userId(), "EXPENSE_CATEGORY_CREATED", "EXPENSE_CATEGORY", id.toString(), name);
        return new Category(id, null, name, true);
    }

    private void requireCategory(UUID storeId, UUID categoryId) {
        long ok = jdbc.sql("SELECT COUNT(*) FROM expense_categories WHERE id = :id AND (store_id IS NULL OR store_id = :s) AND active")
                .param("id", categoryId).param("s", storeId).query(Long.class).single();
        if (ok == 0) {
            throw ApiException.notFound("Kateqoriya tapılmadı");
        }
    }

    // ----- expenses -----

    public ExpensePage list(LocalDate from, LocalDate to, UUID categoryId, String q, int page, int size) {
        CurrentMember m = requireExpenses();
        StringBuilder where = new StringBuilder(" WHERE e.store_id = :s AND e.deleted_at IS NULL");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("s", m.storeId());
        if (from != null) { where.append(" AND e.expense_date >= :f"); p.put("f", from); }
        if (to != null) { where.append(" AND e.expense_date <= :t"); p.put("t", to); }
        if (categoryId != null) { where.append(" AND e.category_id = :c"); p.put("c", categoryId); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(e.title) LIKE LOWER(:q) OR LOWER(COALESCE(e.payee, '')) LIKE LOWER(:q) OR LOWER(COALESCE(e.note, '')) LIKE LOWER(:q))");
            p.put("q", Search.contains(q));
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        var spec = jdbc.sql(SELECT + where + " ORDER BY e.expense_date DESC, e.created_at DESC LIMIT " + safeSize
                + " OFFSET " + ((long) safePage * safeSize));
        var tot = jdbc.sql("SELECT COUNT(*) AS n, COALESCE(SUM(e.amount), 0) AS s FROM expenses e" + where);
        for (var e : p.entrySet()) { spec = spec.param(e.getKey(), e.getValue()); tot = tot.param(e.getKey(), e.getValue()); }
        List<Expense> items = spec.query((rs, n) -> map(rs)).list();
        Object[] t = tot.query((rs, n) -> new Object[]{rs.getLong("n"), Money.of(rs.getBigDecimal("s"))}).single();
        return new ExpensePage(items, (Long) t[0], (BigDecimal) t[1], safePage, safeSize);
    }

    public Expense get(UUID id) {
        CurrentMember m = requireExpenses();
        return load(m.storeId(), id);
    }

    @Transactional
    public Expense create(ExpenseRequest req) {
        CurrentMember m = requireExpenses();
        if (req.clientRequestId() != null) {
            var dup = jdbc.sql("SELECT id FROM expenses WHERE store_id = :s AND client_request_id = :r")
                    .param("s", m.storeId()).param("r", req.clientRequestId()).query(UUID.class).optional();
            if (dup.isPresent()) return load(m.storeId(), dup.get());
        }
        return insertExpense(m, req.title(), req.categoryId(), req.amount(), req.expenseDate(), req.paymentMethod(),
                req.accountId(), req.payee(), req.note(), null, null, req.clientRequestId());
    }

    private Expense insertExpense(CurrentMember m, String title, UUID categoryId, BigDecimal amountIn, LocalDate dateIn,
                                  String methodIn, UUID accountIn, String payee, String note, UUID recurringId,
                                  LocalDate periodDate, UUID requestId) {
        requireCategory(m.storeId(), categoryId);
        BigDecimal amount = Money.of(amountIn);
        LocalDate date = checkDate(dateIn);
        String method = methodIn == null ? "CASH" : methodIn;
        UUID account = cash.accountFor(m.storeId(), method, accountIn, m.userId());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO expenses (id, store_id, title, category_id, amount, expense_date, payment_method, account_id,
                                          payee, note, recurring_id, period_date, created_by, client_request_id)
                    VALUES (:id, :s, :t, :c, :a, :d, :pm, :acc, :payee, :note, :rec, :per, :by, :req)
                    """).param("id", id).param("s", m.storeId()).param("t", title.trim()).param("c", categoryId)
                    .param("a", amount).param("d", date).param("pm", method).param("acc", account)
                    .param("payee", blank(payee)).param("note", blank(note)).param("rec", recurringId)
                    .param("per", periodDate).param("by", m.userId()).param("req", requestId).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_PAID", "Bu dövr üçün xərc artıq qeydə alınıb");
        }
        cash.post(m.storeId(), account, "OUT", amount, "EXPENSE", date, null, id, title.trim(), m.userId());
        audit.log(m.storeId(), m.userId(), "EXPENSE_CREATED", "EXPENSE", id.toString(), title.trim() + ": " + amount + " AZN");
        return load(m.storeId(), id);
    }

    @Transactional
    public Expense update(UUID id, ExpenseRequest req) {
        CurrentMember m = requireExpenses();
        Expense cur = lock(m.storeId(), id);
        if (req.version() == null || req.version() != cur.version()) {
            throw ApiException.conflict("VERSION_CONFLICT", "Xərc başqa istifadəçi tərəfindən dəyişdirilib. Yenidən yükləyin");
        }
        requireCategory(m.storeId(), req.categoryId());
        BigDecimal amount = Money.of(req.amount());
        LocalDate date = checkDate(req.expenseDate() != null ? req.expenseDate() : cur.expenseDate());
        String method = req.paymentMethod() == null ? cur.paymentMethod() : req.paymentMethod();
        UUID account = req.accountId() != null ? cash.accountFor(m.storeId(), method, req.accountId(), m.userId())
                : (method.equals(cur.paymentMethod()) ? cur.accountId() : cash.accountFor(m.storeId(), method, null, m.userId()));
        boolean moneyChanged = amount.compareTo(cur.amount()) != 0 || !account.equals(cur.accountId()) || !date.equals(cur.expenseDate());
        jdbc.sql("""
                UPDATE expenses SET title = :t, category_id = :c, amount = :a, expense_date = :d, payment_method = :pm,
                       account_id = :acc, payee = :payee, note = :note, updated_at = now(), version = version + 1
                WHERE id = :id AND store_id = :s
                """).param("t", req.title().trim()).param("c", req.categoryId()).param("a", amount).param("d", date)
                .param("pm", method).param("acc", account).param("payee", blank(req.payee())).param("note", blank(req.note()))
                .param("id", id).param("s", m.storeId()).update();
        if (moneyChanged) {
            cash.post(m.storeId(), cur.accountId(), "IN", cur.amount(), "EXPENSE_REVERSAL", tenant.today(), null, id,
                    "Düzəliş: " + cur.title(), m.userId());
            cash.post(m.storeId(), account, "OUT", amount, "EXPENSE", date, null, id, req.title().trim(), m.userId());
        }
        audit.log(m.storeId(), m.userId(), "EXPENSE_UPDATED", "EXPENSE", id.toString(),
                cur.title() + ": " + cur.amount() + " -> " + amount + " AZN, " + cur.expenseDate() + " -> " + date
                        + ", " + cur.categoryName() + " -> " + req.categoryId());
        return load(m.storeId(), id);
    }

    @Transactional
    public void delete(UUID id) {
        CurrentMember m = requireExpenses();
        Expense cur = lock(m.storeId(), id);
        jdbc.sql("UPDATE expenses SET deleted_at = now(), deleted_by = :by, version = version + 1 WHERE id = :id AND store_id = :s")
                .param("by", m.userId()).param("id", id).param("s", m.storeId()).update();
        cash.post(m.storeId(), cur.accountId(), "IN", cur.amount(), "EXPENSE_REVERSAL", tenant.today(), null, id,
                "Silindi: " + cur.title(), m.userId());
        audit.log(m.storeId(), m.userId(), "EXPENSE_DELETED", "EXPENSE", id.toString(), cur.title() + ": " + cur.amount() + " AZN");
    }

    /** Daily / monthly / yearly totals or totals per category for a period (deleted expenses excluded). */
    public Summary summary(LocalDate from, LocalDate to, String groupBy) {
        CurrentMember m = requireExpenses();
        LocalDate today = tenant.today();
        LocalDate lo = from != null ? from : today.withDayOfMonth(1);
        LocalDate hi = to != null ? to : today;
        String g = groupBy == null ? "category" : groupBy.toLowerCase(Locale.ROOT);
        String key = switch (g) {
            case "day" -> "TO_CHAR(e.expense_date, 'YYYY-MM-DD')";
            case "month" -> "TO_CHAR(e.expense_date, 'YYYY-MM')";
            case "year" -> "TO_CHAR(e.expense_date, 'YYYY')";
            case "category" -> "c.name";
            default -> throw ApiException.badRequest("INVALID_GROUP", "Qruplaşdırma: day, month, year və ya category");
        };
        List<SummaryRow> rows = jdbc.sql("SELECT " + key + " AS k, COUNT(*) AS n, SUM(e.amount) AS s FROM expenses e "
                        + "JOIN expense_categories c ON c.id = e.category_id WHERE e.store_id = :s AND e.deleted_at IS NULL "
                        + "AND e.expense_date BETWEEN :lo AND :hi GROUP BY 1 ORDER BY " + ("category".equals(g) ? "s DESC" : "1"))
                .param("s", m.storeId()).param("lo", lo).param("hi", hi)
                .query((rs, n) -> new SummaryRow(rs.getString("k"), rs.getString("k"), rs.getLong("n"), Money.of(rs.getBigDecimal("s"))))
                .list();
        BigDecimal total = Money.zero();
        long count = 0;
        for (SummaryRow r : rows) { total = total.add(r.total()); count += r.count(); }
        return new Summary(lo, hi, g, rows, Money.of(total), count);
    }

    // ----- attachments -----

    @Transactional
    public Attachment attach(UUID expenseId, MultipartFile file) {
        CurrentMember m = requireExpenses();
        load(m.storeId(), expenseId);
        ReceiptStorage.Stored s = files.store(file);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO expense_attachments (id, store_id, expense_id, original_name, content_type, size_bytes, content, uploaded_by)
                VALUES (:id, :s, :e, :n, :t, :sz, :c, :by)
                """).param("id", id).param("s", m.storeId()).param("e", expenseId).param("n", s.originalName())
                .param("t", s.contentType()).param("sz", s.sizeBytes()).param("c", s.content()).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "EXPENSE_ATTACHMENT_ADDED", "EXPENSE", expenseId.toString(), s.originalName());
        return new Attachment(id, s.originalName(), s.contentType(), s.sizeBytes(), OffsetDateTime.now());
    }

    public List<Attachment> attachments(UUID expenseId) {
        CurrentMember m = requireExpenses();
        load(m.storeId(), expenseId);
        return jdbc.sql("""
                SELECT id, original_name, content_type, size_bytes, uploaded_at FROM expense_attachments
                WHERE store_id = :s AND expense_id = :e ORDER BY uploaded_at
                """).param("s", m.storeId()).param("e", expenseId)
                .query((rs, n) -> new Attachment(rs.getObject("id", UUID.class), rs.getString("original_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getObject("uploaded_at", OffsetDateTime.class)))
                .list();
    }

    public record FileContent(byte[] content, String contentType, String fileName) {}

    public FileContent attachment(UUID expenseId, UUID attachmentId) {
        CurrentMember m = requireExpenses();
        return jdbc.sql("SELECT content, content_type, original_name FROM expense_attachments WHERE id = :id AND expense_id = :e AND store_id = :s")
                .param("id", attachmentId).param("e", expenseId).param("s", m.storeId())
                .query((rs, n) -> new FileContent(rs.getBytes("content"), rs.getString("content_type"),
                        rs.getString("original_name") == null ? "qebz" : rs.getString("original_name")))
                .optional().orElseThrow(() -> ApiException.notFound("Fayl tapılmadı"));
    }

    // ----- recurring expenses -----

    public List<Recurring> recurring() {
        CurrentMember m = requireExpenses();
        LocalDate today = tenant.today();
        return jdbc.sql(REC_SELECT + " WHERE r.store_id = :s ORDER BY r.active DESC, r.next_due_date")
                .param("s", m.storeId()).query((rs, n) -> mapRec(rs, today)).list();
    }

    @Transactional
    public Recurring createRecurring(RecurringRequest req) {
        CurrentMember m = requireExpenses();
        requireCategory(m.storeId(), req.categoryId());
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw ApiException.badRequest("INVALID_RANGE", "Bitmə tarixi başlama tarixindən əvvəl ola bilməz");
        }
        if (req.accountId() != null) cash.accountFor(m.storeId(), "CASH", req.accountId(), m.userId());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO recurring_expenses (id, store_id, title, category_id, amount, frequency, start_date, end_date,
                                                next_due_date, account_id, payee, active, created_by)
                VALUES (:id, :s, :t, :c, :a, :f, :sd, :ed, :sd, :acc, :p, :act, :by)
                """).param("id", id).param("s", m.storeId()).param("t", req.title().trim()).param("c", req.categoryId())
                .param("a", Money.of(req.amount())).param("f", req.frequency()).param("sd", req.startDate())
                .param("ed", req.endDate()).param("acc", req.accountId()).param("p", blank(req.payee()))
                .param("act", req.active() == null || req.active()).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "RECURRING_EXPENSE_CREATED", "RECURRING_EXPENSE", id.toString(),
                req.title() + " " + req.frequency() + " " + req.amount());
        return loadRec(m.storeId(), id);
    }

    @Transactional
    public Recurring updateRecurring(UUID id, RecurringRequest req) {
        CurrentMember m = requireExpenses();
        Recurring cur = loadRec(m.storeId(), id);
        requireCategory(m.storeId(), req.categoryId());
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw ApiException.badRequest("INVALID_RANGE", "Bitmə tarixi başlama tarixindən əvvəl ola bilməz");
        }
        if (req.accountId() != null) cash.accountFor(m.storeId(), "CASH", req.accountId(), m.userId());
        // a changed start date moves the next due date only if nothing was paid yet
        long paid = jdbc.sql("SELECT COUNT(*) FROM expenses WHERE recurring_id = :id AND deleted_at IS NULL")
                .param("id", id).query(Long.class).single();
        LocalDate next = paid == 0 ? req.startDate() : cur.nextDueDate();
        jdbc.sql("""
                UPDATE recurring_expenses SET title = :t, category_id = :c, amount = :a, frequency = :f, start_date = :sd,
                       end_date = :ed, next_due_date = :n, account_id = :acc, payee = :p, active = :act, version = version + 1
                WHERE id = :id AND store_id = :s
                """).param("t", req.title().trim()).param("c", req.categoryId()).param("a", Money.of(req.amount()))
                .param("f", req.frequency()).param("sd", req.startDate()).param("ed", req.endDate()).param("n", next)
                .param("acc", req.accountId()).param("p", blank(req.payee())).param("act", req.active() == null || req.active())
                .param("id", id).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "RECURRING_EXPENSE_UPDATED", "RECURRING_EXPENSE", id.toString(), req.title());
        return loadRec(m.storeId(), id);
    }

    /** Pays the plan's current period: creates the real expense and moves the next due date forward. */
    @Transactional
    public Expense payRecurring(UUID id, PayRecurringRequest req) {
        CurrentMember m = requireExpenses();
        jdbc.sql("SELECT id FROM recurring_expenses WHERE id = :id AND store_id = :s FOR UPDATE")
                .param("id", id).param("s", m.storeId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Təkrarlanan xərc tapılmadı"));
        Recurring r = loadRec(m.storeId(), id);
        if (!r.active()) {
            throw ApiException.conflict("RECURRING_INACTIVE", "Bu təkrarlanan xərc deaktivdir");
        }
        if (!req.periodDate().equals(r.nextDueDate())) {
            throw ApiException.conflict("ALREADY_PAID", "Bu dövr artıq ödənilib və ya növbəti dövr " + r.nextDueDate() + " tarixidir");
        }
        LocalDate paidOn = req.paidOn() != null ? req.paidOn() : tenant.today();
        Expense e = insertExpense(m, r.title(), r.categoryId(), req.amount() != null ? req.amount() : r.amount(), paidOn,
                req.paymentMethod(), req.accountId() != null ? req.accountId() : r.accountId(), r.payee(), req.note(),
                id, r.nextDueDate(), null);
        LocalDate next = advance(r.nextDueDate(), r.frequency(), r.startDate());
        boolean stillActive = r.endDate() == null || !next.isAfter(r.endDate());
        jdbc.sql("UPDATE recurring_expenses SET next_due_date = :n, active = :a, version = version + 1 WHERE id = :id")
                .param("n", next).param("a", stillActive).param("id", id).update();
        return e;
    }

    static LocalDate advance(LocalDate d, String frequency, LocalDate anchor) {
        return switch (frequency) {
            case "DAILY" -> d.plusDays(1);
            case "WEEKLY" -> d.plusWeeks(1);
            case "MONTHLY" -> {
                LocalDate n = d.plusMonths(1);
                // keep the start day (e.g. the 31st) when the month allows it
                yield n.withDayOfMonth(Math.min(anchor.getDayOfMonth(), n.lengthOfMonth()));
            }
            case "YEARLY" -> d.plusYears(1);
            default -> throw new IllegalArgumentException(frequency);
        };
    }

    // ----- budgets -----

    public List<Budget> budgets(LocalDate month) {
        CurrentMember m = requireExpenses();
        LocalDate first = (month != null ? month : tenant.today()).withDayOfMonth(1);
        return jdbc.sql("""
                SELECT b.id, b.category_id, c.name, b.month, b.limit_amount,
                       COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.store_id = b.store_id AND e.category_id = b.category_id
                                 AND e.deleted_at IS NULL AND e.expense_date >= b.month
                                 AND e.expense_date < (b.month + INTERVAL '1 month')::date), 0) AS actual
                FROM budgets b JOIN expense_categories c ON c.id = b.category_id
                WHERE b.store_id = :s AND b.month = :m ORDER BY c.name
                """).param("s", m.storeId()).param("m", first)
                .query((rs, n) -> {
                    BigDecimal limit = Money.of(rs.getBigDecimal("limit_amount"));
                    BigDecimal actual = Money.of(rs.getBigDecimal("actual"));
                    int pct = actual.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.FLOOR).intValue();
                    String state = actual.compareTo(limit) > 0 ? "OVER" : pct >= 80 ? "NEAR" : "OK";
                    return new Budget(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
                            rs.getString("name"), rs.getObject("month", LocalDate.class), limit, actual,
                            Money.of(limit.subtract(actual)), pct, state);
                }).list();
    }

    @Transactional
    public List<Budget> setBudget(BudgetRequest req) {
        CurrentMember m = requireExpenses();
        requireCategory(m.storeId(), req.categoryId());
        LocalDate first = req.month().withDayOfMonth(1);
        jdbc.sql("""
                INSERT INTO budgets (id, store_id, category_id, month, limit_amount, created_by)
                VALUES (:id, :s, :c, :m, :l, :by)
                ON CONFLICT (store_id, category_id, month) DO UPDATE SET limit_amount = EXCLUDED.limit_amount, updated_at = now()
                """).param("id", UUID.randomUUID()).param("s", m.storeId()).param("c", req.categoryId()).param("m", first)
                .param("l", Money.of(req.limit())).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "BUDGET_SET", "BUDGET", req.categoryId().toString(), first + ": " + req.limit());
        return budgets(first);
    }

    @Transactional
    public void deleteBudget(UUID id) {
        CurrentMember m = requireExpenses();
        int n = jdbc.sql("DELETE FROM budgets WHERE id = :id AND store_id = :s").param("id", id).param("s", m.storeId()).update();
        if (n == 0) throw ApiException.notFound("Büdcə tapılmadı");
        audit.log(m.storeId(), m.userId(), "BUDGET_DELETED", "BUDGET", id.toString(), null);
    }

    // ----- helpers -----

    private LocalDate checkDate(LocalDate date) {
        LocalDate today = tenant.today();
        LocalDate d = date != null ? date : today;
        if (d.isAfter(today)) {
            throw ApiException.badRequest("FUTURE_DATE", "Tarix gələcəkdə ola bilməz");
        }
        return d;
    }

    private Expense lock(UUID storeId, UUID id) {
        jdbc.sql("SELECT id FROM expenses WHERE id = :id AND store_id = :s AND deleted_at IS NULL FOR UPDATE")
                .param("id", id).param("s", storeId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Xərc tapılmadı"));
        return load(storeId, id);
    }

    private Expense load(UUID storeId, UUID id) {
        return jdbc.sql(SELECT + " WHERE e.store_id = :s AND e.id = :id AND e.deleted_at IS NULL")
                .param("s", storeId).param("id", id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Xərc tapılmadı"));
    }

    private Recurring loadRec(UUID storeId, UUID id) {
        return jdbc.sql(REC_SELECT + " WHERE r.store_id = :s AND r.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> mapRec(rs, tenant.today())).optional()
                .orElseThrow(() -> ApiException.notFound("Təkrarlanan xərc tapılmadı"));
    }

    private static final String SELECT = """
            SELECT e.id, e.title, e.category_id, c.name AS category_name, e.amount, e.expense_date, e.payment_method,
                   e.account_id, a.name AS account_name, e.payee, e.note, e.recurring_id, e.period_date,
                   u.full_name AS created_by_name, e.created_at, e.version,
                   (SELECT COUNT(*) FROM expense_attachments x WHERE x.expense_id = e.id) AS attachment_count
            FROM expenses e
            JOIN expense_categories c ON c.id = e.category_id
            JOIN cash_accounts a ON a.id = e.account_id
            JOIN users u ON u.id = e.created_by""";

    private static final String REC_SELECT = """
            SELECT r.id, r.title, r.category_id, c.name AS category_name, r.amount, r.frequency, r.start_date, r.end_date,
                   r.next_due_date, r.account_id, r.payee, r.active, r.version
            FROM recurring_expenses r JOIN expense_categories c ON c.id = r.category_id""";

    private static Expense map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Expense(rs.getObject("id", UUID.class), rs.getString("title"), rs.getObject("category_id", UUID.class),
                rs.getString("category_name"), Money.of(rs.getBigDecimal("amount")), rs.getObject("expense_date", LocalDate.class),
                rs.getString("payment_method"), rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getString("payee"), rs.getString("note"), rs.getObject("recurring_id", UUID.class),
                rs.getObject("period_date", LocalDate.class), rs.getString("created_by_name"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getLong("version"), rs.getLong("attachment_count"));
    }

    private static Recurring mapRec(java.sql.ResultSet rs, LocalDate today) throws java.sql.SQLException {
        LocalDate next = rs.getObject("next_due_date", LocalDate.class);
        boolean active = rs.getBoolean("active");
        return new Recurring(rs.getObject("id", UUID.class), rs.getString("title"), rs.getObject("category_id", UUID.class),
                rs.getString("category_name"), Money.of(rs.getBigDecimal("amount")), rs.getString("frequency"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), next,
                rs.getObject("account_id", UUID.class), rs.getString("payee"), active, active && !next.isAfter(today),
                rs.getLong("version"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
