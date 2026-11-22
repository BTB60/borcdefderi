package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.common.Passwords;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Manager approval of sale returns. When the store turns it on, a return (or cancel) larger than the store's limit,
 * done by someone without RETURN_APPROVE, needs the PIN of a member who holds it (owners always do).
 * Five wrong PINs lock the typing member out of approvals for 15 minutes.
 * Instead of the PIN the approver may give a printed manager card: a Code128 barcode ("HPK" + 16 base32 characters,
 * 80 random bits) that a USB scanner or the phone camera reads into the same field. Only its SHA-256 is stored.
 */
@Service
public class ReturnApprovalService {

    public static final String PERM_RETURN_APPROVE = "RETURN_APPROVE";
    static final int MAX_FAILED = 5;
    static final int LOCK_MINUTES = 15;
    static final String CARD_PREFIX = "HPK";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Policy(boolean enabled, BigDecimal limit, boolean canApprove, boolean hasPin, boolean hasCard) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final TransactionTemplate separateTx;

    public ReturnApprovalService(JdbcClient jdbc, TenantService tenant, PasswordEncoder encoder, AuditService audit,
                                 PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.encoder = encoder;
        this.audit = audit;
        this.separateTx = new TransactionTemplate(txManager);
        this.separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Policy policy() {
        CurrentMember m = tenant.requireBusinessMember();
        return load(m);
    }

    @Transactional
    public Policy updatePolicy(boolean enabled, BigDecimal limit) {
        CurrentMember m = tenant.requireOwner();
        BigDecimal l = Money.of(limit == null ? BigDecimal.ZERO : limit);
        if (l.signum() < 0) throw ApiException.badRequest("INVALID_LIMIT", "Limit mənfi ola bilməz");
        jdbc.sql("UPDATE stores SET return_approval_enabled = :e, return_approval_limit = :l WHERE id = :s")
                .param("e", enabled).param("l", l).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "RETURN_APPROVAL_POLICY", "STORE", m.storeId().toString(),
                enabled ? "Qaytarma təsdiqi aktiv, limit " + l + " AZN" : "Qaytarma təsdiqi söndürüldü");
        return load(m);
    }

