package az.borcdefteri.payments;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.debts.DebtService;
import az.borcdefteri.debts.DebtService.DebtView;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.debts.LedgerWriter;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    public record PaymentView(UUID id, UUID debtId, DebtType debtType, String partyName, String partyPhone, BigDecimal amount,
                              PaymentMethod paymentMethod, OffsetDateTime paidAt, String description, String note,
                              UUID recordedBy, String recordedByName, OffsetDateTime createdAt,
                              BigDecimal remainingAfter, String debtStatusAfter, String receiptUrl,
                              OffsetDateTime voidedAt, String voidReason, OffsetDateTime editedAt) {}

    private static final String PAYMENT_SELECT = """
            SELECT p.id, p.debt_id, p.amount, p.payment_method, p.paid_at, p.description, p.note,
                   p.recorded_by, p.created_at, u.full_name AS recorded_by_name, d.debt_type,
                   p.voided_at, p.void_reason, p.edited_at,
                   COALESCE(c.name, co.name) AS party_name, COALESCE(c.phone, co.phone) AS party_phone
            FROM payments p
            JOIN debts d ON d.id = p.debt_id AND d.store_id = p.store_id
            LEFT JOIN customers c ON c.id = d.customer_id
            LEFT JOIN companies co ON co.id = d.company_id
            JOIN users u ON u.id = p.recorded_by
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final DebtService debts;
    private final LedgerWriter ledger;
    private final AuditService audit;
    private final Clock clock;
    private final ReceiptLinks links;
    private final az.borcdefteri.cash.CashService cash;

    public PaymentService(JdbcClient jdbc, TenantService tenant, DebtService debts, LedgerWriter ledger,
                          AuditService audit, Clock clock, ReceiptLinks links, az.borcdefteri.cash.CashService cash) {
        this.cash = cash;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.debts = debts;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
        this.links = links;
    }

    /**
     * Records a CASH or CARD payment against a debt. Runs in one DB transaction with a row lock on the debt,
     * so concurrent payments cannot overpay the balance. The remaining balance never becomes negative.
     */
    @Transactional
    public PaymentView record(UUID debtId, PaymentRequest req) {
        CurrentMember m = tenant.requireBusinessMember();
        DebtView debt = debts.lockAndLoad(m, debtId);
        debts.requireAccess(m, debt.debtType());

        if ("PAID".equals(debt.status()) || debt.remainingAmount().signum() <= 0) {
            throw ApiException.conflict("DEBT_ALREADY_PAID", "Bu borc tam ödənilib");
        }
        BigDecimal amount = Money.of(req.amount());
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Ödəniş məbləği 0-dan böyük olmalıdır");
        }
        if (amount.compareTo(debt.remainingAmount()) > 0) {
            throw ApiException.conflict("PAYMENT_EXCEEDS_BALANCE",
                    "Ödəniş qalıq borcdan (" + debt.remainingAmount() + " AZN) böyük ola bilməz");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime paidAt = req.paidAt() != null ? req.paidAt() : now;
        if (paidAt.isAfter(now.plusMinutes(5))) {
            throw ApiException.badRequest("FUTURE_PAYMENT", "Ödəniş tarixi gələcəkdə ola bilməz");
        }

        UUID paymentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments (id, store_id, debt_id, amount, payment_method, paid_at, description, note, recorded_by)
                VALUES (:id, :storeId, :debtId, :amount, :method, :paidAt, :description, :note, :by)
                """)
                .param("id", paymentId).param("storeId", m.storeId()).param("debtId", debtId)
                .param("amount", amount).param("method", req.paymentMethod().name()).param("paidAt", paidAt)
                .param("description", blank(req.description())).param("note", blank(req.note()))
                .param("by", m.userId())
                .update();

        BigDecimal newPaid = Money.of(debt.paidAmount().add(amount));
        BigDecimal newRemaining = Money.of(debt.remainingAmount().subtract(amount));
        String status = DebtService.statusFor(newPaid, newRemaining);
        jdbc.sql("""
                UPDATE debts SET paid_amount = :paid, remaining_amount = :remaining, status = :status,
                       updated_at = now(), version = version + 1
                WHERE id = :id AND store_id = :storeId
                """)
                .param("paid", newPaid).param("remaining", newRemaining).param("status", status)
                .param("id", debtId).param("storeId", m.storeId())
                .update();

        ledger.append(m.storeId(), debtId, paymentId,
                req.paymentMethod().ledgerType(),
                amount, newRemaining, m.userId());
        // The money moves through a cash desk / bank account. Collecting a debt is not revenue and paying a supplier
        // is not an expense: they are booked as DEBT_COLLECTION / SUPPLIER_PAYMENT and kept out of profit.
        boolean outgoing = debt.debtType() == DebtType.COMPANY_PAYABLE;
        UUID account = cash.accountFor(m.storeId(), req.paymentMethod().name(), req.cashAccountId(), m.userId());
        cash.post(m.storeId(), account, outgoing ? "OUT" : "IN", amount, outgoing ? "SUPPLIER_PAYMENT" : "DEBT_COLLECTION",
                paidAt.atZoneSameInstant(java.time.ZoneId.of("Asia/Baku")).toLocalDate(), paymentId, null,
                debt.partyName(), m.userId());
        audit.log(m.storeId(), m.userId(), "PAYMENT_RECORDED", "PAYMENT", paymentId.toString(),
                req.paymentMethod() + " " + amount + " AZN, borc " + debtId);
        return new PaymentView(paymentId, debtId, debt.debtType(), debt.partyName(), debt.partyPhone(), amount, req.paymentMethod(),
                paidAt, blank(req.description()), blank(req.note()), m.userId(), null, now, newRemaining, status,
                links.url(paymentId), null, null, null);
    }

    /** Payments across the debt types the caller may see. */
    public List<PaymentView> list(LocalDate from, LocalDate to, PaymentMethod method, UUID debtId, int limit) {
        return list(from, to, method, debtId, limit, false);
    }

    /** Same; cancelled payments only when {@code includeVoided} (older apps do not know about them). */
    public List<PaymentView> list(LocalDate from, LocalDate to, PaymentMethod method, UUID debtId, int limit,
                                  boolean includeVoided) {
        return list(tenant.requireBusinessMember(), from, to, method, debtId, Math.min(Math.max(limit, 1), 500),
                null, null, includeVoided);
    }

    /** Payments for the reports export (REPORTS permission checked by the caller). Capped to protect memory. */
    public List<PaymentView> listForExport(CurrentMember m, LocalDate from, LocalDate to) {
        return list(m, from, to, null, null, 10_000);
    }

    /** Payments of one customer or company (all their debts), limited to the sections the member may see. */
    public List<PaymentView> listForParty(CurrentMember m, boolean customer, UUID partyId) {
        return list(m, null, null, null, null, 1000, customer ? "d.customer_id" : "d.company_id", partyId, false);
    }

    private List<PaymentView> list(CurrentMember m, LocalDate from, LocalDate to, PaymentMethod method, UUID debtId, int limit) {
        return list(m, from, to, method, debtId, limit, null, null, false);
    }

    private List<PaymentView> list(CurrentMember m, LocalDate from, LocalDate to, PaymentMethod method, UUID debtId,
                                   int limit, String partyColumn, UUID partyId, boolean includeVoided) {
        String allowed = Arrays.stream(DebtType.values())
                .filter(t -> m.has(t.permission()))
                .map(t -> "'" + t.name() + "'")
                .collect(Collectors.joining(","));
        if (allowed.isEmpty()) {
            return List.of();
        }
        StringBuilder where = new StringBuilder(" WHERE p.store_id = :storeId AND d.debt_type IN (" + allowed + ")");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", m.storeId());
        if (!includeVoided) {
            where.append(" AND p.voided_at IS NULL");
        }
        if (from != null) {
            where.append(" AND (p.paid_at AT TIME ZONE 'Asia/Baku')::date >= :from");
            params.put("from", from);
        }
        if (to != null) {
            where.append(" AND (p.paid_at AT TIME ZONE 'Asia/Baku')::date <= :to");
            params.put("to", to);
        }
        if (method != null) {
            where.append(" AND p.payment_method = :method");
            params.put("method", method.name());
        }
        if (debtId != null) {
            where.append(" AND p.debt_id = :debtId");
            params.put("debtId", debtId);
        }
        if (partyColumn != null) {
            // partyColumn is one of two constants chosen by listForParty, never user input
            where.append(" AND ").append(partyColumn).append(" = :partyId");
            params.put("partyId", partyId);
        }
        var spec = jdbc.sql(PAYMENT_SELECT + where
                + " ORDER BY p.paid_at DESC, p.id DESC LIMIT " + limit);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query((rs, n) -> mapView(rs, null, null)).list();
    }

    private PaymentView mapView(java.sql.ResultSet rs, BigDecimal remainingAfter, String statusAfter)
            throws java.sql.SQLException {
        return new PaymentView(
                rs.getObject("id", UUID.class),
                rs.getObject("debt_id", UUID.class),
                DebtType.valueOf(rs.getString("debt_type")),
                rs.getString("party_name"),
                rs.getString("party_phone"),
                Money.of(rs.getBigDecimal("amount")),
                PaymentMethod.valueOf(rs.getString("payment_method")),
                rs.getObject("paid_at", OffsetDateTime.class),
                rs.getString("description"),
                rs.getString("note"),
                rs.getObject("recorded_by", UUID.class),
                rs.getString("recorded_by_name"),
                rs.getObject("created_at", OffsetDateTime.class),
                remainingAfter,
                statusAfter,
                links.url(rs.getObject("id", UUID.class)),
                rs.getObject("voided_at", OffsetDateTime.class),
                rs.getString("void_reason"),
                rs.getObject("edited_at", OffsetDateTime.class));
    }

    // ----- edit / cancel (store owner only) -----

    private record Stored(UUID id, UUID debtId, BigDecimal amount, PaymentMethod method, OffsetDateTime paidAt,
                          String description, String note, OffsetDateTime voidedAt) {}

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    /**
     * Corrects a payment's amount, method, date, description or note. The payment keeps its id (and receipt link).
     * When the amount or method changes, the debt balance moves by the difference and the ledger gets a REVERSAL of the
     * old amount plus a new payment entry; when the money movement changes (amount, method, date or account), the old
     * cash desk row is booked back (PAYMENT_REVERSAL, today) and a new one is posted.
     */
    @Transactional
    public PaymentView update(UUID paymentId, PaymentRequest req) {
        CurrentMember m = requireOwner();
        UUID debtId = debtOf(m, paymentId);
        DebtView debt = debts.lockAndLoad(m, debtId);
        Stored old = lockPayment(m, paymentId);

        BigDecimal amount = Money.of(req.amount());
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Ödəniş məbləği 0-dan böyük olmalıdır");
        }
        BigDecimal max = Money.of(debt.remainingAmount().add(old.amount()));
        if (amount.compareTo(max) > 0) {
            throw ApiException.conflict("PAYMENT_EXCEEDS_BALANCE",
                    "Ödəniş borcun qalığından (" + max + " AZN) böyük ola bilməz");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime paidAt = req.paidAt() != null ? req.paidAt() : old.paidAt();
        if (paidAt.isAfter(now.plusMinutes(5))) {
            throw ApiException.badRequest("FUTURE_PAYMENT", "Ödəniş tarixi gələcəkdə ola bilməz");
        }
        PaymentMethod method = req.paymentMethod();
        String description = blank(req.description());
        String note = blank(req.note());
        LocalDate oldDay = old.paidAt().atZoneSameInstant(BAKU).toLocalDate();
        LocalDate newDay = paidAt.atZoneSameInstant(BAKU).toLocalDate();
        boolean moneyChanged = amount.compareTo(old.amount()) != 0 || method != old.method();
        var live = cash.livePaymentMovement(m.storeId(), paymentId);
        boolean accountChanged = live.isPresent() && req.cashAccountId() != null
                && !req.cashAccountId().equals(live.get().accountId());
        boolean cashChanged = live.isPresent() && (moneyChanged || !oldDay.equals(newDay) || accountChanged);
        boolean changed = moneyChanged || !paidAt.isEqual(old.paidAt()) || accountChanged
                || !Objects.equals(description, old.description()) || !Objects.equals(note, old.note());
        if (!changed) {
            return get(m, paymentId, debt.remainingAmount(), debt.status());
        }

        jdbc.sql("""
                UPDATE payments SET amount = :amount, payment_method = :method, paid_at = :paidAt,
                       description = :description, note = :note, edited_at = now(), edited_by = :by
                WHERE id = :id AND store_id = :storeId
                """)
                .param("amount", amount).param("method", method.name()).param("paidAt", paidAt)
                .param("description", description).param("note", note).param("by", m.userId())
                .param("id", paymentId).param("storeId", m.storeId())
                .update();

        BigDecimal remaining = debt.remainingAmount();
        String status = debt.status();
        LocalDate today = tenant.today();
        if (moneyChanged) {
            BigDecimal restored = Money.of(debt.remainingAmount().add(old.amount()));
            BigDecimal newPaid = Money.of(debt.paidAmount().subtract(old.amount()).add(amount));
            remaining = Money.of(restored.subtract(amount));
            status = DebtService.statusFor(newPaid, remaining);
            setDebtBalance(m, debtId, newPaid, remaining, status);
            ledger.append(m.storeId(), debtId, paymentId, "REVERSAL", old.amount(), restored, m.userId(),
                    today, null, "Ödəniş düzəldildi (köhnə: " + old.amount() + " AZN)");
            ledger.append(m.storeId(), debtId, paymentId, method.ledgerType(), amount, remaining, m.userId(),
                    newDay, null, null);
        }
        if (cashChanged) {
            var mv = live.get();
            cash.reversePaymentMovement(m.storeId(), paymentId, mv, today, "Ödəniş düzəldildi — " + debt.partyName(),
                    m.userId());
            UUID account = req.cashAccountId() != null
                    ? cash.accountFor(m.storeId(), method.name(), req.cashAccountId(), m.userId())
                    : method == old.method() ? mv.accountId() : cash.accountFor(m.storeId(), method.name(), null, m.userId());
            cash.post(m.storeId(), account, mv.direction(), amount, mv.kind(), newDay, paymentId, null,
                    debt.partyName(), m.userId());
        }
        audit.log(m.storeId(), m.userId(), "PAYMENT_EDITED", "PAYMENT", paymentId.toString(),
                changes(old, amount, method, paidAt, description, note, accountChanged) + ", borc " + debtId);
        return get(m, paymentId, remaining, status);
    }

    /**
     * Cancels (ləğv) a payment: it stays in the database marked as cancelled and no longer counts anywhere; the debt
     * grows back by the amount (ledger REVERSAL) and the cash desk row is booked back with today's date.
     */
    @Transactional
    public PaymentView cancel(UUID paymentId, String reason) {
        CurrentMember m = requireOwner();
        UUID debtId = debtOf(m, paymentId);
        DebtView debt = debts.lockAndLoad(m, debtId);
        Stored old = lockPayment(m, paymentId);
        String why = blank(reason);

        jdbc.sql("""
                UPDATE payments SET voided_at = now(), voided_by = :by, void_reason = :reason
                WHERE id = :id AND store_id = :storeId
                """)
                .param("by", m.userId()).param("reason", why).param("id", paymentId).param("storeId", m.storeId())
                .update();
        BigDecimal newPaid = Money.of(debt.paidAmount().subtract(old.amount()));
        BigDecimal remaining = Money.of(debt.remainingAmount().add(old.amount()));
        String status = DebtService.statusFor(newPaid, remaining);
        setDebtBalance(m, debtId, newPaid, remaining, status);
        LocalDate today = tenant.today();
        String ledgerNote = "Ödəniş ləğv edildi" + (why == null ? "" : ": " + why);
        ledger.append(m.storeId(), debtId, paymentId, "REVERSAL", old.amount(), remaining, m.userId(), today, null,
                ledgerNote.length() > 500 ? ledgerNote.substring(0, 500) : ledgerNote);
        cash.livePaymentMovement(m.storeId(), paymentId).ifPresent(mv -> cash.reversePaymentMovement(m.storeId(),
                paymentId, mv, today, "Ödəniş ləğv edildi — " + debt.partyName(), m.userId()));
        audit.log(m.storeId(), m.userId(), "PAYMENT_CANCELLED", "PAYMENT", paymentId.toString(),
                old.method() + " " + old.amount() + " AZN, borc " + debtId + (why == null ? "" : ", səbəb: " + why));
        return get(m, paymentId, remaining, status);
    }

    private CurrentMember requireOwner() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.isOwner()) {
            throw ApiException.forbidden("OWNER_ONLY", "Ödənişi yalnız mağaza sahibi dəyişə və ya ləğv edə bilər");
        }
        return m;
    }

    private UUID debtOf(CurrentMember m, UUID paymentId) {
        return jdbc.sql("SELECT debt_id FROM payments WHERE id = :id AND store_id = :s")
                .param("id", paymentId).param("s", m.storeId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Ödəniş tapılmadı"));
    }

    private Stored lockPayment(CurrentMember m, UUID paymentId) {
        Stored p = jdbc.sql("""
                SELECT id, debt_id, amount, payment_method, paid_at, description, note, voided_at
                FROM payments WHERE id = :id AND store_id = :s FOR UPDATE""")
                .param("id", paymentId).param("s", m.storeId())
                .query((rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getObject("debt_id", UUID.class),
                        Money.of(rs.getBigDecimal("amount")), PaymentMethod.valueOf(rs.getString("payment_method")),
                        rs.getObject("paid_at", OffsetDateTime.class), rs.getString("description"),
                        rs.getString("note"), rs.getObject("voided_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Ödəniş tapılmadı"));
        if (p.voidedAt() != null) {
            throw ApiException.conflict("PAYMENT_CANCELLED", "Bu ödəniş artıq ləğv edilib");
        }
        return p;
    }

    private void setDebtBalance(CurrentMember m, UUID debtId, BigDecimal paid, BigDecimal remaining, String status) {
        jdbc.sql("""
                UPDATE debts SET paid_amount = :paid, remaining_amount = :remaining, status = :status,
                       updated_at = now(), version = version + 1
                WHERE id = :id AND store_id = :storeId
                """)
                .param("paid", paid).param("remaining", remaining).param("status", status)
                .param("id", debtId).param("storeId", m.storeId())
                .update();
    }

    private PaymentView get(CurrentMember m, UUID paymentId, BigDecimal remainingAfter, String statusAfter) {
        return jdbc.sql(PAYMENT_SELECT + " WHERE p.id = :id AND p.store_id = :storeId")
                .param("id", paymentId).param("storeId", m.storeId())
                .query((rs, n) -> mapView(rs, remainingAfter, statusAfter)).single();
    }

    private static String changes(Stored old, BigDecimal amount, PaymentMethod method, OffsetDateTime paidAt,
                                  String description, String note, boolean accountChanged) {
        List<String> out = new java.util.ArrayList<>();
        if (amount.compareTo(old.amount()) != 0) out.add("məbləğ " + old.amount() + "→" + amount);
        if (method != old.method()) out.add("üsul " + old.method() + "→" + method);
        if (!paidAt.isEqual(old.paidAt())) out.add("tarix " + old.paidAt().atZoneSameInstant(BAKU).toLocalDate()
                + "→" + paidAt.atZoneSameInstant(BAKU).toLocalDate());
        if (!Objects.equals(description, old.description())) out.add("təsvir dəyişdi");
        if (!Objects.equals(note, old.note())) out.add("qeyd dəyişdi");
        if (accountChanged) out.add("kassa / hesab dəyişdi");
        return String.join(", ", out);
    }

    /** Everything printed on a payment receipt (çek). */
    public record PaymentReceipt(UUID id, String storeName, String storePhone, String storeAddress, DebtType debtType,
                                 String partyName, String partyPhone, BigDecimal amount, PaymentMethod method,
                                 OffsetDateTime paidAt, String description, String recordedByName,
                                 BigDecimal debtAmount, BigDecimal balanceAfter, LocalDate debtDate,
                                 OffsetDateTime voidedAt) {}

    /** Store-scoped: a payment of another store is reported as not found. */
    public PaymentReceipt receipt(UUID paymentId) {
        CurrentMember m = tenant.requireBusinessMember();
        PaymentReceipt r = loadReceipt(paymentId, m.storeId());
        debts.requireAccess(m, r.debtType());
        return r;
    }

    /** Shareable link of one payment's receipt; same access rules as {@link #receipt(UUID)}. */
    public String shareLink(UUID paymentId) {
        return links.url(receipt(paymentId).id());
    }

    /** Receipt opened from a shared link; the caller has already verified the link's HMAC token. */
    public PaymentReceipt receiptForSharedLink(UUID paymentId) {
        return loadReceipt(paymentId, null);
    }

    private PaymentReceipt loadReceipt(UUID paymentId, UUID storeId) {
        String storeFilter = storeId == null ? "" : " AND p.store_id = :storeId";
        var spec = jdbc.sql("""
                SELECT p.id, s.name AS store_name, s.phone AS store_phone, s.address AS store_address, d.debt_type,
                       COALESCE(c.name, co.name) AS party_name, COALESCE(c.phone, co.phone) AS party_phone,
                       p.amount, p.payment_method, p.paid_at, p.description, u.full_name AS recorded_by,
                       d.amount AS debt_amount, d.debt_date, p.voided_at,
                       (SELECT t.balance_after FROM financial_transactions t WHERE t.payment_id = p.id
                          AND t.transaction_type LIKE 'PAYMENT%' ORDER BY t.created_at DESC, t.id LIMIT 1) AS balance_after
                FROM payments p
                JOIN debts d ON d.id = p.debt_id AND d.store_id = p.store_id
                JOIN stores s ON s.id = p.store_id
                JOIN users u ON u.id = p.recorded_by
                LEFT JOIN customers c ON c.id = d.customer_id
                LEFT JOIN companies co ON co.id = d.company_id
                WHERE p.id = :id""" + storeFilter).param("id", paymentId);
        if (storeId != null) {
            spec = spec.param("storeId", storeId);
        }
        return spec.query((rs, n) -> new PaymentReceipt(
                        rs.getObject("id", UUID.class), rs.getString("store_name"), rs.getString("store_phone"),
                        rs.getString("store_address"), DebtType.valueOf(rs.getString("debt_type")),
                        rs.getString("party_name"), rs.getString("party_phone"), Money.of(rs.getBigDecimal("amount")),
                        PaymentMethod.valueOf(rs.getString("payment_method")),
                        rs.getObject("paid_at", OffsetDateTime.class), rs.getString("description"),
                        rs.getString("recorded_by"), Money.of(rs.getBigDecimal("debt_amount")),
                        Money.of(rs.getBigDecimal("balance_after")), rs.getObject("debt_date", LocalDate.class),
                        rs.getObject("voided_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Ödəniş tapılmadı"));
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
