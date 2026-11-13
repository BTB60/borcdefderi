package az.borcdefteri.inventory;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Stock counts (anbar sayımı). A count lists the products in its scope with their system quantity; staff enter the
 * counted quantity per product (the system quantity is re-read at that moment, so sales during the count do not distort
 * the difference). Completing the count books each difference as a COUNT movement linked to the count; after that the
 * count is read-only and serves as the count report. An unfinished count can be discarded without touching stock.
 */
@Service
public class StockCountService {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";

    public record StartRequest(UUID categoryId,
                               @jakarta.validation.constraints.Size(max = 500) String note) {}

    public record LineRequest(
            /** null clears the line back to "not counted". */
            @jakarta.validation.constraints.DecimalMin(value = "0.000", message = "Sayılan miqdar mənfi ola bilməz")
            @jakarta.validation.constraints.Digits(integer = 11, fraction = 3) BigDecimal countedQty) {}

    public record Totals(int totalItems, int countedItems, int matchedItems, int surplusItems, int shortageItems,
                         BigDecimal surplusQty, BigDecimal shortageQty,
                         BigDecimal surplusValue, BigDecimal shortageValue, BigDecimal netValue) {}

    public record CountSummary(UUID id, String number, String status, UUID categoryId, String categoryName, String note,
                               String createdByName, OffsetDateTime createdAt, String completedByName,
                               OffsetDateTime completedAt, Totals totals, long version) {}

    public record Line(UUID productId, String code, String barcode, String name, String unit, boolean hasImage,
                       BigDecimal systemQty, BigDecimal countedQty, BigDecimal difference, BigDecimal unitCost,
                       BigDecimal differenceValue, String countedByName, OffsetDateTime countedAt) {}

    public record CountDetail(CountSummary count, List<Line> lines) {}

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final AuditService audit;

    public StockCountService(JdbcClient jdbc, InventoryService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
    }

    public List<CountSummary> list(int limit) {
        CurrentMember m = inventory.requireInventory();
        return jdbc.sql(SUMMARY_SELECT + " WHERE sc.store_id = :s ORDER BY sc.created_at DESC LIMIT "
                        + Math.min(Math.max(limit, 1), 500))
                .param("s", m.storeId()).query((rs, n) -> mapSummary(rs)).list();
    }

    public CountDetail detail(UUID id) {
        CurrentMember m = inventory.requireInventory();
        return load(m.storeId(), id);
    }

