package az.borcdefteri.settings;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform-wide settings (table platform_settings). Only the SUPER_ADMIN can change them. */
@Service
public class PlatformSettingsService {

    static final String CONTACT_PHONE = "contact_phone";
    static final String M10_ACCOUNT = "m10_account";
    static final String M10_RECIPIENT = "m10_recipient";
    static final String PAYMENT_NOTE = "payment_note";

    /**
     * Shown to store owners on "Abunə ol": admin phone (+ digits-only WhatsApp number for wa.me links) and the
     * M10 payment details (M10 number / wallet, recipient name, free-text instructions).
     */
    public record Contact(String phone, String whatsapp, String m10Account, String m10Recipient, String paymentNote) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public PlatformSettingsService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    public Contact contact() {
        Map<String, String> v = new HashMap<>();
        jdbc.sql("SELECT key, value FROM platform_settings").query((rs, n) -> {
            v.put(rs.getString("key"), rs.getString("value"));
            return null;
        }).list();
        String phone = blankToNull(v.get(CONTACT_PHONE));
        String digits = phone == null ? null : phone.replaceAll("\\D", "");
        return new Contact(phone, digits == null || digits.isEmpty() ? null : digits,
                blankToNull(v.get(M10_ACCOUNT)), blankToNull(v.get(M10_RECIPIENT)), blankToNull(v.get(PAYMENT_NOTE)));
    }

    @Transactional
    public Contact updateContact(String phone, String m10Account, String m10Recipient, String paymentNote) {
        tenant.requireSuperAdmin();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CONTACT_PHONE, blankToNull(phone));
        values.put(M10_ACCOUNT, blankToNull(m10Account));
        values.put(M10_RECIPIENT, blankToNull(m10Recipient));
        values.put(PAYMENT_NOTE, blankToNull(paymentNote));
        for (Map.Entry<String, String> e : values.entrySet()) {
            jdbc.sql("""
                    INSERT INTO platform_settings (key, value, updated_at, updated_by) VALUES (:k, :v, now(), :by)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by
                    """)
                    .param("k", e.getKey()).param("v", e.getValue()).param("by", tenant.currentUserId())
                    .update();
        }
        audit.log(null, tenant.currentUserId(), "PAYMENT_SETTINGS_UPDATED", "SETTINGS", "contact",
                "telefon: " + values.get(CONTACT_PHONE) + ", M10: " + values.get(M10_ACCOUNT));
        return contact();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
