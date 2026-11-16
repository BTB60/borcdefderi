package az.borcdefteri.inventory;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Toplu qiymət dəyişikliyi: change one price of many products at once — by %, by an amount, or to a fixed value —
 * for a category, a supplier, chosen products or everything; optionally rounded (e.g. to 0.05 ₼).
 */
@Service
public class BulkPriceService {

    public record Request(
            /** SALE (Top 2, pərakəndə), WHOLESALE (Top 1, topdan) or PURCHASE. */
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Pattern(regexp = "SALE|WHOLESALE|PURCHASE") String field,
            /** PERCENT (+/- %), AMOUNT (+/- ₼) or SET (new price). */
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Pattern(regexp = "PERCENT|AMOUNT|SET") String mode,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal value,
            /** Round the result to this step (0.01, 0.05, 0.1, 1); null = 0.01. */
            BigDecimal roundTo,
            UUID categoryId,
            UUID supplierId,
            List<UUID> productIds) {}

    public record Result(int changed, int unchanged) {}

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final AuditService audit;

    public BulkPriceService(JdbcClient jdbc, InventoryService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
    }

    @Transactional
    public Result apply(Request req) {
        CurrentMember m = inventory.requireInventory();
        String column = switch (req.field()) {
            case "WHOLESALE" -> "wholesale_price";
            case "PURCHASE" -> "purchase_price";
            default -> "sale_price";
        };
        StringBuilder where = new StringBuilder(" WHERE store_id = :s AND active");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("s", m.storeId());
        if (req.categoryId() != null) { where.append(" AND category_id = :c"); p.put("c", req.categoryId()); }
        if (req.supplierId() != null) { where.append(" AND supplier_id = :sup"); p.put("sup", req.supplierId()); }
        if (req.productIds() != null && !req.productIds().isEmpty()) { where.append(" AND id IN (:ids)"); p.put("ids", req.productIds()); }
        // a wholesale % / ± change starts from the retail price when the product has no wholesale price yet
        String base = "WHOLESALE".equals(req.field()) ? "COALESCE(wholesale_price, sale_price)" : column;
        var spec = jdbc.sql("SELECT id, " + base + " AS price FROM products" + where + " FOR UPDATE");
        for (var e : p.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        record Row(UUID id, BigDecimal price) {}
        List<Row> rows = spec.query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getBigDecimal("price"))).list();
        if (rows.isEmpty()) throw ApiException.badRequest("NO_PRODUCTS", "Seçimə uyğun məhsul tapılmadı");
        BigDecimal step = req.roundTo() == null || req.roundTo().signum() <= 0 ? new BigDecimal("0.01") : req.roundTo();
        int changed = 0, unchanged = 0;
        for (Row r : rows) {
            BigDecimal next = switch (req.mode()) {
                case "PERCENT" -> r.price().multiply(BigDecimal.ONE.add(req.value().movePointLeft(2)));
                case "AMOUNT" -> r.price().add(req.value());
                default -> req.value();
            };
            next = next.divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
            if (next.compareTo(r.price()) == 0 && !"WHOLESALE".equals(req.field())) { unchanged++; continue; }
            jdbc.sql("UPDATE products SET " + column + " = :v, updated_at = now(), version = version + 1 WHERE id = :id")
                    .param("v", next).param("id", r.id()).update();
            changed++;
        }
        audit.log(m.storeId(), m.userId(), "PRICES_BULK_CHANGED", "PRODUCT", "bulk",
                req.field() + " " + req.mode() + " " + req.value() + " → " + changed + " məhsul");
        return new Result(changed, unchanged);
    }
}