    @Transactional
    public CountDetail start(StartRequest req) {
        CurrentMember m = inventory.requireInventory();
        UUID categoryId = req == null ? null : req.categoryId();
        if (categoryId != null && jdbc.sql("SELECT COUNT(*) FROM product_categories WHERE id = :id AND store_id = :s")
                .param("id", categoryId).param("s", m.storeId()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Kateqoriya tapılmadı");
        }
        UUID id = UUID.randomUUID();
        String number = "SAY-" + String.format("%06d", jdbc.sql("SELECT nextval('stock_count_number_seq')").query(Long.class).single());
        try {
            jdbc.sql("""
                    INSERT INTO stock_counts (id, store_id, number, status, category_id, note, created_by)
                    VALUES (:id, :s, :n, 'IN_PROGRESS', :c, :note, :by)
                    """).param("id", id).param("s", m.storeId()).param("n", number).param("c", categoryId)
                    .param("note", req == null ? null : blank(req.note())).param("by", m.userId()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("COUNT_IN_PROGRESS", "Artıq davam edən sayım var. Əvvəlcə onu tamamlayın və ya silin");
        }
        jdbc.sql("""
                INSERT INTO stock_count_lines (id, count_id, store_id, product_id, system_qty, unit_cost)
                SELECT md5(random()::text || p.id::text || clock_timestamp()::text)::uuid, :c, :s, p.id, p.stock_qty, p.purchase_price
                FROM products p
                WHERE p.store_id = :s AND p.active AND (CAST(:cat AS UUID) IS NULL OR p.category_id = :cat)
                """).param("c", id).param("s", m.storeId()).param("cat", categoryId).update();
        audit.log(m.storeId(), m.userId(), "STOCK_COUNT_STARTED", "STOCK_COUNT", id.toString(), number);
        return load(m.storeId(), id);
    }

    /**
     * Enters (or changes, or clears) the counted quantity of one product. A product outside the count's scope — for
     * example one scanned by barcode — is added to the count.
     */
    @Transactional
    public Line count(UUID countId, UUID productId, LineRequest req) {
        CurrentMember m = inventory.requireInventory();
        lockOpen(m.storeId(), countId);
        InventoryService.Product p = inventory.load(m.storeId(), productId);
        BigDecimal counted = req == null || req.countedQty() == null ? null : InventoryService.qty(req.countedQty());
        int updated = jdbc.sql("""
                UPDATE stock_count_lines
                SET system_qty = :sys, unit_cost = :cost, counted_qty = :q,
                    counted_by = CASE WHEN CAST(:q AS NUMERIC) IS NULL THEN NULL ELSE CAST(:by AS UUID) END,
                    counted_at = CASE WHEN CAST(:q AS NUMERIC) IS NULL THEN NULL ELSE now() END
                WHERE count_id = :c AND product_id = :p
                """).param("sys", p.stockQty()).param("cost", p.purchasePrice()).param("q", counted).param("by", m.userId())
                .param("c", countId).param("p", productId).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO stock_count_lines (id, count_id, store_id, product_id, system_qty, unit_cost, counted_qty,
                                                   counted_by, counted_at)
                    VALUES (:id, :c, :s, :p, :sys, :cost, :q, :by, CASE WHEN CAST(:q AS NUMERIC) IS NULL THEN NULL ELSE now() END)
                    """).param("id", UUID.randomUUID()).param("c", countId).param("s", m.storeId()).param("p", productId)
                    .param("sys", p.stockQty()).param("cost", p.purchasePrice()).param("q", counted)
                    .param("by", counted == null ? null : m.userId()).update();
        }
        jdbc.sql("UPDATE stock_counts SET version = version + 1 WHERE id = :id").param("id", countId).update();
        return jdbc.sql(LINE_SELECT + " WHERE l.count_id = :c AND l.product_id = :p ORDER BY p.name")
                .param("c", countId).param("p", productId).query((rs, n) -> mapLine(rs)).single();
    }

    /** Books every counted difference as a COUNT movement and closes the count. */
    @Transactional
    public CountDetail complete(UUID countId) {
        CurrentMember m = inventory.requireInventory();
        String number = lockOpen(m.storeId(), countId);
        List<Line> lines = lines(countId);
        if (lines.stream().noneMatch(l -> l.countedQty() != null)) {
            throw ApiException.badRequest("NOTHING_COUNTED", "Heç bir məhsul sayılmayıb");
        }
        for (Line l : lines) {
            if (l.difference() == null || l.difference().signum() == 0) continue;
            inventory.lockProduct(m.storeId(), l.productId());
            inventory.move(m, l.productId(), "COUNT", l.difference(), l.unitCost(), null, countId, "Sayım " + number);
        }
        jdbc.sql("""
                UPDATE stock_counts SET status = 'COMPLETED', completed_by = :by, completed_at = now(), version = version + 1
                WHERE id = :id""").param("by", m.userId()).param("id", countId).update();
        CountDetail d = load(m.storeId(), countId);
        Totals t = d.count().totals();
        audit.log(m.storeId(), m.userId(), "STOCK_COUNT_COMPLETED", "STOCK_COUNT", countId.toString(),
                number + ": artıq " + t.surplusValue() + ", kəsir " + t.shortageValue());
        return d;
    }

    /** Discards an unfinished count; stock is untouched. */
    @Transactional
    public void delete(UUID countId) {
        CurrentMember m = inventory.requireInventory();
        String number = lockOpen(m.storeId(), countId);
        jdbc.sql("DELETE FROM stock_counts WHERE id = :id AND store_id = :s").param("id", countId).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "STOCK_COUNT_DELETED", "STOCK_COUNT", countId.toString(), number);
    }

    // ----- helpers -----

    CountDetail load(UUID storeId, UUID id) {
        CountSummary s = jdbc.sql(SUMMARY_SELECT + " WHERE sc.store_id = :s AND sc.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> mapSummary(rs)).optional().orElseThrow(() -> ApiException.notFound("Sayım tapılmadı"));
        return new CountDetail(s, lines(id));
    }

    private List<Line> lines(UUID countId) {
        return jdbc.sql(LINE_SELECT + " WHERE l.count_id = :c ORDER BY p.name").param("c", countId)
                .query((rs, n) -> mapLine(rs)).list();
    }

    /** Locks the count row; returns its number. Fails unless it is still in progress. */
    private String lockOpen(UUID storeId, UUID id) {
        record Row(String number, String status) {}
        Row r = jdbc.sql("SELECT number, status FROM stock_counts WHERE id = :id AND store_id = :s FOR UPDATE")
                .param("id", id).param("s", storeId).query((rs, n) -> new Row(rs.getString("number"), rs.getString("status")))
                .optional().orElseThrow(() -> ApiException.notFound("Sayım tapılmadı"));
        if (!IN_PROGRESS.equals(r.status())) {
            throw ApiException.conflict("COUNT_CLOSED", "Bu sayım artıq tamamlanıb, dəyişdirilə bilməz");
        }
        return r.number();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Difference = counted − system; value at the line's purchase price. Only counted lines enter the totals. */
    private static final String SUMMARY_SELECT = """
            SELECT sc.id, sc.number, sc.status, sc.category_id, c.name AS category_name, sc.note, cu.full_name AS created_by_name,
                   sc.created_at, fu.full_name AS completed_by_name, sc.completed_at, sc.version,
                   t.total_items, t.counted_items, t.matched_items, t.surplus_items, t.shortage_items,
                   t.surplus_qty, t.shortage_qty, t.surplus_value, t.shortage_value
            FROM stock_counts sc
            LEFT JOIN product_categories c ON c.id = sc.category_id
            JOIN users cu ON cu.id = sc.created_by
            LEFT JOIN users fu ON fu.id = sc.completed_by
            CROSS JOIN LATERAL (
                SELECT COUNT(*) AS total_items,
                       COUNT(*) FILTER (WHERE l.counted_qty IS NOT NULL) AS counted_items,
                       COUNT(*) FILTER (WHERE l.counted_qty = l.system_qty) AS matched_items,
                       COUNT(*) FILTER (WHERE l.counted_qty > l.system_qty) AS surplus_items,
                       COUNT(*) FILTER (WHERE l.counted_qty < l.system_qty) AS shortage_items,
                       COALESCE(SUM(l.counted_qty - l.system_qty) FILTER (WHERE l.counted_qty > l.system_qty), 0) AS surplus_qty,
                       COALESCE(SUM(l.system_qty - l.counted_qty) FILTER (WHERE l.counted_qty < l.system_qty), 0) AS shortage_qty,
                       COALESCE(SUM(ROUND((l.counted_qty - l.system_qty) * l.unit_cost, 2))
                                FILTER (WHERE l.counted_qty > l.system_qty), 0) AS surplus_value,
                       COALESCE(SUM(ROUND((l.system_qty - l.counted_qty) * l.unit_cost, 2))
                                FILTER (WHERE l.counted_qty < l.system_qty), 0) AS shortage_value
                FROM stock_count_lines l WHERE l.count_id = sc.id
            ) t""";

    private static final String LINE_SELECT = """
            SELECT l.product_id, p.code, p.barcode, p.name, p.unit, p.image IS NOT NULL AS has_image, l.system_qty,
                   l.counted_qty, l.unit_cost, u.full_name AS counted_by_name, l.counted_at
            FROM stock_count_lines l
            JOIN products p ON p.id = l.product_id
            LEFT JOIN users u ON u.id = l.counted_by""";

    private static CountSummary mapSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal surplus = Money.of(rs.getBigDecimal("surplus_value"));
        BigDecimal shortage = Money.of(rs.getBigDecimal("shortage_value"));
        Totals t = new Totals(rs.getInt("total_items"), rs.getInt("counted_items"), rs.getInt("matched_items"),
                rs.getInt("surplus_items"), rs.getInt("shortage_items"),
                rs.getBigDecimal("surplus_qty").setScale(3, RoundingMode.HALF_UP),
                rs.getBigDecimal("shortage_qty").setScale(3, RoundingMode.HALF_UP),
                surplus, shortage, Money.of(surplus.subtract(shortage)));
        return new CountSummary(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("status"),
                rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getString("note"),
                rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("completed_by_name"), rs.getObject("completed_at", OffsetDateTime.class), t, rs.getLong("version"));
    }

    private static Line mapLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal sys = rs.getBigDecimal("system_qty");
        BigDecimal counted = rs.getBigDecimal("counted_qty");
        BigDecimal cost = Money.of(rs.getBigDecimal("unit_cost"));
        BigDecimal diff = counted == null ? null : counted.subtract(sys).setScale(3, RoundingMode.HALF_UP);
        return new Line(rs.getObject("product_id", UUID.class), rs.getString("code"), rs.getString("barcode"),
                rs.getString("name"), rs.getString("unit"), rs.getBoolean("has_image"), sys, counted, diff, cost,
                diff == null ? null : Money.of(diff.multiply(cost)), rs.getString("counted_by_name"),
                rs.getObject("counted_at", OffsetDateTime.class));
    }
}
