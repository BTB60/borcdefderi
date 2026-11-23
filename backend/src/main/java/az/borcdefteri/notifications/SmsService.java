package az.borcdefteri.notifications;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Avtomatik SMS: sends through the platform's {@link SmsGateway}, logs every message and keeps each store within its
 * monthly limit. The store owner switches automatic debt reminders on; until a provider is configured nothing is sent.
 */
@Service
@RestController
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    public record Status(boolean configured, boolean autoReminders, long sentThisMonth, int monthlyLimit) {}

    public record Settings(boolean autoReminders) {}

    public record Test(String phone) {}

    public record Message(UUID id, String phone, String text, String purpose, String status, String error, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final SmsGateway gateway;
    private final int monthlyLimit;

    public SmsService(JdbcClient jdbc, TenantService tenant, SmsGateway gateway,
                      @Value("${app.sms.monthly-limit:300}") int monthlyLimit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.gateway = gateway;
        this.monthlyLimit = monthlyLimit;
    }

    public boolean configured() {
        return gateway.configured();
    }

    /** True when the store wants its reminders by SMS and a provider is set up. */
    public boolean autoRemindersOn(UUID storeId) {
        return configured() && jdbc.sql("SELECT sms_auto_reminders FROM stores WHERE id = :s").param("s", storeId)
                .query(Boolean.class).optional().orElse(false);
    }

    /**
     * Sends one SMS for the store and logs it. Returns false (and logs why) when it could not be sent: no provider,
     * a phone that is not a mobile number, the monthly limit reached, or the provider refused it.
     */
    public boolean send(UUID storeId, String phone, String text, String purpose, UUID refId) {
        if (!configured()) return false;
        String to = normalize(phone);
        if (to == null) {
            record(storeId, phone == null ? "" : phone, text, purpose, refId, "Telefon nömrəsi yanlışdır");
            return false;
        }
        if (sentThisMonth(storeId) >= monthlyLimit) {
            record(storeId, to, text, purpose, refId, "Aylıq SMS limiti bitib (" + monthlyLimit + ")");
            return false;
        }
        try {
            gateway.send(to, text);
            record(storeId, to, text, purpose, refId, null);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("SMS to {} for store {} failed: {}", to, storeId, e.getMessage());
            record(storeId, to, text, purpose, refId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /** Azerbaijani numbers in any common form (+994 50 ..., 050 ..., 50 ...) to 994XXXXXXXXX; null if not a number. */
    static String normalize(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.length() == 10 && d.startsWith("0")) d = "994" + d.substring(1);
        else if (d.length() == 9) d = "994" + d;
        return d.length() >= 10 && d.length() <= 15 ? d : null;
    }

    private long sentThisMonth(UUID storeId) {
        return jdbc.sql("SELECT COUNT(*) FROM sms_messages WHERE store_id = :s AND status = 'SENT' AND created_at >= date_trunc('month', now())")
                .param("s", storeId).query(Long.class).single();
    }

    private void record(UUID storeId, String phone, String text, String purpose, UUID refId, String error) {
        jdbc.sql("""
                INSERT INTO sms_messages (id, store_id, phone, text, purpose, status, error, ref_id)
                VALUES (:id, :s, :p, :t, :pu, :st, :e, :r)""")
                .param("id", UUID.randomUUID()).param("s", storeId).param("p", cut(phone, 30)).param("t", cut(text, 1000))
                .param("pu", purpose).param("st", error == null ? "SENT" : "FAILED").param("e", error == null ? null : cut(error, 500))
                .param("r", refId).update();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------- the store's settings ----------

    @GetMapping("/api/settings/sms")
    public Status status() {
        CurrentMember m = tenant.requireBusinessMember();
        return statusOf(m.storeId());
    }

    @PutMapping("/api/settings/sms")
    public Status update(@RequestBody Settings req) {
        CurrentMember m = tenant.requireOwner();
        if (req.autoReminders() && !configured()) {
            throw ApiException.conflict("SMS_OFF", "SMS xidməti hələ qoşulmayıb. Administratorla əlaqə saxlayın");
        }
        jdbc.sql("UPDATE stores SET sms_auto_reminders = :a WHERE id = :s").param("a", req.autoReminders()).param("s", m.storeId()).update();
        return statusOf(m.storeId());
    }

    /** Sends a test SMS to the given number (counts against the monthly limit). */
    @PostMapping("/api/settings/sms/test")
    public Map<String, Object> test(@RequestBody Test req) {
        CurrentMember m = tenant.requireOwner();
        if (!configured()) throw ApiException.conflict("SMS_OFF", "SMS xidməti hələ qoşulmayıb. Administratorla əlaqə saxlayın");
        if (normalize(req.phone()) == null) throw ApiException.badRequest("INVALID_PHONE", "Telefon nömrəsi yanlışdır");
        boolean ok = send(m.storeId(), req.phone(), "HesabPro: “" + m.storeName() + "” üçün SMS xidməti işləyir.", "TEST", null);
        return Map.of("sent", ok);
    }

    @GetMapping("/api/sms")
    public List<Message> messages(@RequestParam(defaultValue = "100") int limit) {
        CurrentMember m = tenant.requireOwner();
        return jdbc.sql("SELECT * FROM sms_messages WHERE store_id = :s ORDER BY created_at DESC LIMIT " + Math.min(Math.max(limit, 1), 500))
                .param("s", m.storeId())
                .query((rs, n) -> new Message(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getString("text"),
                        rs.getString("purpose"), rs.getString("status"), rs.getString("error"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private Status statusOf(UUID storeId) {
        boolean auto = jdbc.sql("SELECT sms_auto_reminders FROM stores WHERE id = :s").param("s", storeId).query(Boolean.class).single();
        return new Status(configured(), auto, sentThisMonth(storeId), monthlyLimit);
    }
}
