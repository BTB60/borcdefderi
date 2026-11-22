package az.borcdefteri.ads;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.subscriptions.ReceiptStorage;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reklam hekayələri: picture stories the platform admin puts on top of every store's phone home screen
 * (Instagram-like circles; tapping opens the picture full screen with a button to a link or WhatsApp).
 * A story is "live" while it is active, inside its dates and has a picture. Views and button taps are counted
 * so the admin can show an advertiser how the story did.
 */
@Service
public class AdStoryService {

    /** What the phone needs. */
    public record StoryView(UUID id, String title, String subtitle, String buttonLabel, String linkUrl, String whatsapp,
                            long imageVersion) {}

    /** What the admin panel needs. */
    public record AdminView(UUID id, String title, String subtitle, String buttonLabel, String linkUrl, String whatsapp,
                            boolean hasImage, long imageVersion, boolean active, LocalDate startsOn, LocalDate endsOn,
                            int sortOrder, int views, int clicks, boolean live, OffsetDateTime createdAt) {}

    public record Input(String title, String subtitle, String buttonLabel, String linkUrl, String whatsapp,
                        Boolean active, LocalDate startsOn, LocalDate endsOn, Integer sortOrder) {}

    public record Image(byte[] content, String contentType) {}

    private static final String LIVE = "active AND image IS NOT NULL AND (starts_on IS NULL OR starts_on <= :today)"
            + " AND (ends_on IS NULL OR ends_on >= :today)";

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final ReceiptStorage files;
    private final AuditService audit;

    public AdStoryService(JdbcClient jdbc, TenantService tenant, ReceiptStorage files, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.files = files;
        this.audit = audit;
    }

    // ----- every signed-in user -----

