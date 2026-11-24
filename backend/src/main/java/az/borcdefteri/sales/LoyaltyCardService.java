package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.common.Search;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bonus kartları. A card belongs to one customer and has its own bonus percent: sales to that customer earn bonus at
 * the card's percent instead of the store's. Registering a card makes the customer when there is none with that
 * phone yet. The card number is the customer's code, so the till scans it like before.
 */
@Service
@RestController
@RequestMapping("/api/loyalty-cards")
public class LoyaltyCardService {

    public record CardRequest(
            @NotBlank(message = "Adı daxil edin") @Size(max = 60) String firstName,
            @NotBlank(message = "Soyadı daxil edin") @Size(max = 60) String lastName,
            @Size(max = 60) String patronymic,
            @NotBlank(message = "Telefon nömrəsini daxil edin")
            @Pattern(regexp = "^[+0-9 ()-]{7,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
            @NotNull(message = "Kartın faizini daxil edin") @DecimalMin(value = "0", message = "Faiz 0–50 arası olmalıdır")
            @DecimalMax(value = "50", message = "Faiz 0–50 arası olmalıdır") @Digits(integer = 2, fraction = 2) BigDecimal percent,
            /** Update only: false blocks the card (no bonus is earned or spent). */
            Boolean active,
            /** Create only: false skips the SMS with the card number and the app's activation code (default: sent). */
            Boolean sendSms) {}

    public record Card(UUID id, UUID customerId, String number, String firstName, String lastName, String patronymic,
                       String fullName, String phone, BigDecimal percent, boolean active, BigDecimal balance,
                       BigDecimal earned, BigDecimal spent, OffsetDateTime lastSaleAt, String activationCode, OffsetDateTime createdAt) {}

    public record Entry(UUID id, String kind, BigDecimal amount, String saleNumber, String note, OffsetDateTime createdAt) {}

    public record CardDetail(Card card, List<Entry> entries) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final az.borcdefteri.subscriptions.PlanService plans;
    private final az.borcdefteri.notifications.SmsService sms;
    private final CardCreditService credits;

    /** Short link to the customers' app (web redirect to the kart-latest APK). */
    static final String APP_LINK = "hesabpro.cloud/kart";

    public LoyaltyCardService(JdbcClient jdbc, TenantService tenant, AuditService audit, az.borcdefteri.subscriptions.PlanService plans,
                              az.borcdefteri.notifications.SmsService sms, CardCreditService credits) {
        this.credits = credits;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.plans = plans;
        this.sms = sms;
    }

    private CurrentMember requireAccess() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.has("SALES") && !m.has(TenantService.PERM_CUSTOMER_DEBTS)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return m;
    }

    @GetMapping
    public List<Card> list(@RequestParam(required = false) String q) {
        CurrentMember m = requireAccess();
        String where = " WHERE lc.store_id = :s";
        if (q != null && !q.isBlank()) {
            where += " AND (search_norm(lc.first_name || ' ' || lc.last_name || ' ' || COALESCE(lc.patronymic, '')) LIKE search_norm(:q)"
                    + " OR regexp_replace(lc.phone, '[^0-9]', '', 'g') LIKE :digits OR LOWER(c.code) = LOWER(:exact))";
        }
        var spec = jdbc.sql(SELECT + where + " ORDER BY lc.created_at DESC LIMIT 500").param("s", m.storeId());
        if (q != null && !q.isBlank()) {
            String digits = q.replaceAll("[^0-9]", "");
            // no digits in the search: the phone condition must match nothing (phones are digits only here)
            spec = spec.param("q", Search.contains(q)).param("digits", digits.isEmpty() ? "x" : "%" + digits + "%").param("exact", q.trim());
        }
        return spec.query((rs, n) -> map(rs)).list();
    }

