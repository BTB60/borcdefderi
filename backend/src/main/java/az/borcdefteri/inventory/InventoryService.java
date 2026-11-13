package az.borcdefteri.inventory;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.common.Search;
import az.borcdefteri.subscriptions.ReceiptStorage;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Products and stock. The quantity on hand is stored on the product and changed only together with an
 * append-only inventory movement (same transaction, product row locked), so history and stock always agree.
 */
@Service
public class InventoryService {

    public static final String PERM_INVENTORY = "INVENTORY";

    public record Category(UUID id, String name, long productCount) {}

    public record Product(UUID id, String code, String barcode, String name, UUID categoryId, String categoryName,
                          UUID supplierId, String supplierName, String unit, BigDecimal purchasePrice, BigDecimal salePrice,
                          BigDecimal stockQty, BigDecimal minStock, boolean active, boolean lowStock, boolean outOfStock,
                          boolean hasImage, BigDecimal stockCostValue, BigDecimal stockSaleValue, long version,
                          /** Price before a discount (shown struck through on labels); null = no discount. */
                          BigDecimal oldPrice) {}

    public record ProductRequest(
            @jakarta.validation.constraints.NotBlank(message = "Məhsulun adını daxil edin")
            @jakarta.validation.constraints.Size(max = 200) String name,
            @jakarta.validation.constraints.Size(max = 30) String code,
            @jakarta.validation.constraints.Pattern(regexp = "^[0-9A-Za-z-]{0,64}$", message = "Barkod yalnız rəqəm/hərf ola bilər") String barcode,
            UUID categoryId,
            UUID supplierId,
            @jakarta.validation.constraints.Size(max = 20) String unit,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal purchasePrice,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal salePrice,
            @jakarta.validation.constraints.DecimalMin("0.000")
            @jakarta.validation.constraints.Digits(integer = 11, fraction = 3) BigDecimal minStock,
            /** Only on create: opening stock, booked as a RECEIPT movement. */
            @jakarta.validation.constraints.DecimalMin("0.000")
            @jakarta.validation.constraints.Digits(integer = 11, fraction = 3) BigDecimal initialStock,
            Boolean active,
            Long version,
            /** Price before a discount. null = keep as is (on update), 0 = remove the discount. */
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal oldPrice) {}

    public record StockRequest(
            @jakarta.validation.constraints.NotNull UUID productId,
            /** RECEIPT (anbara giriş), ISSUE (çıxış), ADJUSTMENT (± düzəliş), COUNT (sayılan miqdar). */
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Pattern(regexp = "RECEIPT|ISSUE|ADJUSTMENT|COUNT") String kind,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            @jakarta.validation.constraints.Size(max = 500) String note) {}

    public record Movement(UUID id, UUID productId, String productName, String kind, BigDecimal quantity, BigDecimal unitCost,
                           BigDecimal balanceAfter, UUID saleId, String note, String actorName, OffsetDateTime createdAt) {}

    public record Summary(long productCount, long activeCount, long lowStockCount, long outOfStockCount,
                          BigDecimal costValue, BigDecimal saleValue) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final ReceiptStorage files;

