package az.borcdefteri.auth;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.config.JwtService;
import az.borcdefteri.subscriptions.SubscriptionService;
import az.borcdefteri.subscriptions.SubscriptionSummary;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final TenantService tenant;
    private final SubscriptionService subscriptions;
    private final TwoFactorService twoFactor;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuditService audit, TenantService tenant, SubscriptionService subscriptions, TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.tenant = tenant;
        this.subscriptions = subscriptions;
    }

    /** Self-service registration: creates a STORE_OWNER and a new isolated store. Subscription is required before use. */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        az.borcdefteri.common.Passwords.requireBcryptLength(req.password());
        if (userExists(email)) {
            throw ApiException.conflict("EMAIL_EXISTS", "Bu e-poçt ünvanı artıq qeydiyyatdadır");
        }
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO users (id, email, password_hash, full_name, phone, platform_role)
                VALUES (:id, :email, :hash, :name, :phone, 'USER')
                """)
                .param("id", userId).param("email", email)
                .param("hash", passwordEncoder.encode(req.password()))
                .param("name", req.fullName().trim())
                .param("phone", blankToNull(req.phone()))
                .update();

        jdbc.sql("INSERT INTO stores (id, name, phone, address, status) VALUES (:id, :name, :phone, :address, 'ACTIVE')")
                .param("id", storeId).param("name", req.storeName().trim())
                .param("phone", blankToNull(req.storePhone()))
                .param("address", blankToNull(req.storeAddress()))
                .update();

        jdbc.sql("INSERT INTO store_members (id, store_id, user_id, role, active) VALUES (:id, :storeId, :userId, 'STORE_OWNER', TRUE)")
                .param("id", memberId).param("storeId", storeId).param("userId", userId)
                .update();

        audit.log(storeId, userId, "STORE_REGISTERED", "STORE", storeId.toString(), "Mağaza qeydiyyatı: " + req.storeName());
        return new AuthResponse(jwtService.issue(userId, "USER"), jwtService.expiresInSeconds(), me(userId));
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        String email = normalizeEmail(req.email());
        record Creds(UUID id, String hash, boolean enabled, String platformRole) {}
        Creds creds = jdbc.sql("SELECT id, password_hash, enabled, platform_role FROM users WHERE email = :email")
                .param("email", email)
                .query((rs, n) -> new Creds(rs.getObject("id", UUID.class), rs.getString("password_hash"),
                        rs.getBoolean("enabled"), rs.getString("platform_role")))
                .optional()
                .orElse(null);
        boolean usable = az.borcdefteri.common.Passwords.fitsBcrypt(req.password());
        // Unknown accounts still run one BCrypt check so response time does not reveal which e-mails exist.
        boolean ok = usable && passwordEncoder.matches(req.password(), creds != null ? creds.hash() : dummyHash());
        if (creds == null || !ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-poçt və ya şifrə yanlışdır");
        }
        if (!creds.enabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Hesab deaktiv edilib");
        }
        if (twoFactor.required(creds.id())) {
            twoFactor.challenge(creds.id(), email); // throws TWO_FACTOR_REQUIRED with the challenge id
        }
        return issue(creds.id(), creds.platformRole());
    }

    /** Second step of two-step login: the code from the e-mail. */
    public AuthResponse verifyLoginCode(UUID challengeId, String code) {
        UUID userId = twoFactor.verify(challengeId, code);
        String role = jdbc.sql("SELECT platform_role FROM users WHERE id = :id AND enabled").param("id", userId)
                .query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Hesab deaktiv edilib"));
        return issue(userId, role);
    }

    private AuthResponse issue(UUID userId, String platformRole) {
        UUID storeId = jdbc.sql("SELECT store_id FROM store_members WHERE user_id = :id")
                .param("id", userId).query(UUID.class).optional().orElse(null);
        audit.log(storeId, userId, "LOGIN", "USER", userId.toString(), "Sistemə giriş");
        return new AuthResponse(jwtService.issue(userId, platformRole), jwtService.expiresInSeconds(), me(userId));
    }

    public MeResponse me(UUID userId) {
        record Base(String email, String fullName, String platformRole, String phone, java.time.OffsetDateTime avatarAt,
                    boolean mustChangePassword) {}
        Base base = jdbc.sql("""
                SELECT email, full_name, platform_role, phone, avatar_updated_at, must_change_password
                FROM users WHERE id = :id
                """)
                .param("id", userId)
                .query((rs, n) -> new Base(rs.getString("email"), rs.getString("full_name"), rs.getString("platform_role"),
                        rs.getString("phone"), rs.getObject("avatar_updated_at", java.time.OffsetDateTime.class),
                        rs.getBoolean("must_change_password")))
                .single();

        CurrentMember member = null;
        SubscriptionSummary subscription = null;
        try {
            member = memberOf(userId);
        } catch (ApiException | EmptyResultDataAccessException ignored) {
            // no store / suspended store / platform admin: profile is still returned so the UI can explain why
        }
        if (member != null) {
            subscription = subscriptions.summaryFor(member.storeId());
        }
        return new MeResponse(
                userId,
                base.email(),
                base.fullName(),
                base.platformRole(),
                member == null ? null : member.role(),
                member == null ? null : member.storeId(),
                member == null ? null : member.storeName(),
                member == null ? null : member.permissions().stream().sorted().toList(),
                subscription,
                base.phone(),
                base.avatarAt() != null,
                base.avatarAt() == null ? null : base.avatarAt().toInstant().toEpochMilli(),
                base.mustChangePassword(),
                member == null ? null : jdbc.sql("SELECT job_title FROM store_members WHERE user_id = :u AND store_id = :s")
                        .param("u", userId).param("s", member.storeId()).query(String.class).optional().orElse(null),
                member == null ? java.util.List.<String>of() : tenant.lockedModulesOf(member.storeId()).stream().sorted().toList()
        );
    }

    /** Lightweight membership lookup that does not require an authenticated request context. */
    private CurrentMember memberOf(UUID userId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT m.id, s.id AS store_id, s.name, s.status, m.role, m.active
                FROM store_members m JOIN stores s ON s.id = m.store_id
                WHERE m.user_id = :userId
                """).param("userId", userId).query().singleRow();
        if (!"ACTIVE".equals(row.get("status")) || !Boolean.TRUE.equals(row.get("active"))) {
            throw ApiException.forbidden("STORE_SUSPENDED", "Mağaza fəaliyyəti dayandırılıb");
        }
        var perms = jdbc.sql("SELECT permission FROM member_permissions WHERE member_id = :m")
                .param("m", row.get("id")).query(String.class).list();
        return new CurrentMember(userId, (UUID) row.get("id"), (UUID) row.get("store_id"),
                (String) row.get("name"), (String) row.get("role"), null, new java.util.HashSet<>(perms));
    }

    private volatile String dummyHash;

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
        }
        return dummyHash;
    }

    private boolean userExists(String email) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE email = :email").param("email", email)
                .query(Long.class).single() > 0;
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
