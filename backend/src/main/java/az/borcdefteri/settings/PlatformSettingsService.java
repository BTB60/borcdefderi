package az.borcdefteri.settings;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform-wide payment and contact settings (table platform_settings), shown to stores on the subscription
 * payment page. Nothing here is hard-coded: the SUPER_ADMIN enters every value in the admin panel, and only the
 * SUPER_ADMIN can change them.
 */
@Service
public class PlatformSettingsService {

    static final String CONTACT_PHONE = "contact_phone";          // WhatsApp number
    static final String M10_ACCOUNT = "m10_account";
    static final String M10_RECIPIENT = "m10_recipient";
    static final String PAYMENT_NOTE = "payment_note";            // M10 payment instructions
    static final String CARD_NUMBER = "card_number";
    static final String CARD_HOLDER = "card_holder";
    static final String CARD_BANK = "card_bank";
    static final String CARD_NOTE = "card_note";                  // card payment instructions
    static final String EXTRA_PHONE = "extra_phone";
    static final String EXTRA_CONTACT = "extra_contact";
    static final String WHATSAPP_TEMPLATE = "whatsapp_template";
    static final String PAYMENT_INSTRUCTIONS = "payment_instructions";
    static final String AFTER_PAYMENT_TEXT = "after_payment_text";

    /**
     * Shown to store owners on "Abunə ol" and the payment page. {@code whatsapp} is the WhatsApp number in
     * international digits for wa.me links (994XXXXXXXXX).
     */
    public record Contact(String phone, String whatsapp, String m10Account, String m10Recipient, String paymentNote,
                          String cardNumber, String cardHolder, String cardBank, String cardNote,
                          String extraPhone, String extraContact, String whatsappTemplate,
                          String paymentInstructions, String afterPaymentText) {}

    /** Everything the admin form sends; blank values are cleared. */
    public record ContactInput(String phone, String m10Account, String m10Recipient, String paymentNote,
                               String cardNumber, String cardHolder, String cardBank, String cardNote,
                               String extraPhone, String extraContact, String whatsappTemplate,
                               String paymentInstructions, String afterPaymentText) {}

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
        String phone = get(v, CONTACT_PHONE);
        return new Contact(phone, whatsappNumber(phone), get(v, M10_ACCOUNT), get(v, M10_RECIPIENT),
                get(v, PAYMENT_NOTE), get(v, CARD_NUMBER), get(v, CARD_HOLDER), get(v, CARD_BANK),
                get(v, CARD_NOTE), get(v, EXTRA_PHONE), get(v, EXTRA_CONTACT), get(v, WHATSAPP_TEMPLATE),
                get(v, PAYMENT_INSTRUCTIONS), get(v, AFTER_PAYMENT_TEXT));
    }

    @Transactional
    public Contact updateContact(ContactInput in) {
        tenant.requireSuperAdmin();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CONTACT_PHONE, blankToNull(in.phone()));
        values.put(M10_ACCOUNT, blankToNull(in.m10Account()));
        values.put(M10_RECIPIENT, blankToNull(in.m10Recipient()));
        values.put(PAYMENT_NOTE, blankToNull(in.paymentNote()));
        values.put(CARD_NUMBER, blankToNull(in.cardNumber()));
        values.put(CARD_HOLDER, blankToNull(in.cardHolder()));
        values.put(CARD_BANK, blankToNull(in.cardBank()));
        values.put(CARD_NOTE, blankToNull(in.cardNote()));
        values.put(EXTRA_PHONE, blankToNull(in.extraPhone()));
        values.put(EXTRA_CONTACT, blankToNull(in.extraContact()));
        values.put(WHATSAPP_TEMPLATE, blankToNull(in.whatsappTemplate()));
        values.put(PAYMENT_INSTRUCTIONS, blankToNull(in.paymentInstructions()));
        values.put(AFTER_PAYMENT_TEXT, blankToNull(in.afterPaymentText()));
        for (Map.Entry<String, String> e : values.entrySet()) {
            jdbc.sql("""
                    INSERT INTO platform_settings (key, value, updated_at, updated_by) VALUES (:k, :v, now(), :by)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by
                    """)
                    .param("k", e.getKey()).param("v", e.getValue()).param("by", tenant.currentUserId())
                    .update();
        }
        String card = values.get(CARD_NUMBER);
        audit.log(null, tenant.currentUserId(), "PAYMENT_SETTINGS_UPDATED", "SETTINGS", "contact",
                "WhatsApp: " + values.get(CONTACT_PHONE) + ", M10: " + values.get(M10_ACCOUNT)
                        + ", kart: " + (card == null ? "—" : "****" + card.replaceAll("\\D", "")
                        .substring(Math.max(0, card.replaceAll("\\D", "").length() - 4))));
        return contact();
    }

    /**
     * Azerbaijani phone → international digits for wa.me links: "050 123 45 67" / "+994 50 123 45 67" /
     * "501234567" → "994501234567". wa.me does not accept the local leading 0.
     */
    static String whatsappNumber(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.startsWith("994")) return d.length() == 12 ? d : null;
        if (d.startsWith("0") && d.length() == 10) return "994" + d.substring(1);
        if (d.length() == 9) return "994" + d;
        return d.length() >= 10 && d.length() <= 15 ? d : null;
    }

    private static String get(Map<String, String> v, String key) {
        return blankToNull(v.get(key));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
