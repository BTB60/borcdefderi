package az.borcdefteri.customers;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.config.JwtService;
import az.borcdefteri.notifications.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HesabPro Kart — the customers' own app. A customer signs in with their phone (a 6-digit SMS code, when the platform
 * has an SMS provider) or with a card's number and activation code. They then see every active bonus card issued to
 * that phone, in any store that uses HesabPro: the store's card design, number, percent, balance and history. A new
 * card a store issues to the phone appears by itself. Never debts, sales or anything else of a store.
 */
@RestController
@RequestMapping("/api/public/customer-app")
public class CustomerAppService {

    public record PhoneRequest(String phone) {}

    public record VerifyRequest(String phone, String code) {}

    public record ActivateRequest(String cardNumber, String code) {}

    public record Session(String token, String phone, String name) {}

    public record Card(UUID id, String storeName, String storePhone, String number, String fullName, String firstName,
                       BigDecimal percent, BigDecimal balance, boolean active, JsonNode design, OffsetDateTime createdAt) {}

    public record Me(String phone, String name, String firstName, List<Card> cards) {}

    public record Entry(String kind, BigDecimal amount, String saleNumber, OffsetDateTime createdAt) {}

    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final JwtService jwt;
    private final SmsService sms;
    private final ObjectMapper json;
    private final Clock clock;

    public CustomerAppService(JdbcClient jdbc, JwtService jwt, SmsService sms, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.sms = sms;
        this.json = json;
        this.clock = clock;
    }

