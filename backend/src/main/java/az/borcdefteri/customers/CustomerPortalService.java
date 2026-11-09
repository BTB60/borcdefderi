package az.borcdefteri.customers;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.AppProperties;
import az.borcdefteri.common.Money;
import az.borcdefteri.customers.PartyCardService.HistoryEntry;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * "Müştəri kabineti": a read-only page a customer opens from a link. The link carries a 256-bit random token;
 * only its SHA-256 is stored, so a database leak does not reveal working links. A link shows exactly one
 * customer of one store, can expire and can be revoked at any time.
 */
@Service
public class CustomerPortalService {

    public record LinkView(UUID id, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt,
                           OffsetDateTime lastUsedAt, String createdByName, boolean active) {}

    /** Returned once at creation: the only time the full link is known. */
    public record CreatedLink(UUID id, String url, OffsetDateTime expiresAt) {}

    public record CreateRequest(
            @jakarta.validation.constraints.Min(value = 1, message = "Müddət ən az 1 gün olmalıdır")
            @jakarta.validation.constraints.Max(value = 365, message = "Müddət ən çox 365 gün ola bilər") Integer days) {}

    public record PortalDebt(LocalDate debtDate, LocalDate dueDate, BigDecimal amount, BigDecimal paid,
                             BigDecimal remaining, String status, long overdueDays) {}

    public record PortalEntry(LocalDate date, String type, BigDecimal amount, BigDecimal balanceAfter,
                              String paymentMethod, String receiptNumber, String note) {}

    public record PortalView(String storeName, String storePhone, String storeAddress, String customerName,
                             String customerCode, BigDecimal totalDebt, BigDecimal paid, BigDecimal remaining,
                             List<PortalDebt> debts, List<PortalEntry> history, OffsetDateTime expiresAt,
                             OffsetDateTime generatedAt) {}

