package az.borcdefteri.auth;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.AppProperties;
import az.borcdefteri.common.Passwords;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Forgotten passwords.
 * <ol>
 *   <li>Self-service: "Şifrəni unutdum" e-mails a one-time link (30 min) when SMTP is configured.</li>
 *   <li>Assisted: every request is listed for the platform admin, who issues a one-time temporary password
 *       (store owners can do the same for their employees). The user must pick a new password after logging in.</li>
 * </ol>
 * Responses never reveal whether an e-mail is registered.
 */
@Service
public class PasswordResetService {

    /** Unambiguous characters for temporary passwords that are read out / typed from WhatsApp. */
    private static final String TEMP_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record ForgotResult(boolean emailEnabled) {}

    public record TemporaryPassword(String temporaryPassword, String email, String fullName, String phone) {}

    public record ResetRequest(UUID id, UUID userId, String email, String fullName, String phone, String storeName,
                               String role, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetMailer mailer;
    private final TenantService tenant;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;

    public PasswordResetService(JdbcClient jdbc, PasswordEncoder passwordEncoder, PasswordResetMailer mailer,
                                TenantService tenant, AuditService audit, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.tenant = tenant;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- self-service

    /** Always the same answer whether or not the e-mail exists. */
    @Transactional
    public ForgotResult forgot(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        record U(UUID id, String fullName, String platformRole, UUID storeId) {}
        U user = jdbc.sql("""
                SELECT u.id, u.full_name, u.platform_role, m.store_id
                FROM users u LEFT JOIN store_members m ON m.user_id = u.id
                WHERE u.email = :e AND u.enabled
                """).param("e", normalized)
                .query((rs, n) -> new U(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("platform_role"), rs.getObject("store_id", UUID.class)))
                .optional().orElse(null);
        if (user != null) {
            // One open request per user for the admin list (repeated clicks do not pile up).
            boolean open = jdbc.sql("SELECT COUNT(*) FROM password_reset_requests WHERE user_id = :u AND status = 'OPEN'")
                    .param("u", user.id()).query(Long.class).single() > 0;
            if (!open && !"SUPER_ADMIN".equals(user.platformRole())) {
                jdbc.sql("INSERT INTO password_reset_requests (id, user_id, ip_address) VALUES (:id, :u, :ip)")
                        .param("id", UUID.randomUUID()).param("u", user.id()).param("ip", clientIp()).update();
            }
            if (mailer.enabled()) {
                int minutes = props.passwordReset().tokenMinutes();
                String token = newToken();
                jdbc.sql("""
                        INSERT INTO password_reset_tokens (token_hash, user_id, expires_at)
                        VALUES (:h, :u, :exp)
                        """).param("h", sha256(token)).param("u", user.id())
                        .param("exp", OffsetDateTime.now(clock).plusMinutes(minutes)).update();
                String link = props.passwordReset().webUrl().replaceAll("/+$", "") + "/sifre-berpa?token=" + token;
                // Sent after commit and off the request thread: the response time must not reveal that the
                // account exists, and the link must not arrive before its token is stored.
                Runnable send = () -> java.util.concurrent.CompletableFuture.runAsync(
                        () -> mailer.send(normalized, user.fullName(), link, minutes));
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    send.run();
                                }
                            });
                } else {
                    send.run();
                }
            }
            audit.log(user.storeId(), user.id(), "PASSWORD_RESET_REQUESTED", "USER", user.id().toString(),
                    mailer.enabled() ? "E-poçtla bərpa linki" : "Administrator vasitəsilə bərpa");
        }
        return new ForgotResult(mailer.enabled());
    }

    /** Sets a new password with a valid, unused, unexpired e-mail token. */
    @Transactional
    public void resetWithToken(String token, String newPassword) {
        Passwords.requireBcryptLength(newPassword);
        record T(UUID userId, OffsetDateTime expiresAt, OffsetDateTime usedAt, String platformRole) {}
        T t = jdbc.sql("""
                SELECT t.user_id, t.expires_at, t.used_at, u.platform_role
                FROM password_reset_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = :h AND u.enabled
                FOR UPDATE OF t
                """).param("h", sha256(token == null ? "" : token))
                .query((rs, n) -> new T(rs.getObject("user_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("used_at", OffsetDateTime.class), rs.getString("platform_role")))
                .optional().orElse(null);
        if (t == null || t.usedAt() != null || t.expiresAt().isBefore(OffsetDateTime.now(clock))) {
            throw ApiException.badRequest("INVALID_RESET_LINK", "Bərpa linki etibarsızdır və ya vaxtı keçib. Yenisini istəyin");
        }
        requireLength(newPassword, t.platformRole());
        setPassword(t.userId(), newPassword, false);
        jdbc.sql("UPDATE password_reset_tokens SET used_at = now() WHERE user_id = :u AND used_at IS NULL")
                .param("u", t.userId()).update();
        closeRequests(t.userId(), null);
        audit.log(storeOf(t.userId()), t.userId(), "PASSWORD_RESET", "USER", t.userId().toString(), "E-poçt linki ilə");
    }

    // ---------------------------------------------------------------- assisted (admin / store owner)

    public List<ResetRequest> openRequests() {
        tenant.requireSuperAdmin();
        return jdbc.sql("""
                SELECT r.id, r.user_id, r.created_at, u.email, u.full_name, u.phone, m.role, s.name AS store_name
                FROM password_reset_requests r
                JOIN users u ON u.id = r.user_id
                LEFT JOIN store_members m ON m.user_id = u.id
                LEFT JOIN stores s ON s.id = m.store_id
                WHERE r.status = 'OPEN'
                ORDER BY r.created_at DESC LIMIT 200
                """).query((rs, n) -> new ResetRequest(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("email"), rs.getString("full_name"), rs.getString("phone"), rs.getString("store_name"),
                rs.getString("role"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public void dismissRequest(UUID requestId) {
        tenant.requireSuperAdmin();
        jdbc.sql("UPDATE password_reset_requests SET status = 'DONE', resolved_at = now(), resolved_by = :a WHERE id = :id")
                .param("a", tenant.currentUserId()).param("id", requestId).update();
    }

    /** Platform admin: temporary password for any store user (not for other platform admins). */
    @Transactional
    public TemporaryPassword adminReset(UUID userId) {
        tenant.requireSuperAdmin();
        Map<String, Object> u = jdbc.sql("SELECT email, full_name, phone, platform_role FROM users WHERE id = :id")
                .param("id", userId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("İstifadəçi tapılmadı"));
        if ("SUPER_ADMIN".equals(u.get("platform_role"))) {
            throw ApiException.forbidden("ADMIN_ACCOUNT", "Platforma administratorunun şifrəsi bu yolla sıfırlanmır");
        }
        return issueTemporary(userId, (String) u.get("email"), (String) u.get("full_name"), (String) u.get("phone"),
                tenant.currentUserId(), "Platforma administratoru");
    }

    /** Store owner: temporary password for one of the store's employees. */
    @Transactional
    public TemporaryPassword ownerResetEmployee(UUID memberId) {
        CurrentMember owner = tenant.requireMember();
        if (!owner.isOwner()) {
            throw ApiException.forbidden("OWNER_ONLY", "Yalnız mağaza sahibi bu əməliyyatı edə bilər");
        }
        Map<String, Object> u = jdbc.sql("""
                SELECT u.id, u.email, u.full_name, u.phone FROM store_members m JOIN users u ON u.id = m.user_id
                WHERE m.id = :m AND m.store_id = :s AND m.role = 'EMPLOYEE'
                """).param("m", memberId).param("s", owner.storeId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("İşçi tapılmadı"));
        return issueTemporary((UUID) u.get("id"), (String) u.get("email"), (String) u.get("full_name"),
                (String) u.get("phone"), owner.userId(), "Mağaza sahibi");
    }

    private TemporaryPassword issueTemporary(UUID userId, String email, String fullName, String phone, UUID actor, String by) {
        String temp = temporaryPassword();
        setPassword(userId, temp, true);
        closeRequests(userId, actor);
        audit.log(storeOf(userId), actor, "PASSWORD_RESET_BY_ADMIN", "USER", userId.toString(),
                by + " müvəqqəti şifrə verdi: " + email);
        return new TemporaryPassword(temp, email, fullName, phone);
    }

    // ---------------------------------------------------------------- helpers

    /** New password + revoke every existing login token (password_changed_at). */
    private void setPassword(UUID userId, String password, boolean mustChange) {
        jdbc.sql("""
                UPDATE users SET password_hash = :h, password_changed_at = now(), must_change_password = :must
                WHERE id = :id
                """).param("h", passwordEncoder.encode(password)).param("must", mustChange).param("id", userId).update();
    }

    private void closeRequests(UUID userId, UUID actor) {
        jdbc.sql("""
                UPDATE password_reset_requests SET status = 'DONE', resolved_at = now(), resolved_by = :a
                WHERE user_id = :u AND status = 'OPEN'
                """).param("a", actor).param("u", userId).update();
    }

    private UUID storeOf(UUID userId) {
        return jdbc.sql("SELECT store_id FROM store_members WHERE user_id = :u").param("u", userId)
                .query(UUID.class).optional().orElse(null);
    }

    static void requireLength(String password, String platformRole) {
        int min = "SUPER_ADMIN".equals(platformRole) ? 12 : 8;
        if (password == null || password.length() < min) {
            throw ApiException.badRequest("PASSWORD_TOO_SHORT", "Yeni şifrə ən azı " + min + " simvol olmalıdır");
        }
    }

    static String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(TEMP_CHARS.charAt(RANDOM.nextInt(TEMP_CHARS.length())));
        }
        return sb.toString(); // e.g. "Kp7mQ-x3Rta" — 11 characters
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String ip = attrs.getRequest().getRemoteAddr();
            return ip == null ? null : ip.substring(0, Math.min(ip.length(), 64));
        }
        return null;
    }
}