    public InventoryService(JdbcClient jdbc, TenantService tenant, AuditService audit, ReceiptStorage files) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.files = files;
    }

    public CurrentMember requireInventory() {
        return tenant.requirePermission(PERM_INVENTORY);
    }

    /** Sales staff may read products (to sell) without managing the stock. */
    private CurrentMember requireRead() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.has(PERM_INVENTORY) && !m.has("SALES")) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return m;
    }

    // ----- categories -----

    public List<Category> categories() {
        CurrentMember m = requireRead();
        return jdbc.sql("""
                SELECT c.id, c.name, (SELECT COUNT(*) FROM products p WHERE p.category_id = c.id) AS n
                FROM product_categories c WHERE c.store_id = :s ORDER BY c.name""")
                .param("s", m.storeId()).query((rs, n) -> new Category(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("n"))).list();
    }

    @Transactional
    public Category createCategory(String name) {
        CurrentMember m = requireInventory();
        if (name == null || name.isBlank() || name.length() > 100) {
            throw ApiException.badRequest("INVALID_NAME", "Kateqoriya adı 1–100 simvol olmalıdır");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO product_categories (id, store_id, name) VALUES (:id, :s, :n)")
                    .param("id", id).param("s", m.storeId()).param("n", name.trim()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_NAME", "Bu adda kateqoriya artıq var");
        }
        return new Category(id, name.trim(), 0);
    }

    // ----- products -----

    public List<Product> products(String q, UUID categoryId, String stock, Boolean active) {
        CurrentMember m = requireRead();
        StringBuilder where = new StringBuilder(" WHERE p.store_id = :s");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("s", m.storeId());
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(p.name) LIKE LOWER(:q) OR LOWER(p.code) LIKE LOWER(:q) OR COALESCE(p.barcode, '') = :exact)");
            params.put("q", Search.contains(q));
            params.put("exact", q.trim());
        }
        if (categoryId != null) { where.append(" AND p.category_id = :c"); params.put("c", categoryId); }
        if ("LOW".equalsIgnoreCase(stock)) where.append(" AND p.stock_qty <= p.min_stock");
        if ("OUT".equalsIgnoreCase(stock)) where.append(" AND p.stock_qty = 0");
        if (active != null) { where.append(" AND p.active = :a"); params.put("a", active); }
        var spec = jdbc.sql(SELECT + where + " ORDER BY p.name LIMIT 1000");
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec.query((rs, n) -> map(rs)).list();
    }

    public Product product(UUID id) {
        CurrentMember m = requireRead();
        return load(m.storeId(), id);
    }

    /** Exact barcode or code lookup for the sale screen / scanner. */
    public Product byBarcode(String code) {
        CurrentMember m = requireRead();
        return jdbc.sql(SELECT + " WHERE p.store_id = :s AND p.active AND (p.barcode = :c OR LOWER(p.code) = LOWER(:c)) LIMIT 1")
                .param("s", m.storeId()).param("c", code.trim()).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Bu barkodla məhsul tapılmadı"));
    }

    public Summary summary() {
        CurrentMember m = requireRead();
        return jdbc.sql("""
                SELECT COUNT(*) AS n, COUNT(*) FILTER (WHERE active) AS act,
                       COUNT(*) FILTER (WHERE active AND stock_qty <= min_stock) AS low,
                       COUNT(*) FILTER (WHERE active AND stock_qty = 0) AS out_,
                       COALESCE(SUM(ROUND(stock_qty * purchase_price, 2)), 0) AS cost,
                       COALESCE(SUM(ROUND(stock_qty * sale_price, 2)), 0) AS sale
                FROM products WHERE store_id = :s""").param("s", m.storeId())
                .query((rs, n) -> new Summary(rs.getLong("n"), rs.getLong("act"), rs.getLong("low"), rs.getLong("out_"),
                        Money.of(rs.getBigDecimal("cost")), Money.of(rs.getBigDecimal("sale")))).single();
    }

    @Transactional
    public Product create(ProductRequest req) {
        CurrentMember m = requireInventory();
        checkRefs(m.storeId(), req.categoryId(), req.supplierId());
        UUID id = UUID.randomUUID();
        String code = req.code() == null || req.code().isBlank()
                ? "P-" + String.format("%06d", jdbc.sql("SELECT nextval('product_code_seq')").query(Long.class).single())
                : req.code().trim();
        try {
            jdbc.sql("""
                    INSERT INTO products (id, store_id, code, barcode, name, category_id, supplier_id, unit, purchase_price,
                                          sale_price, min_stock, active, created_by, old_price)
                    VALUES (:id, :s, :code, :bc, :n, :c, :sup, :u, :pp, :sp, :min, :act, :by, :op)
                    """).param("id", id).param("s", m.storeId()).param("code", code).param("bc", blank(req.barcode()))
                    .param("n", req.name().trim()).param("c", req.categoryId()).param("sup", req.supplierId())
                    .param("u", req.unit() == null || req.unit().isBlank() ? "ədəd" : req.unit().trim())
                    .param("pp", Money.of(req.purchasePrice())).param("sp", Money.of(req.salePrice()))
                    .param("min", qty(req.minStock())).param("act", req.active() == null || req.active()).param("by", m.userId())
                    .param("op", oldPrice(req.oldPrice(), null))
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_CODE", "Bu kod və ya barkod artıq başqa məhsulda istifadə olunur");
        }
        if (req.initialStock() != null && req.initialStock().signum() > 0) {
            move(m, id, "RECEIPT", qty(req.initialStock()), Money.of(req.purchasePrice()), null, "Başlanğıc qalıq");
        }
        audit.log(m.storeId(), m.userId(), "PRODUCT_CREATED", "PRODUCT", id.toString(), code + " " + req.name());
        return load(m.storeId(), id);
    }

    @Transactional
    public Product update(UUID id, ProductRequest req) {
        CurrentMember m = requireInventory();
        Product cur = lockProduct(m.storeId(), id);
        if (req.version() == null || req.version() != cur.version()) {
            throw ApiException.conflict("VERSION_CONFLICT", "Məhsul başqa istifadəçi tərəfindən dəyişdirilib. Yenidən yükləyin");
        }
        checkRefs(m.storeId(), req.categoryId(), req.supplierId());
        try {
            jdbc.sql("""
                    UPDATE products SET code = :code, barcode = :bc, name = :n, category_id = :c, supplier_id = :sup, unit = :u,
                           purchase_price = :pp, sale_price = :sp, min_stock = :min, active = :act, old_price = :op, updated_at = now(),
                           version = version + 1
                    WHERE id = :id AND store_id = :s
                    """).param("code", req.code() == null || req.code().isBlank() ? cur.code() : req.code().trim())
                    .param("bc", blank(req.barcode())).param("n", req.name().trim()).param("c", req.categoryId())
                    .param("sup", req.supplierId()).param("u", req.unit() == null || req.unit().isBlank() ? cur.unit() : req.unit().trim())
                    .param("pp", Money.of(req.purchasePrice())).param("sp", Money.of(req.salePrice())).param("min", qty(req.minStock()))
                    .param("act", req.active() == null || req.active()).param("op", oldPrice(req.oldPrice(), cur.oldPrice()))
                    .param("id", id).param("s", m.storeId()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_CODE", "Bu kod və ya barkod artıq başqa məhsulda istifadə olunur");
        }
        audit.log(m.storeId(), m.userId(), "PRODUCT_UPDATED", "PRODUCT", id.toString(),
                cur.name() + ": alış " + cur.purchasePrice() + " -> " + req.purchasePrice() + ", satış " + cur.salePrice() + " -> " + req.salePrice());
        return load(m.storeId(), id);
    }

    /**
     * Removes a product that was never moved or sold; otherwise it is only deactivated so its history stays.
     * Returns true when deleted, false when deactivated.
     */
    @Transactional
    public boolean delete(UUID id) {
        CurrentMember m = requireInventory();
        Product p = lockProduct(m.storeId(), id);
        long used = jdbc.sql("SELECT (SELECT COUNT(*) FROM inventory_movements WHERE product_id = :id) + (SELECT COUNT(*) FROM sale_items WHERE product_id = :id)")
                .param("id", id).query(Long.class).single();
        if (used == 0) {
            jdbc.sql("DELETE FROM products WHERE id = :id AND store_id = :s").param("id", id).param("s", m.storeId()).update();
            audit.log(m.storeId(), m.userId(), "PRODUCT_DELETED", "PRODUCT", id.toString(), p.name());
            return true;
        }
        jdbc.sql("UPDATE products SET active = FALSE, version = version + 1 WHERE id = :id AND store_id = :s")
                .param("id", id).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "PRODUCT_DEACTIVATED", "PRODUCT", id.toString(), p.name());
        return false;
    }

    @Transactional
    public Product setImage(UUID id, MultipartFile file) {
        CurrentMember m = requireInventory();
        lockProduct(m.storeId(), id);
        ReceiptStorage.Stored s = files.store(file);
        if (!s.contentType().startsWith("image/")) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Yalnız PNG və ya JPG şəkil");
        }
        jdbc.sql("UPDATE products SET image = :i, image_type = :t, version = version + 1 WHERE id = :id AND store_id = :s")
                .param("i", s.content()).param("t", s.contentType()).param("id", id).param("s", m.storeId()).update();
        return load(m.storeId(), id);
    }

    public record Image(byte[] content, String contentType) {}

    public Image image(UUID id) {
        CurrentMember m = requireRead();
        return jdbc.sql("SELECT image, image_type FROM products WHERE id = :id AND store_id = :s AND image IS NOT NULL")
                .param("id", id).param("s", m.storeId())
                .query((rs, n) -> new Image(rs.getBytes("image"), rs.getString("image_type"))).optional()
                .orElseThrow(() -> ApiException.notFound("Şəkil yoxdur"));
    }

    // ----- stock -----

    @Transactional
    public Movement stock(StockRequest req) {
        CurrentMember m = requireInventory();
        Product p = lockProduct(m.storeId(), req.productId());
        BigDecimal q = qty(req.quantity());
        BigDecimal delta = switch (req.kind()) {
            case "RECEIPT" -> positive(q);
            case "ISSUE" -> positive(q).negate();
            case "ADJUSTMENT" -> q;
            case "COUNT" -> {
                if (q.signum() < 0) throw ApiException.badRequest("INVALID_QUANTITY", "Sayılan miqdar mənfi ola bilməz");
                yield q.subtract(p.stockQty());
            }
            default -> throw ApiException.badRequest("INVALID_KIND", "Əməliyyat növü yanlışdır");
        };
        if (delta.signum() == 0) {
            throw ApiException.badRequest("NO_CHANGE", "COUNT".equals(req.kind()) ? "Sayım uyğundur, dəyişiklik yoxdur" : "Miqdar 0 ola bilməz");
        }
        BigDecimal cost = req.unitCost() != null ? Money.of(req.unitCost()) : null;
        UUID mid = move(m, p.id(), req.kind(), delta, cost, null, req.note());
        // a receipt with a new purchase price updates the product's price (weighted average of old and new stock)
        if ("RECEIPT".equals(req.kind()) && cost != null && cost.compareTo(p.purchasePrice()) != 0) {
            BigDecimal oldQty = p.stockQty();
            BigDecimal newQty = oldQty.add(delta);
            BigDecimal avg = oldQty.signum() == 0 ? cost
                    : oldQty.multiply(p.purchasePrice()).add(delta.multiply(cost)).divide(newQty, 2, RoundingMode.HALF_UP);
            jdbc.sql("UPDATE products SET purchase_price = :pp, version = version + 1 WHERE id = :id")
                    .param("pp", avg).param("id", p.id()).update();
        }
        audit.log(m.storeId(), m.userId(), "STOCK_" + req.kind(), "PRODUCT", p.id().toString(), p.name() + ": " + delta);
        return movement(m.storeId(), mid);
    }

    public List<Movement> movements(UUID productId, int limit) {
        CurrentMember m = requireRead();
        String filter = productId != null ? " AND im.product_id = :p" : "";
        var spec = jdbc.sql(MOVE_SELECT + " WHERE im.store_id = :s" + filter + " ORDER BY im.created_at DESC LIMIT "
                + Math.min(Math.max(limit, 1), 1000)).param("s", m.storeId());
        if (productId != null) spec = spec.param("p", productId);
        return spec.query((rs, n) -> mapMove(rs)).list();
    }

    /**
     * Changes stock by {@code delta} for a locked product and writes the movement (used by sales and returns too).
     * Stock never goes below zero.
     */
    public UUID move(CurrentMember m, UUID productId, String kind, BigDecimal delta, BigDecimal unitCost, UUID saleId, String note) {
        return move(m, productId, kind, delta, unitCost, saleId, null, note);
    }

    /** As {@link #move(CurrentMember, UUID, String, BigDecimal, BigDecimal, UUID, String)}, linked to a stock count. */
    public UUID move(CurrentMember m, UUID productId, String kind, BigDecimal delta, BigDecimal unitCost, UUID saleId,
                     UUID stockCountId, String note) {
        BigDecimal current = jdbc.sql("SELECT stock_qty FROM products WHERE id = :id AND store_id = :s FOR UPDATE")
                .param("id", productId).param("s", m.storeId()).query(BigDecimal.class).optional()
                .orElseThrow(() -> ApiException.notFound("Məhsul tapılmadı"));
        BigDecimal after = current.add(delta).setScale(3, RoundingMode.HALF_UP);
        if (after.signum() < 0) {
            String name = jdbc.sql("SELECT name FROM products WHERE id = :id").param("id", productId).query(String.class).single();
            throw ApiException.conflict("INSUFFICIENT_STOCK", "“" + name + "” anbarda kifayət qədər yoxdur (qalıq: "
                    + current.stripTrailingZeros().toPlainString() + ")");
        }
        jdbc.sql("UPDATE products SET stock_qty = :q, updated_at = now() WHERE id = :id").param("q", after).param("id", productId).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO inventory_movements (id, store_id, product_id, kind, quantity, unit_cost, balance_after, sale_id,
                                                 stock_count_id, note, actor_id)
                VALUES (:id, :s, :p, :k, :q, :c, :b, :sale, :cnt, :note, :a)
                """).param("id", id).param("s", m.storeId()).param("p", productId).param("k", kind).param("q", delta)
                .param("c", unitCost).param("b", after).param("sale", saleId).param("cnt", stockCountId)
                .param("note", blank(note)).param("a", m.userId()).update();
        return id;
    }

    public Product lockProduct(UUID storeId, UUID id) {
        jdbc.sql("SELECT id FROM products WHERE id = :id AND store_id = :s FOR UPDATE").param("id", id).param("s", storeId)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Məhsul tapılmadı"));
        return load(storeId, id);
    }

    public Product load(UUID storeId, UUID id) {
        return jdbc.sql(SELECT + " WHERE p.store_id = :s AND p.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("Məhsul tapılmadı"));
    }

    private void checkRefs(UUID storeId, UUID categoryId, UUID supplierId) {
        if (categoryId != null && jdbc.sql("SELECT COUNT(*) FROM product_categories WHERE id = :id AND store_id = :s")
                .param("id", categoryId).param("s", storeId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Kateqoriya tapılmadı");
        }
        if (supplierId != null && jdbc.sql("SELECT COUNT(*) FROM companies WHERE id = :id AND store_id = :s")
                .param("id", supplierId).param("s", storeId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Təchizatçı (şirkət) tapılmadı");
        }
    }

    private Movement movement(UUID storeId, UUID id) {
        return jdbc.sql(MOVE_SELECT + " WHERE im.store_id = :s AND im.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> mapMove(rs)).single();
    }

    static BigDecimal qty(BigDecimal q) {
        return q == null ? BigDecimal.ZERO.setScale(3) : q.setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal q) {
        if (q.signum() <= 0) throw ApiException.badRequest("INVALID_QUANTITY", "Miqdar 0-dan böyük olmalıdır");
        return q;
    }

    /** null keeps [current], 0 removes the discount, anything else is the new price-before-discount. */
    private static BigDecimal oldPrice(BigDecimal requested, BigDecimal current) {
        if (requested == null) return current;
        return requested.signum() == 0 ? null : Money.of(requested);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final String SELECT = """
            SELECT p.id, p.code, p.barcode, p.name, p.category_id, c.name AS category_name, p.supplier_id, co.name AS supplier_name,
                   p.unit, p.purchase_price, p.sale_price, p.old_price, p.stock_qty, p.min_stock, p.active, p.image IS NOT NULL AS has_image,
                   p.version
            FROM products p
            LEFT JOIN product_categories c ON c.id = p.category_id
            LEFT JOIN companies co ON co.id = p.supplier_id""";

    private static final String MOVE_SELECT = """
            SELECT im.id, im.product_id, p.name AS product_name, im.kind, im.quantity, im.unit_cost, im.balance_after, im.sale_id,
                   im.note, u.full_name AS actor_name, im.created_at
            FROM inventory_movements im JOIN products p ON p.id = im.product_id JOIN users u ON u.id = im.actor_id""";

    private static Product map(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal stock = rs.getBigDecimal("stock_qty");
        BigDecimal min = rs.getBigDecimal("min_stock");
        BigDecimal pp = Money.of(rs.getBigDecimal("purchase_price"));
        BigDecimal sp = Money.of(rs.getBigDecimal("sale_price"));
        boolean active = rs.getBoolean("active");
        return new Product(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("barcode"), rs.getString("name"),
                rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"), rs.getString("unit"), pp, sp, stock, min, active,
                active && stock.compareTo(min) <= 0, stock.signum() == 0, rs.getBoolean("has_image"),
                Money.of(stock.multiply(pp)), Money.of(stock.multiply(sp)), rs.getLong("version"),
                rs.getBigDecimal("old_price") == null ? null : Money.of(rs.getBigDecimal("old_price")));
    }

    private static Movement mapMove(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Movement(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                rs.getString("kind"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("balance_after"),
                rs.getObject("sale_id", UUID.class), rs.getString("note"), rs.getString("actor_name"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
