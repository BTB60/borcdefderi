package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.subscriptions.ReceiptStorage;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Mağaza reklamları: the store's own promo posts — a product or an offer with an optional old / new price, a badge
 * ("-20%", "YENİ"), a colour and a picture. Customers see the live ones in HesabPro Kart under the store's card.
 */
@Service
@RestController
public class StorePromoService {

    public record PromoRequest(
            @NotBlank(message = "Başlığı yazın") @Size(max = 80, message = "Başlıq ən çox 80 simvol") String title,
            @Size(max = 200, message = "Mətn ən çox 200 simvol") String subtitle,
            @Size(max = 20, message = "Nişan ən çox 20 simvol") String badge,
            @DecimalMin(value = "0", message = "Qiymət mənfi ola bilməz") @Digits(integer = 12, fraction = 2) BigDecimal oldPrice,
            @DecimalMin(value = "0", message = "Qiymət mənfi ola bilməz") @Digits(integer = 12, fraction = 2) BigDecimal newPrice,
            @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "Rəng #RRGGBB olmalıdır") String color,
            Boolean active,
            LocalDate startsOn,
            LocalDate endsOn,
            Integer sortOrder) {}

    public record Promo(UUID id, String title, String subtitle, String badge, BigDecimal oldPrice, BigDecimal newPrice,
                        String color, boolean hasImage, long imageVersion, boolean active, LocalDate startsOn, LocalDate endsOn,
                        int sortOrder, int views, boolean live, OffsetDateTime createdAt) {}

    /** What the customers' app shows: the store's name with each live post. */
    public record FeedItem(UUID id, String storeName, String title, String subtitle, String badge, BigDecimal oldPrice,
                           BigDecimal newPrice, String color, boolean hasImage, long imageVersion, LocalDate endsOn) {}

    private static final int MAX_PER_STORE = 30;
    private static final String LIVE = "p.active AND (p.starts_on IS NULL OR p.starts_on <= :today) AND (p.ends_on IS NULL OR p.ends_on >= :today)";

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final ReceiptStorage files;
    private final AuditService audit;

    public StorePromoService(JdbcClient jdbc, TenantService tenant, ReceiptStorage files, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.files = files;
        this.audit = audit;
    }

    private CurrentMember requireAccess() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.isOwner() && !m.has("SALES")) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return m;
    }

    // ---------- the store ----------

    @GetMapping("/api/promos")
    public List<Promo> list() {
        CurrentMember m = requireAccess();
        return jdbc.sql(SELECT + " WHERE p.store_id = :s ORDER BY p.sort_order, p.created_at DESC")
                .param("s", m.storeId()).param("today", tenant.today()).query((rs, n) -> map(rs)).list();
    }

    @PostMapping("/api/promos")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Promo create(@Valid @RequestBody PromoRequest req) {
        CurrentMember m = requireAccess();
        validate(req);
        long count = jdbc.sql("SELECT COUNT(*) FROM store_promos WHERE store_id = :s").param("s", m.storeId()).query(Long.class).single();
        if (count >= MAX_PER_STORE) {
            throw ApiException.conflict("PROMO_LIMIT", "Ən çox " + MAX_PER_STORE + " reklam ola bilər. Köhnələrini silin");
        }
        UUID id = UUID.randomUUID();
        bind(jdbc.sql("""
                INSERT INTO store_promos (id, store_id, title, subtitle, badge, old_price, new_price, color, active, starts_on, ends_on,
                                          sort_order, created_by)
                VALUES (:id, :s, :t, :st, :b, :op, :np, :c, :a, :from, :to, :o, :by)""")
                .param("id", id).param("s", m.storeId()).param("by", m.userId()).param("a", !Boolean.FALSE.equals(req.active())), req)
                .update();
        audit.log(m.storeId(), m.userId(), "PROMO_CREATED", "PROMO", id.toString(), req.title());
        return get(m, id);
    }

    @PutMapping("/api/promos/{id}")
    @Transactional
    public Promo update(@PathVariable UUID id, @Valid @RequestBody PromoRequest req) {
        CurrentMember m = requireAccess();
        Promo cur = get(m, id);
        validate(req);
        bind(jdbc.sql("""
                UPDATE store_promos SET title = :t, subtitle = :st, badge = :b, old_price = :op, new_price = :np, color = :c,
                       active = :a, starts_on = :from, ends_on = :to, sort_order = :o
                WHERE id = :id AND store_id = :s""")
                .param("id", id).param("s", m.storeId()).param("a", req.active() == null ? cur.active() : req.active()), req)
                .update();
        audit.log(m.storeId(), m.userId(), "PROMO_UPDATED", "PROMO", id.toString(), req.title());
        return get(m, id);
    }

    @DeleteMapping("/api/promos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        CurrentMember m = requireAccess();
        Promo cur = get(m, id);
        jdbc.sql("DELETE FROM store_promos WHERE id = :id AND store_id = :s").param("id", id).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "PROMO_DELETED", "PROMO", id.toString(), cur.title());
    }

    @PostMapping(value = "/api/promos/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Promo setImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        CurrentMember m = requireAccess();
        get(m, id);
        ReceiptStorage.Stored stored = files.store(file);
        if (!stored.contentType().startsWith("image/")) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Yalnız PNG və ya JPG şəkil");
        }
        jdbc.sql("UPDATE store_promos SET image = :i, image_type = :t, image_version = image_version + 1 WHERE id = :id AND store_id = :s")
                .param("i", stored.content()).param("t", stored.contentType()).param("id", id).param("s", m.storeId()).update();
        return get(m, id);
    }

    // ---------- the customers' app (public by id: the pictures are adverts) ----------

    /** The picture changes only with a new image_version (the app adds ?v=), so it may be cached. */
    @GetMapping("/api/public/promos/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        record Img(byte[] content, String type) {}
        Img img = jdbc.sql("SELECT image, image_type FROM store_promos WHERE id = :id AND image IS NOT NULL")
                .param("id", id).query((rs, n) -> new Img(rs.getBytes("image"), rs.getString("image_type"))).optional()
                .orElseThrow(() -> ApiException.notFound("Şəkil tapılmadı"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.type()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(img.content());
    }

    /** Live posts of the stores where the phone (last 9 digits) holds an active bonus card. */
    public List<FeedItem> feed(String last9) {
        List<FeedItem> items = jdbc.sql("""
                SELECT p.id, st.name AS store_name, p.title, p.subtitle, p.badge, p.old_price, p.new_price, p.color,
                       p.image IS NOT NULL AS has_image, p.image_version, p.ends_on
                FROM store_promos p JOIN stores st ON st.id = p.store_id
                WHERE st.status = 'ACTIVE' AND\s""" + LIVE + """
                  AND EXISTS (SELECT 1 FROM loyalty_cards lc WHERE lc.store_id = p.store_id AND lc.active
                              AND right(regexp_replace(lc.phone, '[^0-9]', '', 'g'), 9) = :p)
                ORDER BY p.sort_order, p.created_at DESC LIMIT 40""")
                .param("p", last9).param("today", tenant.today())
                .query((rs, n) -> new FeedItem(rs.getObject("id", UUID.class), rs.getString("store_name"), rs.getString("title"),
                        rs.getString("subtitle"), rs.getString("badge"), money(rs.getBigDecimal("old_price")),
                        money(rs.getBigDecimal("new_price")), rs.getString("color"), rs.getBoolean("has_image"),
                        rs.getLong("image_version"), rs.getObject("ends_on", LocalDate.class))).list();
        if (!items.isEmpty()) {
            jdbc.sql("UPDATE store_promos SET views = views + 1 WHERE id IN (:ids)")
                    .param("ids", items.stream().map(FeedItem::id).toList()).update();
        }
        return items;
    }

    // ---------- helpers ----------

    private static void validate(PromoRequest r) {
        if (r.startsOn() != null && r.endsOn() != null && r.endsOn().isBefore(r.startsOn())) {
            throw ApiException.badRequest("INVALID_DATES", "Bitmə tarixi başlama tarixindən əvvəl ola bilməz");
        }
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, PromoRequest r) {
        return spec.param("t", r.title().trim()).param("st", blank(r.subtitle())).param("b", blank(r.badge()))
                .param("op", r.oldPrice() == null ? null : Money.of(r.oldPrice()))
                .param("np", r.newPrice() == null ? null : Money.of(r.newPrice()))
                .param("c", blank(r.color())).param("from", r.startsOn()).param("to", r.endsOn())
                .param("o", r.sortOrder() == null ? 0 : r.sortOrder());
    }

    private Promo get(CurrentMember m, UUID id) {
        return jdbc.sql(SELECT + " WHERE p.store_id = :s AND p.id = :id").param("s", m.storeId()).param("id", id)
                .param("today", tenant.today()).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Reklam tapılmadı"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : Money.of(v);
    }

    private static final String SELECT = """
            SELECT p.id, p.title, p.subtitle, p.badge, p.old_price, p.new_price, p.color, p.image IS NOT NULL AS has_image,
                   p.image_version, p.active, p.starts_on, p.ends_on, p.sort_order, p.views, p.created_at,
                   (""" + LIVE + """
            ) AS live
            FROM store_promos p""";

    private static Promo map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Promo(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("subtitle"), rs.getString("badge"),
                money(rs.getBigDecimal("old_price")), money(rs.getBigDecimal("new_price")), rs.getString("color"),
                rs.getBoolean("has_image"), rs.getLong("image_version"), rs.getBoolean("active"),
                rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class), rs.getInt("sort_order"),
                rs.getInt("views"), rs.getBoolean("live"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
