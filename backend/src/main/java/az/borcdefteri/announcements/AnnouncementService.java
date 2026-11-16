package az.borcdefteri.announcements;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.notifications.NotificationService;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Announcements from the platform admin to every store: shown as a banner in the web app and on the phone until
 * switched off (or until the end date), and sent once as a notification to each store.
 */
@Service
public class AnnouncementService {

    public record AnnouncementView(UUID id, String title, String body, String level, boolean active, LocalDate endsOn,
                                   OffsetDateTime createdAt) {}

    public record AnnouncementInput(String title, String body, String level, Boolean active, LocalDate endsOn,
                                    Boolean sendNotification) {}

    private static final Set<String> LEVELS = Set.of("INFO", "WARNING", "SUCCESS");

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final NotificationService notifications;

    public AnnouncementService(JdbcClient jdbc, TenantService tenant, AuditService audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** Active announcements, for every signed-in user. */
    public List<AnnouncementView> current() {
        return jdbc.sql("""
                SELECT * FROM announcements WHERE active AND (ends_on IS NULL OR ends_on >= :today)
                ORDER BY created_at DESC LIMIT 5
                """).param("today", tenant.today()).query(this::map).list();
    }

    public List<AnnouncementView> all() {
        tenant.requireSuperAdmin();
        return jdbc.sql("SELECT * FROM announcements ORDER BY created_at DESC LIMIT 200").query(this::map).list();
    }

    @Transactional
    public AnnouncementView create(AnnouncementInput in) {
        tenant.requireSuperAdmin();
        validate(in);
        UUID id = UUID.randomUUID();
        boolean active = in.active() == null || in.active();
        jdbc.sql("""
                INSERT INTO announcements (id, title, body, level, active, ends_on, created_by)
                VALUES (:id, :title, :body, :level, :active, :ends, :by)
                """)
                .param("id", id).param("title", in.title().trim()).param("body", in.body().trim())
                .param("level", level(in)).param("active", active)
                .param("ends", in.endsOn()).param("by", tenant.currentUserId()).update();
        int sent = 0;
        if (active && !Boolean.FALSE.equals(in.sendNotification())) {
            String message = in.body().trim();
            if (message.length() > 1000) message = message.substring(0, 997) + "...";
            List<UUID> stores = jdbc.sql("SELECT id FROM stores WHERE status = 'ACTIVE'").query(UUID.class).list();
            for (UUID store : stores) {
                notifications.create(store, "ANNOUNCEMENT", in.title().trim(), message);
            }
            sent = stores.size();
        }
        audit.log(null, tenant.currentUserId(), "ANNOUNCEMENT_CREATED", "ANNOUNCEMENT", id.toString(),
                in.title().trim() + (sent > 0 ? " (" + sent + " mağazaya bildiriş)" : ""));
        return get(id);
    }

    @Transactional
    public AnnouncementView update(UUID id, AnnouncementInput in) {
        tenant.requireSuperAdmin();
        get(id);
        validate(in);
        jdbc.sql("""
                UPDATE announcements SET title = :title, body = :body, level = :level, active = :active, ends_on = :ends
                WHERE id = :id
                """)
                .param("title", in.title().trim()).param("body", in.body().trim()).param("level", level(in))
                .param("active", in.active() == null || in.active()).param("ends", in.endsOn()).param("id", id).update();
        audit.log(null, tenant.currentUserId(), "ANNOUNCEMENT_UPDATED", "ANNOUNCEMENT", id.toString(),
                in.title().trim() + (Boolean.FALSE.equals(in.active()) ? " (söndürüldü)" : ""));
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        tenant.requireSuperAdmin();
        AnnouncementView a = get(id);
        jdbc.sql("DELETE FROM announcements WHERE id = :id").param("id", id).update();
        audit.log(null, tenant.currentUserId(), "ANNOUNCEMENT_DELETED", "ANNOUNCEMENT", id.toString(), a.title());
    }

    private AnnouncementView get(UUID id) {
        return jdbc.sql("SELECT * FROM announcements WHERE id = :id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Elan tapılmadı"));
    }

    private static void validate(AnnouncementInput in) {
        if (in.title() == null || in.title().isBlank() || in.title().trim().length() > 150) {
            throw ApiException.badRequest("INVALID_TITLE", "Başlıq 1-150 simvol olmalıdır");
        }
        if (in.body() == null || in.body().isBlank() || in.body().trim().length() > 2000) {
            throw ApiException.badRequest("INVALID_BODY", "Mətn 1-2000 simvol olmalıdır");
        }
        if (in.level() != null && !LEVELS.contains(in.level())) {
            throw ApiException.badRequest("INVALID_LEVEL", "Növ düzgün deyil");
        }
    }

    private static String level(AnnouncementInput in) {
        return in.level() == null ? "INFO" : in.level();
    }

    private AnnouncementView map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AnnouncementView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
                rs.getString("level"), rs.getBoolean("active"), rs.getObject("ends_on", LocalDate.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