    /** Whether the SMS sign-in can be used (the app shows the card-code sign-in otherwise). */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("smsLogin", sms.configured());
    }

    /** Sends a 6-digit sign-in code to the phone (at most 3 in 10 minutes per phone). */
    @PostMapping("/code")
    @Transactional
    public Map<String, Object> sendCode(@RequestBody PhoneRequest req) {
        String phone = phone(req.phone());
        if (!sms.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_OFF", "SMS ilə giriş hələ işləmir — kartdakı aktivləşdirmə kodu ilə daxil olun");
        }
        long recent = jdbc.sql("SELECT COUNT(*) FROM customer_login_codes WHERE phone = :p AND created_at > now() - interval '10 minutes'")
                .param("p", phone).query(Long.class).single();
        if (recent >= 3) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES", "Çox kod istənildi. 10 dəqiqə sonra yenidən cəhd edin");
        String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        jdbc.sql("INSERT INTO customer_login_codes (id, phone, code_hash, expires_at) VALUES (:id, :p, :h, :e)")
                .param("id", UUID.randomUUID()).param("p", phone).param("h", hash(phone, code))
                .param("e", OffsetDateTime.now(clock).plus(CODE_TTL)).update();
        boolean sent = sms.send(null, phone, "HesabPro Kart giriş kodu: " + code + ". Kodu heç kimə deməyin.", "CUSTOMER_LOGIN", null);
        if (!sent) throw new ApiException(HttpStatus.BAD_GATEWAY, "SMS_FAILED", "SMS göndərilmədi. Bir az sonra yenidən cəhd edin");
        return Map.of("sent", true, "expiresInSeconds", CODE_TTL.toSeconds());
    }

    /** Checks the SMS code and signs the customer in (5 wrong tries spoil the code). */
    @PostMapping("/verify")
    @Transactional
    public Session verify(@RequestBody VerifyRequest req) {
        String phone = phone(req.phone());
        String code = req.code() == null ? "" : req.code().replaceAll("\\D", "");
        record Row(UUID id, String hash, int attempts) {}
        Row row = jdbc.sql("""
                SELECT id, code_hash, attempts FROM customer_login_codes
                WHERE phone = :p AND NOT used AND expires_at > now() ORDER BY created_at DESC LIMIT 1 FOR UPDATE""")
                .param("p", phone).query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("code_hash"), rs.getInt("attempts")))
                .optional().orElseThrow(() -> ApiException.badRequest("CODE_EXPIRED", "Kodun vaxtı bitib. Yeni kod istəyin"));
        if (row.attempts() >= 5) throw ApiException.badRequest("CODE_EXPIRED", "Çox səhv cəhd. Yeni kod istəyin");
        if (!MessageDigest.isEqual(row.hash().getBytes(StandardCharsets.UTF_8), hash(phone, code).getBytes(StandardCharsets.UTF_8))) {
            jdbc.sql("UPDATE customer_login_codes SET attempts = attempts + 1 WHERE id = :id").param("id", row.id()).update();
            throw ApiException.badRequest("WRONG_CODE", "Kod səhvdir");
        }
        jdbc.sql("UPDATE customer_login_codes SET used = TRUE WHERE id = :id").param("id", row.id()).update();
        return session(phone);
    }

    /** Signs in with a card: its number (HP-000123) and the 6-digit activation code the store gave. */
    @PostMapping("/activate")
    @Transactional
    public Session activate(@RequestBody ActivateRequest req) {
        String number = req.cardNumber() == null ? "" : req.cardNumber().trim();
        String code = req.code() == null ? "" : req.code().replaceAll("\\D", "");
        String cardPhone = jdbc.sql("""
                SELECT lc.phone FROM loyalty_cards lc JOIN customers c ON c.id = lc.customer_id
                WHERE LOWER(c.code) = LOWER(:n) AND lc.activation_code = :code AND lc.active""")
                .param("n", number).param("code", code).query(String.class).optional()
                .orElseThrow(() -> ApiException.badRequest("WRONG_CARD_CODE", "Kart nömrəsi və ya aktivləşdirmə kodu səhvdir"));
        return session(phone(cardPhone));
    }

    /** The customer's cards in every store (only active ones). */
    @GetMapping("/me")
    public Me me(@RequestHeader(value = "Authorization", required = false) String auth) {
        String phone = customer(auth).getValue();
        List<Card> cards = cards(phone);
        Card latest = cards.isEmpty() ? null : cards.get(0);
        return new Me(phone, latest == null ? null : latest.fullName(), latest == null ? null : latest.firstName(), cards);
    }

    @GetMapping("/cards/{id}/history")
    public List<Entry> history(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID id) {
        String phone = customer(auth).getValue();
        UUID customerId = jdbc.sql("""
                SELECT customer_id FROM loyalty_cards WHERE id = :id AND active
                  AND right(regexp_replace(phone, '[^0-9]', '', 'g'), 9) = :p""")
                .param("id", id).param("p", last9(phone)).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Kart tapılmadı"));
        return jdbc.sql("""
                SELECT e.kind, e.amount, s.number, e.created_at FROM customer_bonus_entries e LEFT JOIN sales s ON s.id = e.sale_id
                WHERE e.customer_id = :c ORDER BY e.created_at DESC LIMIT 200""").param("c", customerId)
                .query((rs, n) -> new Entry(rs.getString("kind"), Money.of(rs.getBigDecimal("amount")), rs.getString("number"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private List<Card> cards(String phone) {
        return jdbc.sql("""
                SELECT lc.id, st.name AS store_name, st.phone AS store_phone, c.code, lc.first_name, lc.last_name, lc.patronymic,
                       lc.percent, lc.active, lc.created_at, st.print_design -> 'card' AS design,
                       (SELECT COALESCE(SUM(e.amount), 0) FROM customer_bonus_entries e WHERE e.customer_id = lc.customer_id) AS balance
                FROM loyalty_cards lc JOIN customers c ON c.id = lc.customer_id JOIN stores st ON st.id = lc.store_id
                WHERE lc.active AND st.status = 'ACTIVE' AND right(regexp_replace(lc.phone, '[^0-9]', '', 'g'), 9) = :p
                ORDER BY lc.created_at DESC""").param("p", last9(phone))
                .query((rs, n) -> {
                    String first = rs.getString("first_name"), last = rs.getString("last_name"), pat = rs.getString("patronymic");
                    JsonNode design = null;
                    String raw = rs.getString("design");
                    if (raw != null) {
                        try { design = json.readTree(raw); } catch (Exception ignored) { /* default look */ }
                    }
                    return new Card(rs.getObject("id", UUID.class), rs.getString("store_name"), rs.getString("store_phone"),
                            rs.getString("code"), (last + " " + first + (pat == null ? "" : " " + pat)).trim(), first,
                            Money.of(rs.getBigDecimal("percent")), Money.of(rs.getBigDecimal("balance")), rs.getBoolean("active"),
                            design, rs.getObject("created_at", OffsetDateTime.class));
                }).list();
    }

    private Session session(String phone) {
        UUID id = jdbc.sql("""
                INSERT INTO customer_accounts (id, phone, last_login_at) VALUES (:id, :p, now())
                ON CONFLICT (phone) DO UPDATE SET last_login_at = now() RETURNING id""")
                .param("id", UUID.randomUUID()).param("p", phone).query(UUID.class).single();
        List<Card> cards = cards(phone);
        return new Session(jwt.issueCustomer(id, phone), phone, cards.isEmpty() ? null : cards.get(0).firstName());
    }

    private Map.Entry<UUID, String> customer(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Daxil olun");
        return jwt.parseCustomer(auth.substring(7).trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Yenidən daxil olun"));
    }

    /** Azerbaijani numbers in any common form to 994XXXXXXXXX. */
    static String phone(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.length() == 10 && d.startsWith("0")) d = "994" + d.substring(1);
        else if (d.length() == 9) d = "994" + d;
        if (!d.matches("994\\d{9}")) throw ApiException.badRequest("INVALID_PHONE", "Telefon nömrəsi düzgün deyil");
        return d;
    }

    private static String last9(String phone) {
        return phone.substring(phone.length() - 9);
    }

    private static String hash(String phone, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
