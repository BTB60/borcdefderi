package az.borcdefteri.employees;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class EmployeeService {

    public record EmployeeView(UUID memberId, UUID userId, String email, String fullName, String phone,
                               boolean active, List<String> permissions) {}

    private static final String SELECT_EMPLOYEES = """
            SELECT m.id AS member_id, u.id AS user_id, u.email, u.full_name, u.phone, m.active,
                   COALESCE((SELECT string_agg(p.permission, ',' ORDER BY p.permission)
                             FROM member_permissions p WHERE p.member_id = m.id), '') AS permissions
            FROM store_members m JOIN users u ON u.id = m.user_id
            WHERE m.store_id = :s AND m.role = 'EMPLOYEE'
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public EmployeeService(JdbcClient jdbc, TenantService tenant, PasswordEncoder passwordEncoder, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public List<EmployeeView> list() {
        return list(tenant.requireOwner());
    }

    @Transactional
    public EmployeeView create(String email, String password, String fullName, String phone, List<String> permissions) {
        CurrentMember owner = tenant.requireOwner();
        Set<String> perms = validatePermissions(permissions);
        az.borcdefteri.common.Passwords.requireBcryptLength(password);
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM users WHERE email = :e").param("e", normalized)
                .query(Long.class).single() > 0;
        if (exists) {
            throw ApiException.conflict("EMAIL_EXISTS", "Bu e-poçt ünvanı artıq qeydiyyatdadır");
        }
        UUID userId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO users (id, email, password_hash, full_name, phone, platform_role)
                VALUES (:id, :email, :hash, :name, :phone, 'USER')
                """).param("id", userId).param("email", normalized)
                .param("hash", passwordEncoder.encode(password)).param("name", fullName.trim())
                .param("phone", blankToNull(phone)).update();
        jdbc.sql("INSERT INTO store_members (id, store_id, user_id, role, active) VALUES (:id, :s, :u, 'EMPLOYEE', TRUE)")
                .param("id", memberId).param("s", owner.storeId()).param("u", userId).update();
        insertPermissions(memberId, perms);
        audit.log(owner.storeId(), owner.userId(), "EMPLOYEE_CREATED", "EMPLOYEE", memberId.toString(),
                normalized + " icazələr: " + perms);
        return find(owner, memberId);
    }

    @Transactional
    public EmployeeView update(UUID memberId, List<String> permissions, boolean active) {
        CurrentMember owner = tenant.requireOwner();
        find(owner, memberId); // store-scoped existence check (404 for other stores' members)
        Set<String> perms = validatePermissions(permissions);
        jdbc.sql("UPDATE store_members SET active = :active WHERE id = :id AND store_id = :s")
                .param("active", active).param("id", memberId).param("s", owner.storeId()).update();
        jdbc.sql("DELETE FROM member_permissions WHERE member_id = :id").param("id", memberId).update();
        insertPermissions(memberId, perms);
        audit.log(owner.storeId(), owner.userId(), "EMPLOYEE_UPDATED", "EMPLOYEE", memberId.toString(),
                "icazələr: " + perms + ", aktiv: " + active);
        return find(owner, memberId);
    }

    private EmployeeView find(CurrentMember owner, UUID memberId) {
        return list(owner).stream().filter(e -> e.memberId().equals(memberId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("İşçi tapılmadı"));
    }

    private List<EmployeeView> list(CurrentMember owner) {
        return jdbc.sql(SELECT_EMPLOYEES + " ORDER BY u.full_name")
                .param("s", owner.storeId())
                .query((rs, n) -> new EmployeeView(
                        rs.getObject("member_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("phone"),
                        rs.getBoolean("active"),
                        splitPermissions(rs.getString("permissions"))))
                .list();
    }

    private void insertPermissions(UUID memberId, Set<String> perms) {
        for (String p : perms) {
            jdbc.sql("INSERT INTO member_permissions (member_id, permission) VALUES (:m, :p)")
                    .param("m", memberId).param("p", p).update();
        }
    }

    static Set<String> validatePermissions(List<String> permissions) {
        Set<String> allowed = Set.copyOf(TenantService.allPermissions());
        for (String p : permissions) {
            if (p == null || !allowed.contains(p)) {
                throw ApiException.badRequest("INVALID_PERMISSION", "Naməlum icazə: " + p);
            }
        }
        return new java.util.TreeSet<>(permissions);
    }

    private static List<String> splitPermissions(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