    /** Resolved link: which store and customer it shows. */
    public record Resolved(UUID storeId, UUID customerId, OffsetDateTime expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PartyService parties;
    private final PartyCardService cards;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;

    public CustomerPortalService(JdbcClient jdbc, TenantService tenant, PartyService parties, PartyCardService cards,
                                 AuditService audit, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.parties = parties;
        this.cards = cards;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public CreatedLink create(UUID customerId, CreateRequest req) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        parties.customer(m, customerId);
        int days = req == null || req.days() == null ? 30 : req.days();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expires = OffsetDateTime.now(clock).plusDays(days);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_portal_tokens (id, store_id, customer_id, token_hash, expires_at, created_by)
                VALUES (:id, :s, :c, :h, :e, :by)
                """).param("id", id).param("s", m.storeId()).param("c", customerId).param("h", hash(token))
                .param("e", expires).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "PORTAL_LINK_CREATED", "CUSTOMER", customerId.toString(), days + " gün");
        return new CreatedLink(id, webUrl() + "/kabinet/" + token, expires);
    }

    public List<LinkView> links(UUID customerId) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        parties.customer(m, customerId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return jdbc.sql("""
                SELECT t.id, t.created_at, t.expires_at, t.revoked_at, t.last_used_at, u.full_name
                FROM customer_portal_tokens t JOIN users u ON u.id = t.created_by
                WHERE t.store_id = :s AND t.customer_id = :c ORDER BY t.created_at DESC LIMIT 50
                """).param("s", m.storeId()).param("c", customerId)
                .query((rs, n) -> {
                    OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                    OffsetDateTime rev = rs.getObject("revoked_at", OffsetDateTime.class);
                    return new LinkView(rs.getObject("id", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                            exp, rev, rs.getObject("last_used_at", OffsetDateTime.class), rs.getString("full_name"),
                            rev == null && (exp == null || exp.isAfter(now)));
                }).list();
    }

    @Transactional
    public void revoke(UUID customerId, UUID linkId) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        parties.customer(m, customerId);
        int n = jdbc.sql("""
                UPDATE customer_portal_tokens SET revoked_at = now()
                WHERE id = :id AND store_id = :s AND customer_id = :c AND revoked_at IS NULL
                """).param("id", linkId).param("s", m.storeId()).param("c", customerId).update();
        if (n == 0) {
            throw ApiException.notFound("Link tapılmadı və ya artıq ləğv edilib");
        }
        audit.log(m.storeId(), m.userId(), "PORTAL_LINK_REVOKED", "CUSTOMER", customerId.toString(), linkId.toString());
    }

    /** Public: any invalid, expired or revoked token is the same "not found" (no hint which one it was). */
    @Transactional
    public Resolved resolve(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw notFound();
        }
        record Row(UUID id, UUID storeId, UUID customerId, OffsetDateTime expires, OffsetDateTime revoked, String storeStatus) {}
        Row row = jdbc.sql("""
                SELECT t.id, t.store_id, t.customer_id, t.expires_at, t.revoked_at, s.status
                FROM customer_portal_tokens t JOIN stores s ON s.id = t.store_id WHERE t.token_hash = :h
                """).param("h", hash(token))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("status")))
                .optional().orElseThrow(CustomerPortalService::notFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (row.revoked() != null || (row.expires() != null && !row.expires().isAfter(now)) || !"ACTIVE".equals(row.storeStatus())) {
            throw notFound();
        }
        jdbc.sql("UPDATE customer_portal_tokens SET last_used_at = now() WHERE id = :id").param("id", row.id()).update();
        return new Resolved(row.storeId(), row.customerId(), row.expires());
    }

    public PortalView view(String token) {
        Resolved r = resolve(token);
        record Head(String storeName, String storePhone, String storeAddress, String name, String code) {}
        Head h = jdbc.sql("""
                SELECT s.name AS store_name, s.phone AS store_phone, s.address AS store_address, c.name, c.code
                FROM customers c JOIN stores s ON s.id = c.store_id WHERE c.id = :c AND c.store_id = :s
                """).param("c", r.customerId()).param("s", r.storeId())
                .query((rs, n) -> new Head(rs.getString("store_name"), rs.getString("store_phone"),
                        rs.getString("store_address"), rs.getString("name"), rs.getString("code")))
                .optional().orElseThrow(CustomerPortalService::notFound);
        LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Baku")));
        List<PortalDebt> debts = jdbc.sql("""
                SELECT debt_date, due_date, amount, paid_amount, remaining_amount, status FROM debts
                WHERE store_id = :s AND customer_id = :c AND debt_type = 'CUSTOMER_DEBT' ORDER BY debt_date DESC, created_at DESC
                """).param("s", r.storeId()).param("c", r.customerId())
                .query((rs, n) -> {
                    LocalDate due = rs.getObject("due_date", LocalDate.class);
                    BigDecimal rem = Money.of(rs.getBigDecimal("remaining_amount"));
                    long overdue = due != null && rem.signum() > 0 && due.isBefore(today)
                            ? java.time.temporal.ChronoUnit.DAYS.between(due, today) : 0;
                    return new PortalDebt(rs.getObject("debt_date", LocalDate.class), due,
                            Money.of(rs.getBigDecimal("amount")), Money.of(rs.getBigDecimal("paid_amount")), rem,
                            rs.getString("status"), overdue);
                }).list();
        BigDecimal total = Money.zero(), paid = Money.zero(), rem = Money.zero();
        for (PortalDebt d : debts) {
            total = total.add(d.amount());
            paid = paid.add(d.paid());
            rem = rem.add(d.remaining());
        }
        List<HistoryEntry> hist = cards.history(r.storeId(), true, r.customerId(), List.of("CUSTOMER_DEBT"));
        List<PortalEntry> entries = new java.util.ArrayList<>(hist.size());
        for (int i = hist.size() - 1; i >= 0; i--) {   // newest first
            HistoryEntry e = hist.get(i);
            entries.add(new PortalEntry(e.entryDate(), e.type(), e.amount(), e.balanceAfter(), e.paymentMethod(),
                    e.receiptNumber(), e.note()));
        }
        return new PortalView(h.storeName(), h.storePhone(), h.storeAddress(), h.name(), h.code(), Money.of(total),
                Money.of(paid), Money.of(rem), debts, entries, r.expiresAt(), OffsetDateTime.now(clock));
    }

    private String webUrl() {
        String url = props.passwordReset() == null ? null : props.passwordReset().webUrl();
        if (url == null || url.isBlank()) {
            url = "http://localhost:3000";
        }
        return url.replaceAll("/+$", "");
    }

    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("Link etibarsızdır, vaxtı bitib və ya ləğv edilib");
    }
}
