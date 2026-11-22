package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.common.Passwords;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Manager approval of sale returns. When the store turns it on, a return (or cancel) larger than the store's limit,
 * done by someone without RETURN_APPROVE, needs the PIN of a member who holds it (owners always do).
 * Five wrong PINs lock the typing member out of approvals for 15 minutes.
 */
@Service
public class ReturnApprovalService {

    public static final String PERM_RETURN_APPROVE = "RETURN_APPROVE";
    static final int MAX_FAILED = 5;
    static final int LOCK_MINUTES = 15;

    public record Policy(boolean enabled, BigDecimal limit, boolean canApprove, boolean hasPin) {}

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
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.has(PERM_RETURN_APPROVE)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Qaytarmanı təsdiq etmək icazəniz yoxdur");
        }
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id").param("id", m.userId()).query(String.class).single();
        if (currentPassword == null || !Passwords.fitsBcrypt(currentPassword) || !encoder.matches(currentPassword, hash)) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Cari şifrə yanlışdır");
        }
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
        if (typed.matches("\\d{4,6}")) {
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
        throw ApiException.forbidden("APPROVAL_PIN_INVALID", "PIN yanlışdır. " + left + " cəhd qalıb");
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
                SELECT s.return_approval_enabled, s.return_approval_limit, m.approval_pin_hash IS NOT NULL AS has_pin
                FROM stores s JOIN store_members m ON m.store_id = s.id WHERE m.id = :id""")
                .param("id", m.memberId())
                .query((rs, n) -> new Policy(rs.getBoolean(1), Money.of(rs.getBigDecimal(2)), m.has(PERM_RETURN_APPROVE),
                        rs.getBoolean(3))).single();
    }
}
