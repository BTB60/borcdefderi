package az.borcdefteri.tenant;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.config.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Single place that resolves the caller's tenant. The store is ALWAYS derived from the
 * authenticated user's membership in the database; no store id is ever read from the request.
 */
@Service
public class TenantService {

    public static final String OWNER = "STORE_OWNER";
    public static final String EMPLOYEE = "EMPLOYEE";

    public static final String PERM_CUSTOMER_DEBTS = "CUSTOMER_DEBTS";
    public static final String PERM_COMPANY_RECEIVABLES = "COMPANY_RECEIVABLES";
    public static final String PERM_COMPANY_PAYABLES = "COMPANY_PAYABLES";
    public static final String PERM_REPORTS = "REPORTS";

    private final JdbcClient jdbc;
    private final Clock clock;

    public TenantService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public AuthenticatedUser currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Giriş tələb olunur");
        }
        return user;
    }

    public UUID currentUserId() {
        return currentPrincipal().userId();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Membership + store + user must all be active. Does NOT check subscription. */
    public CurrentMember requireMember() {
        AuthenticatedUser principal = currentPrincipal();
        record Row(UUID memberId, UUID storeId, String storeName, String storeStatus, String role,
                   boolean memberActive, boolean userEnabled, String platformRole, String permissions,
                   boolean mustChangePassword, String planModules) {}
        Row row = jdbc.sql("""
                SELECT m.id AS member_id, s.id AS store_id, s.name AS store_name, s.status AS store_status,
                       m.role, m.active AS member_active, u.enabled AS user_enabled, u.platform_role,
                       u.must_change_password,
                       COALESCE((SELECT string_agg(p.permission, ',') FROM member_permissions p
                                 WHERE p.member_id = m.id), '') AS permissions,
                       (SELECT pl.modules FROM subscriptions sub JOIN subscription_plans pl ON pl.id = sub.plan_id
                        WHERE sub.store_id = s.id AND sub.status = 'ACTIVE' AND sub.end_date >= :today
                        ORDER BY sub.end_date DESC LIMIT 1) AS plan_modules
                FROM users u
                LEFT JOIN store_members m ON m.user_id = u.id
                LEFT JOIN stores s ON s.id = m.store_id
                WHERE u.id = :userId
                """)
                .param("userId", principal.userId())
                .param("today", today())
                .query((rs, n) -> new Row(
                        rs.getObject("member_id", UUID.class),
                        rs.getObject("store_id", UUID.class),
                        rs.getString("store_name"),
                        rs.getString("store_status"),
                        rs.getString("role"),
                        rs.getBoolean("member_active"),
                        rs.getBoolean("user_enabled"),
                        rs.getString("platform_role"),
                        rs.getString("permissions"),
                        rs.getBoolean("must_change_password"),
                        rs.getString("plan_modules")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Hesab tapılmadı"));

        if (!row.userEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Hesab deaktiv edilib");
        }
        if (row.mustChangePassword()) {
            // Logged in with a temporary password from the admin / store owner: only the settings page works.
            throw ApiException.forbidden("PASSWORD_CHANGE_REQUIRED", "Davam etmək üçün yeni şifrə təyin edin");
        }
        if (row.memberId() == null) {
            throw ApiException.forbidden("NO_STORE", "Bu hesab heç bir mağazaya bağlı deyil");
        }
        if (!row.memberActive()) {
            throw ApiException.forbidden("MEMBER_DISABLED", "Mağaza üzrə girişiniz deaktiv edilib");
        }
        if (!"ACTIVE".equals(row.storeStatus())) {
            throw ApiException.forbidden("STORE_SUSPENDED", "Mağazanız dayandırılıb. Dəstək ilə əlaqə saxlayın");
        }
        Set<String> perms = new HashSet<>();
        if (row.permissions() != null && !row.permissions().isBlank()) {
            perms.addAll(Arrays.asList(row.permissions().split(",")));
        }
        Set<String> locked = lockedModules(row.planModules());
        if (EMPLOYEE.equals(row.role()) && locked.contains("EMPLOYEES")) {
            throw ApiException.forbidden("PLAN_MODULE", "Mağazanın paketində işçi hesabları yoxdur. Mağaza sahibinə müraciət edin");
        }
        return new CurrentMember(principal.userId(), row.memberId(), row.storeId(), row.storeName(),
                row.role(), row.platformRole(), perms, locked);
    }

    /** Member, plus an ACTIVE, non-expired subscription for the store. Used by all business modules. */
    public CurrentMember requireBusinessMember() {
        CurrentMember member = requireMember();
        if (!hasActiveSubscription(member.storeId())) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "SUBSCRIPTION_REQUIRED",
                    "Aktiv abunəlik yoxdur. Abunəliyi yeniləyin");
        }
        return member;
    }

    /** Business member that holds the given permission (owners hold all) and whose package opens the module. */
    public CurrentMember requirePermission(String permission) {
        CurrentMember member = requireBusinessMember();
        if (member.lockedModules().contains(permission)) {
            throw ApiException.forbidden("PLAN_MODULE", "Bu bölmə paketinizə daxil deyil. Paketi yüksəldin");
        }
        if (!member.has(permission)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return member;
    }

    /** Owner-only actions (employee management, store settings). */
    public CurrentMember requireOwner() {
        CurrentMember member = requireBusinessMember();
        if (!member.isOwner()) {
            throw ApiException.forbidden("OWNER_ONLY", "Yalnız mağaza sahibi bu əməliyyatı edə bilər");
        }
        return member;
    }

    public boolean hasActiveSubscription(UUID storeId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM subscriptions
                WHERE store_id = :storeId AND status = 'ACTIVE' AND end_date >= :today
                """)
                .param("storeId", storeId)
                .param("today", today())
                .query(Long.class)
                .single() > 0;
    }

    public boolean isSuperAdmin() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE id = :id AND platform_role = 'SUPER_ADMIN' AND enabled")
                .param("id", currentUserId())
                .query(Long.class).single() > 0;
    }

    /** The platform admin or a köməkçi admin (SUPPORT_ADMIN), enabled. */
    public boolean isPlatformAdmin() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE id = :id AND platform_role IN ('SUPER_ADMIN', 'SUPPORT_ADMIN') AND enabled")
                .param("id", currentUserId())
                .query(Long.class).single() > 0;
    }

    /** For the admin calls a köməkçi admin may also make (the URL rules list them). */
    public void requirePlatformAdmin() {
        if (!isPlatformAdmin()) {
            throw ApiException.forbidden("SUPER_ADMIN_ONLY", "Yalnız platforma administratoru bu əməliyyatı edə bilər");
        }
    }

    /** Platform-level check (defence in depth on top of the /api/admin/** URL rule). */
    public void requireSuperAdmin() {
        AuthenticatedUser principal = currentPrincipal();
        boolean admin = jdbc.sql("SELECT COUNT(*) FROM users WHERE id = :id AND platform_role = 'SUPER_ADMIN' AND enabled")
                .param("id", principal.userId())
                .query(Long.class).single() > 0;
        if (!admin) {
            throw ApiException.forbidden("SUPER_ADMIN_ONLY", "Yalnız platforma administratoru bu əməliyyatı edə bilər");
        }
    }

    /**
     * Sections a package may leave out: every menu section except the subscription page. The first eight are also
     * employee permissions; POCKET (cib pulu) and EMPLOYEES (işçilər) are package-only.
     */
    public static final List<String> PLAN_MODULES = List.of(
            "CUSTOMER_DEBTS", "COMPANY_RECEIVABLES", "COMPANY_PAYABLES", "REPORTS",
            "SALES", "INVENTORY", "CASH", "EXPENSES", "POCKET", "EMPLOYEES");

    /** Business member whose package includes the module; otherwise 403 PLAN_MODULE. */
    public CurrentMember requireModule(String module) {
        CurrentMember member = requireBusinessMember();
        requireModule(member, module);
        return member;
    }

    public static void requireModule(CurrentMember member, String module) {
        if (member.lockedModules().contains(module)) {
            throw ApiException.forbidden("PLAN_MODULE", "Bu bölmə paketinizə daxil deyil. Paketi yüksəldin");
        }
    }

    /** Paid modules missing from a package's module list (null list = everything open). */
    public static Set<String> lockedModules(String planModules) {
        if (planModules == null) {
            return Set.of();
        }
        Set<String> open = planModules.isBlank() ? Set.of() : Set.of(planModules.split(","));
        Set<String> locked = new HashSet<>();
        for (String mod : PLAN_MODULES) {
            if (!open.contains(mod)) {
                locked.add(mod);
            }
        }
        return locked;
    }

    /** Locked modules of a store's active package (for /auth/me). */
    public Set<String> lockedModulesOf(UUID storeId) {
        return lockedModules(jdbc.sql("""
                SELECT pl.modules FROM subscriptions sub JOIN subscription_plans pl ON pl.id = sub.plan_id
                WHERE sub.store_id = :s AND sub.status = 'ACTIVE' AND sub.end_date >= :today
                ORDER BY sub.end_date DESC LIMIT 1
                """).param("s", storeId).param("today", today()).query(String.class).optional().orElse(null));
    }

    public static List<String> allPermissions() {
        return List.of(PERM_CUSTOMER_DEBTS, PERM_COMPANY_RECEIVABLES, PERM_COMPANY_PAYABLES, PERM_REPORTS, "CASH", "EXPENSES", "INVENTORY", "SALES", "TILL_SETTINGS");
    }
}
