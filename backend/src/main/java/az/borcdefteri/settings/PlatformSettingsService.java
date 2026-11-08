package az.borcdefteri.settings;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-wide settings (table platform_settings). Only the SUPER_ADMIN can change them. */
@Service
public class PlatformSettingsService {

    static final String CONTACT_PHONE = "contact_phone";

    /** Contact shown to store owners when they press "Abunə ol". whatsapp holds digits only (for wa.me links). */
    public record Contact(String phone, String whatsapp) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public PlatformSettingsService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    public Contact contact() {
        String phone = jdbc.sql("SELECT value FROM platform_settings WHERE key = :k")
                .param("k", CONTACT_PHONE).query(String.class).optional().orElse(null);
        if (phone == null || phone.isBlank()) {
            return new Contact(null, null);
        }
        String digits = phone.replaceAll("\\D", "");
        return new Contact(phone, digits.isEmpty() ? null : digits);
    }

    @Transactional
    public Contact updateContact(String phone) {
        tenant.requireSuperAdmin();
        String value = phone == null || phone.isBlank() ? null : phone.trim();
        jdbc.sql("""
                INSERT INTO platform_settings (key, value, updated_at, updated_by) VALUES (:k, :v, now(), :by)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by
                """)
                .param("k", CONTACT_PHONE).param("v", value).param("by", tenant.currentUserId())
                .update();
        audit.log(null, tenant.currentUserId(), "CONTACT_UPDATED", "SETTINGS", CONTACT_PHONE, value);
        return contact();
    }
}
