package az.borcdefteri.admin;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Köməkçi adminlər: the platform admin gives a helper a login that can only see stores, approve / reject payment
 * requests, remind stores and keep notes (the allowed calls are listed in SecurityConfig). Created with a one-time
 * password that must be changed at the first sign-in; can be switched off at any time.
 */
@Service
@RestController
public class SupportAdminService {

    public record Helper(UUID id, String email, String fullName, String phone, boolean enabled, OffsetDateTime createdAt,
                         OffsetDateTime lastLoginAt) {}

    public record NewHelper(@NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(max = 150) String fullName,
                            @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone) {}

    public record Enabled(boolean enabled) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final PasswordEncoder encoder;

    public SupportAdminService(JdbcClient jdbc, TenantService tenant, AuditService audit, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.encoder = encoder;
    }

    @GetMapping("/api/admin/support-admins")
    public List<Helper> list() {
        tenant.requireSuperAdmin();
        return jdbc.sql("""
                SELECT u.id, u.email, u.full_name, u.phone, u.enabled, u.created_at,
                       (SELECT MAX(a.created_at) FROM audit_logs a WHERE a.actor_id = u.id AND a.action = 'LOGIN') AS last_login
                FROM users u WHERE u.platform_role = 'SUPPORT_ADMIN' ORDER BY u.created_at
                """).query((rs, n) -> new Helper(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("phone"), rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_login", OffsetDateTime.class))).list();
    }

    /** Returns the one-time password once; it is not stored in clear anywhere. */
    @PostMapping("/api/admin/support-admins")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody NewHelper in) {
        tenant.requireSuperAdmin();
        String email = in.email().trim().toLowerCase(Locale.ROOT);
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM users WHERE email = :e").param("e", email).query(Long.class).single() > 0;
        if (exists) throw ApiException.conflict("EMAIL_EXISTS", "Bu e-poçt artıq istifadə olunur");
        String password = oneTimePassword();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO users (id, email, password_hash, full_name, phone, platform_role, must_change_password)
                VALUES (:id, :e, :h, :n, :p, 'SUPPORT_ADMIN', TRUE)
                """).param("id", id).param("e", email).param("h", encoder.encode(password)).param("n", in.fullName().trim())
                .param("p", in.phone() == null || in.phone().isBlank() ? null : in.phone().trim()).update();
        audit.log(null, tenant.currentUserId(), "SUPPORT_ADMIN_CREATED", "USER", id.toString(), "Köməkçi admin: " + email);
        return Map.of("id", id, "email", email, "temporaryPassword", password);
    }

    @PostMapping("/api/admin/support-admins/{id}/enabled")
    @Transactional
    public Map<String, Object> setEnabled(@PathVariable UUID id, @RequestBody Enabled in) {
        tenant.requireSuperAdmin();
        int n = jdbc.sql("""
                UPDATE users SET enabled = :e, password_changed_at = CASE WHEN :e THEN password_changed_at ELSE now() END
                WHERE id = :id AND platform_role = 'SUPPORT_ADMIN'
                """).param("e", in.enabled()).param("id", id).update();
        if (n == 0) throw ApiException.notFound("Köməkçi admin tapılmadı");
        audit.log(null, tenant.currentUserId(), in.enabled() ? "SUPPORT_ADMIN_ENABLED" : "SUPPORT_ADMIN_DISABLED", "USER", id.toString(),
                in.enabled() ? "Köməkçi admin aktiv edildi" : "Köməkçi admin söndürüldü");
        return Map.of("id", id, "enabled", in.enabled());
    }

    private static String oneTimePassword() {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder("Hp#");
        for (int i = 0; i < 13; i++) b.append(abc.charAt(RANDOM.nextInt(abc.length())));
        return b.toString();
    }
}