    /** The approver sets their own PIN; the account password guards it against a borrowed, unlocked session. */
    @Transactional
    public Policy setPin(String pin, String currentPassword) {
        CurrentMember m = requireApprover();
        requirePassword(m, currentPassword);
        if (pin == null || !pin.matches("\\d{4,6}")) {
            throw ApiException.badRequest("INVALID_PIN", "PIN 4–6 rəqəmdən ibarət olmalıdır");
        }
        if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) {
            throw ApiException.badRequest("WEAK_PIN", "Bu PIN çox asandır (1111, 1234 kimi). Başqasını seçin");
        }
        // two managers with the same PIN would make the approval ambiguous
        for (Approver a : approvers(m.storeId())) {
            if (!a.userId().equals(m.userId()) && encoder.matches(pin, a.pinHash())) {
                throw ApiException.conflict("PIN_TAKEN", "Bu PIN artıq istifadə olunur. Başqasını seçin");
            }
        }
        jdbc.sql("UPDATE store_members SET approval_pin_hash = :h WHERE id = :id")
                .param("h", encoder.encode(pin)).param("id", m.memberId()).update();
        audit.log(m.storeId(), m.userId(), "APPROVAL_PIN_SET", "MEMBER", m.memberId().toString(), "Təsdiq PIN-i təyin edildi");
        return load(m);
    }

    @Transactional
    public Policy clearPin() {
        CurrentMember m = tenant.requireBusinessMember();
        jdbc.sql("UPDATE store_members SET approval_pin_hash = NULL WHERE id = :id").param("id", m.memberId()).update();
        audit.log(m.storeId(), m.userId(), "APPROVAL_PIN_CLEARED", "MEMBER", m.memberId().toString(), "Təsdiq PIN-i silindi");
        return load(m);
    }

    /** Issues the caller's manager card (replacing any earlier one) and returns it as a printable PDF. */
    @Transactional
    public ExportFile issueCard(String currentPassword) {
        CurrentMember m = requireApprover();
        requirePassword(m, currentPassword);
        StringBuilder code = new StringBuilder(CARD_PREFIX);
        for (int i = 0; i < 16; i++) code.append(BASE32.charAt(RANDOM.nextInt(32)));
        jdbc.sql("UPDATE store_members SET approval_card_hash = :h, approval_card_issued_at = now() WHERE id = :id")
                .param("h", sha256(code.toString())).param("id", m.memberId()).update();
        audit.log(m.storeId(), m.userId(), "APPROVAL_CARD_ISSUED", "MEMBER", m.memberId().toString(),
                "Menecer kartı verildi (köhnə kart etibarsızdır)");
        record Names(String store, String person) {}
        Names n = jdbc.sql("SELECT s.name, u.full_name FROM store_members m JOIN stores s ON s.id = m.store_id "
                        + "JOIN users u ON u.id = m.user_id WHERE m.id = :id")
                .param("id", m.memberId()).query((rs, i) -> new Names(rs.getString(1), rs.getString(2))).single();
        return new ExportFile(cardPdf(code.toString(), n.store(), n.person(), tenant.today()), "application/pdf", "menecer-karti.pdf");
    }

    @Transactional
    public Policy revokeCard() {
        CurrentMember m = tenant.requireBusinessMember();
        jdbc.sql("UPDATE store_members SET approval_card_hash = NULL, approval_card_issued_at = NULL WHERE id = :id")
                .param("id", m.memberId()).update();
        audit.log(m.storeId(), m.userId(), "APPROVAL_CARD_REVOKED", "MEMBER", m.memberId().toString(), "Menecer kartı ləğv edildi");
        return load(m);
    }

    /**
     * Returns who approved a return of {@code amount}: null when no approval is needed, the caller when they may
     * approve themselves, else the manager whose PIN was typed. Throws APPROVAL_REQUIRED / APPROVAL_PIN_INVALID /
     * APPROVAL_LOCKED otherwise.
     */
    UUID approve(CurrentMember m, BigDecimal amount, String pin) {
        record Store(boolean enabled, BigDecimal limit) {}
        Store s = jdbc.sql("SELECT return_approval_enabled, return_approval_limit FROM stores WHERE id = :id")
                .param("id", m.storeId())
                .query((rs, n) -> new Store(rs.getBoolean(1), rs.getBigDecimal(2))).single();
        if (m.has(PERM_RETURN_APPROVE)) return m.userId();
        if (!s.enabled() || amount.compareTo(s.limit()) <= 0) return null;
        if (pin == null || pin.isBlank()) {
            throw ApiException.forbidden("APPROVAL_REQUIRED",
                    "Bu qaytarma (" + Money.of(amount) + " AZN) menecer təsdiqi tələb edir. Menecer PIN-ini daxil edin");
        }
        boolean locked = jdbc.sql("SELECT approval_locked_until > now() FROM store_members WHERE id = :id")
                .param("id", m.memberId()).query(Boolean.class).optional().map(Boolean.TRUE::equals).orElse(false);
        if (locked) {
            throw ApiException.forbidden("APPROVAL_LOCKED",
                    "Çox sayda yanlış PIN. " + LOCK_MINUTES + " dəqiqə sonra yenidən cəhd edin");
        }
        String typed = pin.trim();
        String card = typed.toUpperCase(Locale.ROOT);
        if (card.matches(CARD_PREFIX + "[A-Z2-7]{16}")) {
            var owner = jdbc.sql("""
                    SELECT m.user_id FROM store_members m JOIN users u ON u.id = m.user_id
                    WHERE m.store_id = :s AND m.approval_card_hash = :h AND m.active AND u.enabled AND m.user_id <> :me
                      AND (m.role = 'STORE_OWNER' OR EXISTS (SELECT 1 FROM member_permissions p
                                                             WHERE p.member_id = m.id AND p.permission = 'RETURN_APPROVE'))
                    """).param("s", m.storeId()).param("h", sha256(card)).param("me", m.userId())
                    .query(UUID.class).optional();
            if (owner.isPresent()) {
                jdbc.sql("UPDATE store_members SET approval_failed = 0, approval_locked_until = NULL WHERE id = :id")
                        .param("id", m.memberId()).update();
                return owner.get();
            }
        } else if (typed.matches("\\d{4,6}")) {
            for (Approver a : approvers(m.storeId())) {
                if (!a.userId().equals(m.userId()) && encoder.matches(typed, a.pinHash())) {
                    jdbc.sql("UPDATE store_members SET approval_failed = 0, approval_locked_until = NULL WHERE id = :id")
                            .param("id", m.memberId()).update();
                    return a.userId();
                }
            }
        }
        // the return's own transaction rolls back on the error below, so the failure is counted in its own one
        int left = separateTx.execute(st -> {
            int failed = jdbc.sql("UPDATE store_members SET approval_failed = approval_failed + 1 WHERE id = :id RETURNING approval_failed")
                    .param("id", m.memberId()).query(Integer.class).single();
            if (failed >= MAX_FAILED) {
                jdbc.sql("UPDATE store_members SET approval_failed = 0, approval_locked_until = now() + make_interval(mins => :m) WHERE id = :id")
                        .param("m", LOCK_MINUTES).param("id", m.memberId()).update();
                audit.log(m.storeId(), m.userId(), "APPROVAL_LOCKED", "MEMBER", m.memberId().toString(),
                        MAX_FAILED + " yanlış PIN, " + LOCK_MINUTES + " dəqiqəlik bloklandı");
                return 0;
            }
            return MAX_FAILED - failed;
        });
        if (left == 0) {
            throw ApiException.forbidden("APPROVAL_LOCKED",
                    "Çox sayda yanlış PIN. " + LOCK_MINUTES + " dəqiqə sonra yenidən cəhd edin");
        }
        throw ApiException.forbidden("APPROVAL_PIN_INVALID", "PIN və ya kart yanlışdır. " + left + " cəhd qalıb");
    }

    private CurrentMember requireApprover() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.has(PERM_RETURN_APPROVE)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Qaytarmanı təsdiq etmək icazəniz yoxdur");
        }
        return m;
    }

    private void requirePassword(CurrentMember m, String currentPassword) {
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id").param("id", m.userId()).query(String.class).single();
        if (currentPassword == null || !Passwords.fitsBcrypt(currentPassword) || !encoder.matches(currentPassword, hash)) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Cari şifrə yanlışdır");
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A4 page with a bank-card sized (85.6 x 54 mm) card at the top to cut out: store, person, barcode. */
    private static byte[] cardPdf(String code, String store, String person, LocalDate issued) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4);
            PdfWriter w = PdfWriter.getInstance(doc, out);
            doc.open();
            PdfContentByte cb = w.getDirectContent();
            BaseFont base = font("fonts/DejaVuSans.ttf"), bold = font("fonts/DejaVuSans-Bold.ttf");
            float cw = 242.6f, ch = 153f, x = (PageSize.A4.getWidth() - cw) / 2, y = PageSize.A4.getHeight() - 60 - ch;
            cb.setColorStroke(new Color(0x9C, 0xA3, 0xAF));
            cb.setLineWidth(0.5f);
            cb.setLineDash(3, 3, 0);
            cb.roundRectangle(x, y, cw, ch, 8);
            cb.stroke();
            cb.setLineDash(0);
            cb.setColorFill(new Color(0x0B, 0x1F, 0x3A));
            cb.roundRectangle(x + 6, y + ch - 34, cw - 12, 28, 5);
            cb.fill();
            text(cb, bold, 10, Color.WHITE, x + 14, y + ch - 24, "MENECER KARTI", PdfContentByte.ALIGN_LEFT);
            text(cb, base, 7, new Color(0xF5, 0xC5, 0x42), x + cw - 14, y + ch - 24, "HesabPro", PdfContentByte.ALIGN_RIGHT);
            text(cb, bold, 9, Color.BLACK, x + 14, y + ch - 50, trim(store, 40), PdfContentByte.ALIGN_LEFT);
            text(cb, base, 8, Color.DARK_GRAY, x + 14, y + ch - 62, trim(person, 44), PdfContentByte.ALIGN_LEFT);
            Barcode128 bc = new Barcode128();
            bc.setCode(code);
            bc.setFont(null); // no readable text under the bars
            bc.setX(0.8f);
            bc.setBarHeight(42);
            Image img = bc.createImageWithBarcode(cb, Color.BLACK, Color.BLACK);
            img.setAbsolutePosition(x + (cw - img.getScaledWidth()) / 2, y + 22);
            cb.addImage(img);
            text(cb, base, 6, Color.GRAY, x + 14, y + 10,
                    "Qaytarma təsdiqi · " + issued.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), PdfContentByte.ALIGN_LEFT);
            text(cb, base, 9, Color.DARK_GRAY, PageSize.A4.getWidth() / 2, y - 30,
                    "Kartı qırıq xətt üzrə kəsin. İtirsəniz, Parametrlər bölməsində yeni kart verin, köhnəsi işləməyəcək.",
                    PdfContentByte.ALIGN_CENTER);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Kart yaradıla bilmədi");
        }
    }

    private static BaseFont font(String file) throws Exception {
        return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                new ClassPathResource(file).getContentAsByteArray(), null);
    }

    private static String trim(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void text(PdfContentByte cb, BaseFont f, float size, Color c, float x, float y, String s, int align) {
        cb.beginText();
        cb.setFontAndSize(f, size);
        cb.setColorFill(c);
        cb.showTextAligned(align, s, x, y, 0);
        cb.endText();
    }

    private record Approver(UUID userId, String pinHash) {}

    /** Active members who may approve (owners, or holders of RETURN_APPROVE) and have a PIN. */
    private List<Approver> approvers(UUID storeId) {
        return jdbc.sql("""
                SELECT m.user_id, m.approval_pin_hash FROM store_members m JOIN users u ON u.id = m.user_id
                WHERE m.store_id = :s AND m.active AND u.enabled AND m.approval_pin_hash IS NOT NULL
                  AND (m.role = 'STORE_OWNER' OR EXISTS (SELECT 1 FROM member_permissions p
                                                         WHERE p.member_id = m.id AND p.permission = 'RETURN_APPROVE'))
                """).param("s", storeId)
                .query((rs, n) -> new Approver(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    private Policy load(CurrentMember m) {
        return jdbc.sql("""
                SELECT s.return_approval_enabled, s.return_approval_limit, m.approval_pin_hash IS NOT NULL AS has_pin,
                       m.approval_card_hash IS NOT NULL AS has_card
                FROM stores s JOIN store_members m ON m.store_id = s.id WHERE m.id = :id""")
                .param("id", m.memberId())
                .query((rs, n) -> new Policy(rs.getBoolean(1), Money.of(rs.getBigDecimal(2)), m.has(PERM_RETURN_APPROVE),
                        rs.getBoolean(3), rs.getBoolean(4))).single();
    }
}