    public List<StoryView> live() {
        tenant.currentPrincipal();
        return jdbc.sql("SELECT id, title, subtitle, button_label, link_url, whatsapp, image_version FROM ad_stories WHERE "
                        + LIVE + " ORDER BY sort_order, created_at DESC LIMIT 20")
                .param("today", tenant.today())
                .query((rs, n) -> new StoryView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("subtitle"),
                        rs.getString("button_label"), rs.getString("link_url"), rs.getString("whatsapp"),
                        rs.getLong("image_version"))).list();
    }

    /** The picture of a live story (the admin also sees pictures of stories that are not live). */
    public Image image(UUID id) {
        tenant.currentPrincipal();
        String where = tenant.isSuperAdmin() ? "image IS NOT NULL" : LIVE;
        return jdbc.sql("SELECT image, image_type FROM ad_stories WHERE id = :id AND " + where)
                .param("id", id).param("today", tenant.today())
                .query((rs, n) -> new Image(rs.getBytes("image"), rs.getString("image_type"))).optional()
                .orElseThrow(() -> ApiException.notFound("Şəkil tapılmadı"));
    }

    /** One more view (the phone sends it once when the story is opened). Unknown or ended stories are ignored. */
    public void view(UUID id) {
        count(id, "views");
    }

    /** One more tap on the story's button. */
    public void click(UUID id) {
        count(id, "clicks");
    }

    private void count(UUID id, String column) {
        tenant.currentPrincipal();
        jdbc.sql("UPDATE ad_stories SET " + column + " = " + column + " + 1 WHERE id = :id AND " + LIVE)
                .param("id", id).param("today", tenant.today()).update();
    }

    // ----- platform admin -----

    public List<AdminView> all() {
        tenant.requireSuperAdmin();
        return jdbc.sql("SELECT *, (" + LIVE + ") AS live FROM ad_stories ORDER BY sort_order, created_at DESC LIMIT 200")
                .param("today", tenant.today()).query(this::admin).list();
    }

    @Transactional
    public AdminView create(Input in) {
        tenant.requireSuperAdmin();
        Clean c = clean(in);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ad_stories (id, title, subtitle, button_label, link_url, whatsapp, active, starts_on, ends_on,
                                        sort_order, created_by)
                VALUES (:id, :title, :sub, :btn, :link, :wa, :active, :starts, :ends, :sort, :by)
                """)
                .param("id", id).param("title", c.title).param("sub", c.subtitle).param("btn", c.buttonLabel)
                .param("link", c.linkUrl).param("wa", c.whatsapp).param("active", c.active)
                .param("starts", in.startsOn()).param("ends", in.endsOn()).param("sort", c.sortOrder)
                .param("by", tenant.currentUserId()).update();
        audit.log(null, tenant.currentUserId(), "AD_STORY_CREATED", "AD_STORY", id.toString(), c.title);
        return get(id);
    }

    @Transactional
    public AdminView update(UUID id, Input in) {
        tenant.requireSuperAdmin();
        get(id);
        Clean c = clean(in);
        jdbc.sql("""
                UPDATE ad_stories SET title = :title, subtitle = :sub, button_label = :btn, link_url = :link, whatsapp = :wa,
                       active = :active, starts_on = :starts, ends_on = :ends, sort_order = :sort
                WHERE id = :id
                """)
                .param("title", c.title).param("sub", c.subtitle).param("btn", c.buttonLabel).param("link", c.linkUrl)
                .param("wa", c.whatsapp).param("active", c.active).param("starts", in.startsOn())
                .param("ends", in.endsOn()).param("sort", c.sortOrder).param("id", id).update();
        audit.log(null, tenant.currentUserId(), "AD_STORY_UPDATED", "AD_STORY", id.toString(),
                c.title + (c.active ? "" : " (söndürüldü)"));
        return get(id);
    }

    @Transactional
    public AdminView setImage(UUID id, MultipartFile file) {
        tenant.requireSuperAdmin();
        AdminView s = get(id);
        ReceiptStorage.Stored stored = files.store(file);
        if (!stored.contentType().startsWith("image/")) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Yalnız PNG və ya JPG şəkil");
        }
        jdbc.sql("UPDATE ad_stories SET image = :i, image_type = :t, image_version = image_version + 1 WHERE id = :id")
                .param("i", stored.content()).param("t", stored.contentType()).param("id", id).update();
        audit.log(null, tenant.currentUserId(), "AD_STORY_IMAGE", "AD_STORY", id.toString(), s.title());
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        tenant.requireSuperAdmin();
        AdminView s = get(id);
        jdbc.sql("DELETE FROM ad_stories WHERE id = :id").param("id", id).update();
        audit.log(null, tenant.currentUserId(), "AD_STORY_DELETED", "AD_STORY", id.toString(), s.title());
    }

    private AdminView get(UUID id) {
        return jdbc.sql("SELECT *, (" + LIVE + ") AS live FROM ad_stories WHERE id = :id")
                .param("id", id).param("today", tenant.today()).query(this::admin).optional()
                .orElseThrow(() -> ApiException.notFound("Reklam tapılmadı"));
    }

    private record Clean(String title, String subtitle, String buttonLabel, String linkUrl, String whatsapp,
                         boolean active, int sortOrder) {}

    private static Clean clean(Input in) {
        String title = trim(in.title());
        if (title == null || title.length() > 80) {
            throw ApiException.badRequest("INVALID_TITLE", "Başlıq 1-80 simvol olmalıdır");
        }
        String subtitle = trim(in.subtitle());
        if (subtitle != null && subtitle.length() > 160) {
            throw ApiException.badRequest("INVALID_SUBTITLE", "Alt mətn ən çox 160 simvol ola bilər");
        }
        String link = trim(in.linkUrl());
        if (link != null && (link.length() > 500 || !link.matches("(?i)https?://\\S+"))) {
            throw ApiException.badRequest("INVALID_LINK", "Link https:// ilə başlamalıdır");
        }
        String wa = trim(in.whatsapp());
        if (wa != null) {
            wa = wa.replaceAll("[\\s()-]", "");
            if (!wa.matches("\\+?\\d{7,15}")) {
                throw ApiException.badRequest("INVALID_WHATSAPP", "WhatsApp nömrəsi düzgün deyil (məs. +994501234567)");
            }
        }
        String button = trim(in.buttonLabel());
        if (button != null && button.length() > 30) {
            throw ApiException.badRequest("INVALID_BUTTON", "Düymə mətni ən çox 30 simvol ola bilər");
        }
        if (button == null && (link != null || wa != null)) button = wa != null && link == null ? "WhatsApp-da yaz" : "Ətraflı";
        if (in.startsOn() != null && in.endsOn() != null && in.endsOn().isBefore(in.startsOn())) {
            throw ApiException.badRequest("INVALID_DATES", "Bitmə tarixi başlama tarixindən əvvəl ola bilməz");
        }
        int sort = in.sortOrder() == null ? 0 : Math.max(-1000, Math.min(1000, in.sortOrder()));
        return new Clean(title, subtitle, button, link, wa, in.active() == null || in.active(), sort);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private AdminView admin(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AdminView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("subtitle"),
                rs.getString("button_label"), rs.getString("link_url"), rs.getString("whatsapp"),
                rs.getString("image_type") != null, rs.getLong("image_version"), rs.getBoolean("active"),
                rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
                rs.getInt("sort_order"), rs.getInt("views"), rs.getInt("clicks"), rs.getBoolean("live"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