    @GetMapping("/{id}")
    public CardDetail get(@PathVariable UUID id) {
        CurrentMember m = requireAccess();
        Card c = load(m.storeId(), id);
        List<Entry> entries = jdbc.sql("""
                SELECT e.id, e.kind, e.amount, s.number, e.note, e.created_at FROM customer_bonus_entries e
                LEFT JOIN sales s ON s.id = e.sale_id WHERE e.customer_id = :c ORDER BY e.created_at DESC LIMIT 300""")
                .param("c", c.customerId())
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("kind"), Money.of(rs.getBigDecimal("amount")),
                        rs.getString("number"), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class))).list();
        return new CardDetail(c, entries);
    }

    /**
     * Registers a card. A customer with the same phone gets the card (one card per customer); otherwise the customer
     * is made from the card's name and phone.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Card create(@Valid @RequestBody CardRequest req) {
        CurrentMember m = requireAccess();
        String digits = req.phone().replaceAll("[^0-9]", "");
        String last9 = digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
        UUID customer = jdbc.sql("""
                SELECT id FROM customers WHERE store_id = :s AND phone IS NOT NULL
                  AND right(regexp_replace(phone, '[^0-9]', '', 'g'), 9) = :p ORDER BY created_at LIMIT 1""")
                .param("s", m.storeId()).param("p", last9).query(UUID.class).optional().orElse(null);
        String fullName = fullName(req);
        if (customer == null) {
            Integer max = plans.limitsFor(m.storeId()).maxCustomers();
            if (max != null && jdbc.sql("SELECT COUNT(*) FROM customers WHERE store_id = :s").param("s", m.storeId())
                    .query(Long.class).single() >= max) {
                throw ApiException.conflict("PLAN_LIMIT_CUSTOMERS", "Paketinizdə ən çox " + max + " müştəri ola bilər. Daha çox müştəri üçün paketi yüksəldin.");
            }
            customer = UUID.randomUUID();
            jdbc.sql("INSERT INTO customers (id, store_id, name, phone, created_by) VALUES (:id, :s, :n, :p, :by)")
                    .param("id", customer).param("s", m.storeId()).param("n", fullName).param("p", req.phone().trim())
                    .param("by", m.userId()).update();
        } else if (jdbc.sql("SELECT COUNT(*) FROM loyalty_cards WHERE customer_id = :c").param("c", customer).query(Long.class).single() > 0) {
            throw ApiException.conflict("CARD_EXISTS", "Bu telefon nömrəsi ilə artıq bonus kartı var");
        }
        credits.useOne(m.storeId()); // a paid card: one from the store's card balance (rolled back with the card)
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO loyalty_cards (id, store_id, customer_id, first_name, last_name, patronymic, phone, percent, activation_code, created_by)
                VALUES (:id, :s, :c, :f, :l, :p, :ph, :pct, :code, :by)""")
                .param("id", id).param("s", m.storeId()).param("c", customer).param("f", req.firstName().trim())
                .param("l", req.lastName().trim()).param("p", blank(req.patronymic())).param("ph", req.phone().trim())
                .param("pct", req.percent()).param("code", code()).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "LOYALTY_CARD_CREATED", "CUSTOMER", customer.toString(), fullName + ", " + req.percent() + "%");
        Card card = load(m.storeId(), id);
        if (!Boolean.FALSE.equals(req.sendSms())) sendCardSms(m.storeId(), card);
        return card;
    }

    // ---------- "Kart yoxdur": the till finds the card by the customer's phone ----------

    public record PhoneMatch(UUID cardId, UUID customerId, String number, String fullName, String phone, BigDecimal percent,
                             BigDecimal balance, boolean active, boolean smsOn) {}

    /** The store's card(s) for a phone number (matched on its last 9 digits, however it was typed). */
    @GetMapping("/lookup")
    public List<PhoneMatch> lookup(@RequestParam String phone) {
        CurrentMember m = requireAccess();
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (digits.length() < 9) throw ApiException.badRequest("INVALID_PHONE", "Telefon nömrəsini tam yazın");
        String last9 = digits.substring(digits.length() - 9);
        boolean smsOn = sms.configured();
        List<PhoneMatch> found = jdbc.sql(SELECT + " WHERE lc.store_id = :s AND right(regexp_replace(lc.phone, '[^0-9]', '', 'g'), 9) = :p ORDER BY lc.active DESC, lc.created_at DESC")
                .param("s", m.storeId()).param("p", last9).query((rs, n) -> map(rs)).list().stream()
                .map(c -> new PhoneMatch(c.id(), c.customerId(), c.number(), c.fullName(), c.phone(), c.percent(), c.balance(), c.active(), smsOn))
                .toList();
        audit.log(m.storeId(), m.userId(), "BONUS_CARD_BY_PHONE", "CUSTOMER", found.isEmpty() ? null : found.get(0).customerId().toString(),
                "Kart telefonla axtarıldı: ***" + last9.substring(5) + (found.isEmpty() ? " (tapılmadı)" : " → " + found.get(0).number()));
        return found;
    }

    /** A 4-digit code to the card's phone: the customer reads it out before paying with the bonus. */
    @PostMapping("/{id}/bonus-code")
    @Transactional
    public Map<String, Object> sendBonusCode(@PathVariable UUID id) {
        CurrentMember m = requireAccess();
        Card card = load(m.storeId(), id);
        if (!card.active()) throw ApiException.conflict("CARD_BLOCKED", "Kart bloklanıb");
        if (!sms.configured()) {
            audit.log(m.storeId(), m.userId(), "BONUS_CODE_SKIPPED", "CUSTOMER", card.customerId().toString(),
                    card.number() + ": SMS qoşulmayıb, bonus kodsuz açıldı");
            return Map.of("sent", false, "smsOn", false);
        }
        long recent = jdbc.sql("SELECT COUNT(*) FROM bonus_spend_codes WHERE card_id = :c AND created_at > now() - interval '10 minutes'")
                .param("c", id).query(Long.class).single();
        if (recent >= 3) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES", "Çox kod istənildi. 10 dəqiqə sonra yenidən cəhd edin");
        String code = String.format("%04d", new SecureRandom().nextInt(10_000));
        jdbc.sql("""
                INSERT INTO bonus_spend_codes (id, store_id, card_id, code_hash, expires_at, created_by)
                VALUES (:id, :s, :c, :h, now() + interval '10 minutes', :by)""")
                .param("id", UUID.randomUUID()).param("s", m.storeId()).param("c", id).param("h", hash(id, code)).param("by", m.userId()).update();
        String store = jdbc.sql("SELECT name FROM stores WHERE id = :s").param("s", m.storeId()).query(String.class).optional().orElse("");
        boolean sent = sms.send(m.storeId(), card.phone(), (store.isBlank() ? "" : store + ": ") + "bonusla odenis kodu " + code
                + ". Kodu yalniz kassada kassire deyin.", "BONUS_CODE", id);
        if (!sent) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_FAILED", "SMS göndərilmədi. Bonussuz davam edin və ya bir az sonra yenidən cəhd edin");
        return Map.of("sent", true, "smsOn", true, "phone", "***" + card.phone().replaceAll("[^0-9]", "").replaceAll(".*(\\d{4})$", "$1"));
    }

    public record CodeCheck(String code) {}

    @PostMapping("/{id}/bonus-code/verify")
    @Transactional
    public Map<String, Object> verifyBonusCode(@PathVariable UUID id, @RequestBody CodeCheck body) {
        CurrentMember m = requireAccess();
        Card card = load(m.storeId(), id);
        String code = body == null || body.code() == null ? "" : body.code().replaceAll("\\D", "");
        record Row(UUID id, String hash, int attempts) {}
        Row row = jdbc.sql("""
                SELECT id, code_hash, attempts FROM bonus_spend_codes
                WHERE card_id = :c AND store_id = :s AND NOT used AND expires_at > now() ORDER BY created_at DESC LIMIT 1 FOR UPDATE""")
                .param("c", id).param("s", m.storeId())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("code_hash"), rs.getInt("attempts"))).optional()
                .orElseThrow(() -> ApiException.badRequest("CODE_EXPIRED", "Kodun vaxtı bitib. Yeni kod göndərin"));
        if (row.attempts() >= 5) throw ApiException.badRequest("CODE_EXPIRED", "Çox səhv cəhd. Yeni kod göndərin");
        if (!java.security.MessageDigest.isEqual(row.hash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                hash(id, code).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            jdbc.sql("UPDATE bonus_spend_codes SET attempts = attempts + 1 WHERE id = :id").param("id", row.id()).update();
            throw ApiException.badRequest("WRONG_CODE", "Kod səhvdir");
        }
        jdbc.sql("UPDATE bonus_spend_codes SET used = TRUE WHERE id = :id").param("id", row.id()).update();
        audit.log(m.storeId(), m.userId(), "BONUS_CODE_OK", "CUSTOMER", card.customerId().toString(), card.number() + ": SMS kodu təsdiqləndi");
        return Map.of("ok", true);
    }

    private static String hash(UUID cardId, String code) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((cardId + ":" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends the card's number and the app's activation code to the customer's phone again. */
    @PostMapping("/{id}/sms")
    public Map<String, Object> resendSms(@PathVariable UUID id) {
        CurrentMember m = requireAccess();
        if (!sms.configured()) {
            throw ApiException.conflict("SMS_OFF", "SMS göndərmə hələ qoşulmayıb. Kodu WhatsApp ilə göndərin.");
        }
        Card card = load(m.storeId(), id);
        if (!card.active()) throw ApiException.conflict("CARD_BLOCKED", "Kart bloklanıb");
        boolean sent = sendCardSms(m.storeId(), card);
        if (!sent) {
            String why = jdbc.sql("SELECT error FROM sms_messages WHERE store_id = :s AND ref_id = :id ORDER BY created_at DESC LIMIT 1")
                    .param("s", m.storeId()).param("id", id).query(String.class).optional().orElse(null);
            throw ApiException.conflict("SMS_FAILED", "SMS göndərilmədi" + (why == null ? "" : ": " + why));
        }
        return Map.of("sent", true, "phone", card.phone());
    }

    /** The card SMS: store, card number, the app's activation code and the app link. Never fails the caller. */
    private boolean sendCardSms(UUID storeId, Card c) {
        if (!sms.configured()) return false;
        String store = jdbc.sql("SELECT name FROM stores WHERE id = :s").param("s", storeId).query(String.class).optional().orElse("");
        String text = (store.isBlank() ? "" : store + ": ") + "bonus kartiniz " + c.number()
                + ". HesabPro Kart tetbiqine giris kodu: " + c.activationCode() + ". Tetbiq: " + APP_LINK;
        try {
            return sms.send(storeId, c.phone(), text, "LOYALTY_CARD", c.id());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public Card update(@PathVariable UUID id, @Valid @RequestBody CardRequest req) {
        CurrentMember m = requireAccess();
        Card cur = load(m.storeId(), id);
        jdbc.sql("""
                UPDATE loyalty_cards SET first_name = :f, last_name = :l, patronymic = :p, phone = :ph, percent = :pct, active = :a
                WHERE id = :id""").param("f", req.firstName().trim()).param("l", req.lastName().trim()).param("p", blank(req.patronymic()))
                .param("ph", req.phone().trim()).param("pct", req.percent()).param("a", req.active() == null ? cur.active() : req.active())
                .param("id", id).update();
        audit.log(m.storeId(), m.userId(), "LOYALTY_CARD_UPDATED", "CUSTOMER", cur.customerId().toString(),
                cur.fullName() + ": " + cur.percent() + "% -> " + req.percent() + "%" + (Boolean.FALSE.equals(req.active()) ? ", bloklandı" : ""));
        return load(m.storeId(), id);
    }

    private Card load(UUID storeId, UUID id) {
        return jdbc.sql(SELECT + " WHERE lc.store_id = :s AND lc.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("Bonus kartı tapılmadı"));
    }

    private static String fullName(CardRequest r) {
        return (r.lastName().trim() + " " + r.firstName().trim() + (blank(r.patronymic()) == null ? "" : " " + r.patronymic().trim())).trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String code() {
        SecureRandom r = new SecureRandom();
        return String.format("%06d", r.nextInt(1_000_000));
    }

    private static final String SELECT = """
            SELECT lc.*, c.code,
                   (SELECT COALESCE(SUM(e.amount), 0) FROM customer_bonus_entries e WHERE e.customer_id = lc.customer_id) AS balance,
                   (SELECT COALESCE(SUM(e.amount), 0) FROM customer_bonus_entries e WHERE e.customer_id = lc.customer_id AND e.amount > 0) AS earned,
                   (SELECT COALESCE(-SUM(e.amount), 0) FROM customer_bonus_entries e WHERE e.customer_id = lc.customer_id AND e.amount < 0) AS spent,
                   (SELECT MAX(s.created_at) FROM sales s WHERE s.customer_id = lc.customer_id AND s.status = 'COMPLETED') AS last_sale
            FROM loyalty_cards lc JOIN customers c ON c.id = lc.customer_id""";

    private static Card map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String first = rs.getString("first_name"), last = rs.getString("last_name"), pat = rs.getString("patronymic");
        return new Card(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("code"), first, last, pat,
                (last + " " + first + (pat == null ? "" : " " + pat)).trim(), rs.getString("phone"), Money.of(rs.getBigDecimal("percent")),
                rs.getBoolean("active"), Money.of(rs.getBigDecimal("balance")), Money.of(rs.getBigDecimal("earned")),
                Money.of(rs.getBigDecimal("spent")), rs.getObject("last_sale", OffsetDateTime.class), rs.getString("activation_code"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
