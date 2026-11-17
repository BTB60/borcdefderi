package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.notifications.NotificationService;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Onlayn kataloq və sifariş: the owner switches on a public page of the store's goods (pictures, prices, in stock);
 * customers put goods in a basket and send an order with name and phone. Orders appear in "Onlayn sifarişlər"
 * (and the bell); the cashier loads one into the till and sells it as usual.
 */
@Service
@RestController
public class CatalogService {

    public record CatalogSettings(boolean enabled, String slug, String note) {}

    public record CatalogUpdate(boolean enabled, @Size(max = 300) String note) {}

    public record PublicProduct(UUID id, String name, String category, String unit, BigDecimal price, BigDecimal oldPrice,
                                boolean inStock, boolean hasImage) {}

    public record PublicCatalog(String storeName, String phone, String address, String note, List<PublicProduct> products) {}

    public record OrderLine(@NotNull UUID productId,
                            @NotNull @DecimalMin("0.001") @DecimalMax("10000") BigDecimal quantity) {}

    public record OrderRequest(@NotBlank @Size(max = 100) String name,
                               @NotBlank @Pattern(regexp = "^[+0-9 ()-]{7,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
                               @Size(max = 300) String address, @Size(max = 500) String note,
                               @NotEmpty @Size(max = 50) List<@Valid OrderLine> items) {}

    public record OrderItem(UUID productId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

    public record Order(UUID id, String number, String customerName, String customerPhone, String address, String note,
                        BigDecimal total, String status, UUID saleId, OffsetDateTime createdAt, List<OrderItem> items) {}

    public record StatusUpdate(@NotNull @Pattern(regexp = "CONFIRMED|DONE|CANCELLED|NEW") String status, UUID saleId) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final NotificationService notifications;

    public CatalogService(JdbcClient jdbc, TenantService tenant, AuditService audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ---------- owner: switch on / off ----------

    @GetMapping("/api/catalog/settings")
    public CatalogSettings settings() {
        CurrentMember m = tenant.requireMember();
        return load(m.storeId());
    }

    @PutMapping("/api/catalog/settings")
    @Transactional
    public CatalogSettings update(@Valid @RequestBody CatalogUpdate in) {
        CurrentMember m = tenant.requireOwner();
        tenant.requirePermission("SALES");
        CatalogSettings cur = load(m.storeId());
        String slug = cur.slug() != null ? cur.slug() : newSlug();
        jdbc.sql("UPDATE stores SET catalog_enabled = :e, catalog_slug = :slug, catalog_note = :n WHERE id = :id")
                .param("e", in.enabled()).param("slug", slug).param("n", blank(in.note())).param("id", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), in.enabled() ? "CATALOG_ON" : "CATALOG_OFF", "STORE", m.storeId().toString(),
                in.enabled() ? "Onlayn kataloq açıldı" : "Onlayn kataloq bağlandı");
        return load(m.storeId());
    }

    private CatalogSettings load(UUID storeId) {
        return jdbc.sql("SELECT catalog_enabled, catalog_slug, catalog_note FROM stores WHERE id = :id").param("id", storeId)
                .query((rs, n) -> new CatalogSettings(rs.getBoolean("catalog_enabled"), rs.getString("catalog_slug"),
                        rs.getString("catalog_note"))).single();
    }

    private static String newSlug() {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(abc.charAt(RANDOM.nextInt(abc.length())));
        return b.toString();
    }

    // ---------- public ----------

    private UUID storeOf(String slug) {
        return jdbc.sql("""
                SELECT st.id FROM stores st WHERE st.catalog_slug = :slug AND st.catalog_enabled AND st.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM subscriptions s WHERE s.store_id = st.id AND s.status = 'ACTIVE' AND s.end_date >= :today)
                """).param("slug", slug).param("today", tenant.today()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Kataloq tapılmadı və ya bağlıdır"));
    }

    @GetMapping("/api/public/catalog/{slug}")
    public PublicCatalog catalog(@PathVariable String slug) {
        UUID s = storeOf(slug);
        record Store(String name, String phone, String address, String note) {}
        Store st = jdbc.sql("SELECT name, phone, address, catalog_note FROM stores WHERE id = :id").param("id", s)
                .query((rs, n) -> new Store(rs.getString("name"), rs.getString("phone"), rs.getString("address"),
                        rs.getString("catalog_note"))).single();
        List<PublicProduct> products = jdbc.sql("""
                SELECT p.id, p.name, c.name AS category, p.unit, p.sale_price, p.old_price, p.stock_qty > 0 AS in_stock,
                       p.image IS NOT NULL AS has_image
                FROM products p LEFT JOIN product_categories c ON c.id = p.category_id
                WHERE p.store_id = :s AND p.active AND p.sale_price > 0
                ORDER BY (p.stock_qty > 0) DESC, c.name NULLS LAST, p.name LIMIT 2000
                """).param("s", s)
                .query((rs, n) -> new PublicProduct(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                        rs.getString("unit"), Money.of(rs.getBigDecimal("sale_price")),
                        rs.getBigDecimal("old_price") == null ? null : Money.of(rs.getBigDecimal("old_price")),
                        rs.getBoolean("in_stock"), rs.getBoolean("has_image"))).list();
        return new PublicCatalog(st.name(), st.phone(), st.address(), st.note(), products);
    }

    @GetMapping("/api/public/catalog/{slug}/images/{productId}")
    public ResponseEntity<byte[]> image(@PathVariable String slug, @PathVariable UUID productId) {
        UUID s = storeOf(slug);
        record Img(byte[] bytes, String type) {}
        Img img = jdbc.sql("SELECT image, image_type FROM products WHERE id = :id AND store_id = :s AND active AND image IS NOT NULL")
                .param("id", productId).param("s", s)
                .query((rs, n) -> new Img(rs.getBytes("image"), rs.getString("image_type"))).optional()
                .orElseThrow(() -> ApiException.notFound("Şəkil yoxdur"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.type()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic()).body(img.bytes());
    }

    @PostMapping("/api/public/catalog/{slug}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> order(@PathVariable String slug, @Valid @RequestBody OrderRequest req) {
        UUID s = storeOf(slug);
        long recent = jdbc.sql("SELECT COUNT(*) FROM online_orders WHERE store_id = :s AND created_at > now() - interval '1 hour'")
                .param("s", s).query(Long.class).single();
        if (recent >= 60) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ORDERS", "Çox sifariş gəlib, bir az sonra yenidən cəhd edin");
        }
        UUID id = UUID.randomUUID();
        String number = "O-" + String.format("%06d", jdbc.sql("SELECT nextval('online_order_number_seq')").query(Long.class).single());
        BigDecimal total = BigDecimal.ZERO;
        List<Object[]> lines = new ArrayList<>();
        for (OrderLine l : req.items()) {
            record P(String name, BigDecimal price) {}
            P p = jdbc.sql("SELECT name, sale_price FROM products WHERE id = :id AND store_id = :s AND active AND sale_price > 0")
                    .param("id", l.productId()).param("s", s)
                    .query((rs, n) -> new P(rs.getString("name"), rs.getBigDecimal("sale_price"))).optional()
                    .orElseThrow(() -> ApiException.badRequest("PRODUCT_GONE", "Səbətdəki bir mal artıq satışda deyil — səhifəni yeniləyin"));
            BigDecimal q = l.quantity().setScale(3, RoundingMode.HALF_UP);
            BigDecimal lt = Money.of(q.multiply(p.price()));
            total = total.add(lt);
            lines.add(new Object[]{l.productId(), p.name(), q, Money.of(p.price()), lt});
        }
        jdbc.sql("""
                INSERT INTO online_orders (id, store_id, number, customer_name, customer_phone, address, note, total)
                VALUES (:id, :s, :n, :name, :phone, :addr, :note, :t)
                """).param("id", id).param("s", s).param("n", number).param("name", req.name().trim())
                .param("phone", req.phone().trim()).param("addr", blank(req.address())).param("note", blank(req.note()))
                .param("t", Money.of(total)).update();
        for (Object[] l : lines) {
            jdbc.sql("""
                    INSERT INTO online_order_items (id, order_id, product_id, name, quantity, unit_price, line_total)
                    VALUES (:id, :o, :p, :n, :q, :up, :lt)
                    """).param("id", UUID.randomUUID()).param("o", id).param("p", l[0]).param("n", l[1])
                    .param("q", l[2]).param("up", l[3]).param("lt", l[4]).update();
        }
        notifications.create(s, "ONLINE_ORDER", "Yeni onlayn sifariş " + number,
                req.name().trim() + " (" + req.phone().trim() + ") — " + Money.of(total) + " AZN, " + lines.size() + " mal");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", number);
        out.put("total", Money.of(total));
        return out;
    }

    // ---------- store: orders ----------

    @GetMapping("/api/online-orders")
    public List<Order> orders(@RequestParam(required = false) String status) {
        CurrentMember m = tenant.requirePermission("SALES");
        List<Order> list = jdbc.sql("""
                SELECT * FROM online_orders WHERE store_id = :s AND (CAST(:st AS varchar) IS NULL OR status = :st)
                ORDER BY created_at DESC LIMIT 200
                """).param("s", m.storeId()).param("st", status)
                .query((rs, n) -> new Order(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("customer_name"),
                        rs.getString("customer_phone"), rs.getString("address"), rs.getString("note"), Money.of(rs.getBigDecimal("total")),
                        rs.getString("status"), rs.getObject("sale_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                        new ArrayList<>())).list();
        for (Order o : list) {
            o.items().addAll(jdbc.sql("SELECT product_id, name, quantity, unit_price, line_total FROM online_order_items WHERE order_id = :o ORDER BY name")
                    .param("o", o.id())
                    .query((rs, n) -> new OrderItem(rs.getObject("product_id", UUID.class), rs.getString("name"),
                            rs.getBigDecimal("quantity").stripTrailingZeros(), Money.of(rs.getBigDecimal("unit_price")),
                            Money.of(rs.getBigDecimal("line_total")))).list());
        }
        return list;
    }

    @PostMapping("/api/online-orders/{id}/status")
    @Transactional
    public Map<String, Object> setStatus(@PathVariable UUID id, @Valid @RequestBody StatusUpdate in) {
        CurrentMember m = tenant.requirePermission("SALES");
        int n = jdbc.sql("""
                UPDATE online_orders SET status = :st, sale_id = COALESCE(:sale, sale_id), updated_at = now(), updated_by = :by
                WHERE id = :id AND store_id = :s
                """).param("st", in.status()).param("sale", in.saleId()).param("by", m.userId()).param("id", id)
                .param("s", m.storeId()).update();
        if (n == 0) throw ApiException.notFound("Sifariş tapılmadı");
        audit.log(m.storeId(), m.userId(), "ONLINE_ORDER_" + in.status(), "ONLINE_ORDER", id.toString(), "Status: " + in.status());
        return Map.of("id", id, "status", in.status());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
