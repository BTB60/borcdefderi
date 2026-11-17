package az.borcdefteri.auth;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kassir PIN: each worker sets a 4–6 digit PIN (unique in the store). On a shared till another cashier takes over
 * with their PIN, and the locked screen opens with it — no e-mail and password each time. Wrong PINs are counted:
 * after 10 in 10 minutes the store's till waits.
 */
@Service
@RestController
public class PinService {

    public record SetPin(@NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN 4-6 rəqəm olmalıdır") String pin,
                         @NotBlank @Size(max = 72) String currentPassword) {}

    public record PinLogin(@NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN 4-6 rəqəm olmalıdır") String pin) {}

    private record Member(UUID userId, String pinHash) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final AuthService auth;

    public PinService(JdbcClient jdbc, TenantService tenant, PasswordEncoder encoder, AuditService audit, AuthService auth) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.encoder = encoder;
        this.audit = audit;
        this.auth = auth;
    }

    private List<Member> members(UUID storeId) {
        return jdbc.sql("""
                SELECT u.id, u.pin_hash FROM store_members m JOIN users u ON u.id = m.user_id
                WHERE m.store_id = :s AND m.active AND u.enabled AND u.pin_hash IS NOT NULL
                """).param("s", storeId)
                .query((rs, n) -> new Member(rs.getObject("id", UUID.class), rs.getString("pin_hash"))).list();
    }

    @GetMapping("/api/profile/pin")
    public Map<String, Object> hasPin() {
        UUID me = tenant.currentUserId();
        boolean has = jdbc.sql("SELECT pin_hash IS NOT NULL FROM users WHERE id = :id").param("id", me).query(Boolean.class).single();
        return Map.of("hasPin", has);
    }

    @PutMapping("/api/profile/pin")
    @Transactional
    public Map<String, Object> setPin(@Valid @RequestBody SetPin in) {
        CurrentMember m = tenant.requireMember();
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id").param("id", m.userId()).query(String.class).single();
        if (!encoder.matches(in.currentPassword(), hash)) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Hazırkı şifrə yanlışdır");
        }
        for (Member other : members(m.storeId())) {
            if (!other.userId().equals(m.userId()) && encoder.matches(in.pin(), other.pinHash())) {
                throw ApiException.conflict("PIN_TAKEN", "Bu PIN mağazada başqa işçidə var — başqa PIN seçin");
            }
        }
        jdbc.sql("UPDATE users SET pin_hash = :h WHERE id = :id").param("h", encoder.encode(in.pin())).param("id", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "PIN_SET", "USER", m.userId().toString(), "Kassa PIN-i təyin edildi");
        return Map.of("hasPin", true);
    }

    @DeleteMapping("/api/profile/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void removePin() {
        CurrentMember m = tenant.requireMember();
        jdbc.sql("UPDATE users SET pin_hash = NULL WHERE id = :id").param("id", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "PIN_REMOVED", "USER", m.userId().toString(), "Kassa PIN-i silindi");
    }

    /**
     * On a till that is signed in to a store: the worker of that store with this PIN takes over (a new token for
     * them). Also opens the locked screen.
     */
    @PostMapping("/api/till/pin-switch")
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse switchByPin(@Valid @RequestBody PinLogin in) {
        CurrentMember m = tenant.requireMember();
        long wrong = jdbc.sql("""
                SELECT COUNT(*) FROM audit_logs WHERE store_id = :s AND action = 'PIN_FAILED' AND created_at > now() - interval '10 minutes'
                """).param("s", m.storeId()).query(Long.class).single();
        if (wrong >= 10) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PIN_LOCKED", "Çox səhv PIN yazılıb — 10 dəqiqə sonra yenidən cəhd edin");
        }
        for (Member c : members(m.storeId())) {
            if (encoder.matches(in.pin(), c.pinHash())) {
                audit.log(m.storeId(), c.userId(), "PIN_SWITCH", "USER", c.userId().toString(), "Kassaya PIN ilə keçid");
                return auth.issueFor(c.userId());
            }
        }
        audit.log(m.storeId(), m.userId(), "PIN_FAILED", "USER", m.userId().toString(), "Səhv kassa PIN-i");
        throw ApiException.badRequest("WRONG_PIN", "PIN yanlışdır");
    }
}
