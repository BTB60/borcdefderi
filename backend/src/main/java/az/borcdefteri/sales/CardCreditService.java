package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.subscriptions.ReceiptStorage;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pullu bonus kartları: the store's card balance. The owner buys a package from the platform admin (payment request
 * with an optional receipt); the admin approves it and the cards are added. Each new bonus card uses one card.
 */
@Service
@RestController
public class CardCreditService {

    public record Request(UUID id, UUID storeId, String storeName, int quantity, BigDecimal amount, String status,
                          String paymentMethod, String payerReference, String note, boolean hasReceipt, String rejectReason,
                          OffsetDateTime requestedAt, OffsetDateTime reviewedAt) {}

    public record Balance(int credits, BigDecimal pricePerCard, List<Integer> packages, int minQuantity, Request pending,
                          List<Request> history) {}

    public record Review(String reason) {}

    public record Grant(int quantity, String note) {}

    static final List<Integer> PACKAGES = List.of(20, 50, 100, 200, 500);
    static final int MIN_QUANTITY = 10, MAX_QUANTITY = 10_000;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final ReceiptStorage receipts;
    private final AuditService audit;
    /** The default when the admin has not set a price (app.cards.price-per-card). */
    private final BigDecimal defaultPrice;

    public CardCreditService(JdbcClient jdbc, TenantService tenant, ReceiptStorage receipts, AuditService audit,
                             @Value("${app.cards.price-per-card:0.85}") BigDecimal price) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.receipts = receipts;
        this.audit = audit;
        this.defaultPrice = Money.of(price);
    }

    // ---------- the store ----------

    @GetMapping("/api/card-credits")
    public Balance balance() {
        CurrentMember m = tenant.requireBusinessMember();
        List<Request> history = jdbc.sql(SELECT + " WHERE r.store_id = :s ORDER BY r.requested_at DESC LIMIT 30")
                .param("s", m.storeId()).query((rs, n) -> map(rs)).list();
        Request pending = history.stream().filter(r -> "PENDING".equals(r.status())).findFirst().orElse(null);
        return new Balance(credits(m.storeId()), price(), PACKAGES, MIN_QUANTITY, pending, history);
    }

    /** "Ödəniş etdim": a new PENDING request, or the store's pending one updated (a new receipt replaces the old one). */
    @PostMapping(value = "/api/card-credits/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Request request(@RequestParam int quantity, @RequestParam(required = false) String paymentMethod,
                          @RequestParam(required = false) String payerReference, @RequestParam(required = false) String note,
                          @RequestParam(required = false) MultipartFile receipt) {
        CurrentMember m = tenant.requireOwner();
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw ApiException.badRequest("INVALID_QUANTITY", "Kart sayı " + MIN_QUANTITY + "–" + MAX_QUANTITY + " arası olmalıdır");
        }
        String method = paymentMethod == null || paymentMethod.isBlank() ? null : paymentMethod.trim().toUpperCase(Locale.ROOT);
        if (method != null && !method.equals("M10") && !method.equals("CARD")) {
            throw ApiException.badRequest("INVALID_METHOD", "Ödəniş üsulu M10 və ya bank kartı olmalıdır");
        }
        String ref = cut(payerReference, 120), text = cut(note, 500);
        ReceiptStorage.Stored stored = receipt == null || receipt.isEmpty() ? null : receipts.store(receipt);
        BigDecimal amount = Money.of(price().multiply(BigDecimal.valueOf(quantity)));
        jdbc.sql("SELECT id FROM stores WHERE id = :s FOR UPDATE").param("s", m.storeId()).query(UUID.class).single();
        UUID id = jdbc.sql("SELECT id FROM card_credit_requests WHERE store_id = :s AND status = 'PENDING' ORDER BY requested_at DESC LIMIT 1")
                .param("s", m.storeId()).query(UUID.class).optional().orElse(null);
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO card_credit_requests (id, store_id, quantity, amount, status, requested_by)
                    VALUES (:id, :s, :q, :a, 'PENDING', :by)""")
                    .param("id", id).param("s", m.storeId()).param("q", quantity).param("a", amount).param("by", m.userId()).update();
        }
        jdbc.sql("""
                UPDATE card_credit_requests SET quantity = :q, amount = :a, requested_at = now(),
                       payment_method = COALESCE(:method, payment_method), payer_reference = COALESCE(:ref, payer_reference),
                       note = COALESCE(:note, note)
                WHERE id = :id""")
                .param("q", quantity).param("a", amount).param("method", method).param("ref", ref).param("note", text)
                .param("id", id).update();
        if (stored != null) {
            jdbc.sql("UPDATE card_credit_requests SET receipt = :c, receipt_type = :t, receipt_name = :n WHERE id = :id")
                    .param("c", stored.content()).param("t", stored.contentType()).param("n", stored.originalName()).param("id", id).update();
        }
        audit.log(m.storeId(), m.userId(), "CARD_CREDITS_REQUESTED", "CARD_CREDITS", id.toString(),
                quantity + " kart, " + amount + " AZN" + (method != null ? ", " + method : "") + (stored != null ? ", qəbz yükləndi" : ""));
        return get(id);
    }

    @GetMapping("/api/card-credits/requests/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        boolean admin = tenant.isPlatformAdmin();
        UUID store = admin ? null : tenant.requireMember().storeId();
        record File(byte[] content, String type, String name) {}
        File f = jdbc.sql("SELECT receipt, receipt_type, receipt_name FROM card_credit_requests WHERE id = :id AND receipt IS NOT NULL"
                        + (admin ? "" : " AND store_id = :s"))
                .param("id", id).params(admin ? java.util.Map.of() : java.util.Map.of("s", store))
                .query((rs, n) -> new File(rs.getBytes("receipt"), rs.getString("receipt_type"), rs.getString("receipt_name")))
                .optional().orElseThrow(() -> ApiException.notFound("Qəbz tapılmadı"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.type()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"qebz\"").header("X-Content-Type-Options", "nosniff")
                .body(f.content());
    }

    /** A new bonus card uses one card from the balance; none left = the card is not made. */
    public void useOne(UUID storeId) {
        int updated = jdbc.sql("UPDATE stores SET card_credits = card_credits - 1 WHERE id = :s AND card_credits > 0")
                .param("s", storeId).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "NO_CARD_CREDITS",
                    "Bonus kart balansınız bitib. “Kart paketi al” ilə kart alın (1 kart = " + price().toPlainString().replace('.', ',') + " ₼)");
        }
    }

    // ---------- the platform admin ----------

    @GetMapping("/api/admin/card-credit-requests")
    public List<Request> adminList(@RequestParam(defaultValue = "PENDING") String status) {
        tenant.requirePlatformAdmin();
        String s = status.toUpperCase(Locale.ROOT);
        String where = "ALL".equals(s) ? "" : " WHERE r.status = :st";
        var spec = jdbc.sql(SELECT + where + " ORDER BY r.requested_at DESC LIMIT 200");
        if (!"ALL".equals(s)) spec = spec.param("st", s);
        return spec.query((rs, n) -> map(rs)).list();
    }

    @PostMapping("/api/admin/card-credit-requests/{id}/approve")
    @Transactional
    public Request approve(@PathVariable UUID id) {
        tenant.requirePlatformAdmin();
        Request r = get(id);
        if (!"PENDING".equals(r.status())) throw ApiException.conflict("NOT_PENDING", "Sorğu artıq baxılıb");
        jdbc.sql("UPDATE card_credit_requests SET status = 'APPROVED', reviewed_by = :by, reviewed_at = now() WHERE id = :id AND status = 'PENDING'")
                .param("by", tenant.currentUserId()).param("id", id).update();
        jdbc.sql("UPDATE stores SET card_credits = card_credits + :q WHERE id = :s").param("q", r.quantity()).param("s", r.storeId()).update();
        audit.log(r.storeId(), tenant.currentUserId(), "CARD_CREDITS_APPROVED", "CARD_CREDITS", id.toString(),
                r.quantity() + " kart, " + r.amount() + " AZN");
        return get(id);
    }

    @PostMapping("/api/admin/card-credit-requests/{id}/reject")
    @Transactional
    public Request reject(@PathVariable UUID id, @RequestBody(required = false) Review body) {
        tenant.requirePlatformAdmin();
        Request r = get(id);
        if (!"PENDING".equals(r.status())) throw ApiException.conflict("NOT_PENDING", "Sorğu artıq baxılıb");
        String reason = body == null ? null : cut(body.reason(), 300);
        jdbc.sql("""
                UPDATE card_credit_requests SET status = 'REJECTED', reject_reason = :r, reviewed_by = :by, reviewed_at = now()
                WHERE id = :id AND status = 'PENDING'""")
                .param("r", reason).param("by", tenant.currentUserId()).param("id", id).update();
        audit.log(r.storeId(), tenant.currentUserId(), "CARD_CREDITS_REJECTED", "CARD_CREDITS", id.toString(), reason == null ? "" : reason);
        return get(id);
    }

    /** The admin adds cards by hand (paid by phone, a gift); the store's history shows it as "Admin əlavə etdi". */
    @PostMapping("/api/admin/stores/{storeId}/card-credits")
    @Transactional
    public Request grant(@PathVariable UUID storeId, @RequestBody Grant body) {
        tenant.requirePlatformAdmin();
        if (body == null || body.quantity() <= 0 || body.quantity() > MAX_QUANTITY) {
            throw ApiException.badRequest("INVALID_QUANTITY", "Kart sayı düzgün deyil");
        }
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM stores WHERE id = :s").param("s", storeId).query(Long.class).single() > 0;
        if (!exists) throw ApiException.notFound("Mağaza tapılmadı");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO card_credit_requests (id, store_id, quantity, amount, status, note, reviewed_by, reviewed_at)
                VALUES (:id, :s, :q, :a, 'GRANT', :n, :by, now())""")
                .param("id", id).param("s", storeId).param("q", body.quantity())
                .param("a", Money.of(price().multiply(BigDecimal.valueOf(body.quantity()))))
                .param("n", cut(body.note(), 500)).param("by", tenant.currentUserId()).update();
        jdbc.sql("UPDATE stores SET card_credits = card_credits + :q WHERE id = :s").param("q", body.quantity()).param("s", storeId).update();
        audit.log(storeId, tenant.currentUserId(), "CARD_CREDITS_GRANTED", "CARD_CREDITS", id.toString(), body.quantity() + " kart");
        return get(id);
    }

    // ---------- the price of one card (the platform admin sets it) ----------

    public record Price(BigDecimal price) {}

    /** One card's price now: the admin's setting, or the default. Requests already sent keep their amount. */
    public BigDecimal price() {
        return jdbc.sql("SELECT value FROM platform_settings WHERE key = 'card_price'").query(String.class).optional()
                .flatMap(v -> {
                    try {
                        return java.util.Optional.of(Money.of(new BigDecimal(v.trim())));
                    } catch (RuntimeException e) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(defaultPrice);
    }

    @GetMapping("/api/admin/card-credits/price")
    public Price adminPrice() {
        tenant.requirePlatformAdmin();
        return new Price(price());
    }

    @PutMapping("/api/admin/card-credits/price")
    @Transactional
    public Price setPrice(@RequestBody Price body) {
        tenant.requireSuperAdmin();
        if (body == null || body.price() == null || body.price().signum() <= 0 || body.price().compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.badRequest("INVALID_PRICE", "Kartın qiyməti 0-dan böyük, 100 ₼-dan az olmalıdır");
        }
        BigDecimal next = Money.of(body.price()), before = price();
        jdbc.sql("""
                INSERT INTO platform_settings (key, value, updated_at, updated_by) VALUES ('card_price', :v, now(), :by)
                ON CONFLICT (key) DO UPDATE SET value = :v, updated_at = now(), updated_by = :by""")
                .param("v", next.toPlainString()).param("by", tenant.currentUserId()).update();
        audit.log(null, tenant.currentUserId(), "CARD_PRICE_CHANGED", "CARD_CREDITS", null, before + " → " + next + " AZN");
        return new Price(next);
    }

    // ---------- helpers ----------

    private int credits(UUID storeId) {
        return jdbc.sql("SELECT card_credits FROM stores WHERE id = :s").param("s", storeId).query(Integer.class).single();
    }

    private Request get(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Sorğu tapılmadı"));
    }

    private static String cut(String v, int max) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static final String SELECT = """
            SELECT r.id, r.store_id, st.name AS store_name, r.quantity, r.amount, r.status, r.payment_method, r.payer_reference,
                   r.note, r.receipt IS NOT NULL AS has_receipt, r.reject_reason, r.requested_at, r.reviewed_at
            FROM card_credit_requests r JOIN stores st ON st.id = r.store_id""";

    private static Request map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Request(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class), rs.getString("store_name"),
                rs.getInt("quantity"), Money.of(rs.getBigDecimal("amount")), rs.getString("status"), rs.getString("payment_method"),
                rs.getString("payer_reference"), rs.getString("note"), rs.getBoolean("has_receipt"), rs.getString("reject_reason"),
                rs.getObject("requested_at", OffsetDateTime.class), rs.getObject("reviewed_at", OffsetDateTime.class));
    }
}
