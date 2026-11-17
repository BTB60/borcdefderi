package az.borcdefteri.inventory;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Son istifadə tarixi: batches (goods-receipt lines with an expiry date) that are still on the shelf and expire
 * within N days or have already expired. The shelf quantity of a batch comes from the product's current stock,
 * assuming the oldest goods are sold first (the newest receipts are what is left).
 */
@Service
public class ExpiryService {

    public record ExpiringBatch(UUID purchaseItemId, UUID productId, String productName, String productCode, String unit,
                                String purchaseNumber, LocalDate expiryDate, long daysLeft, BigDecimal quantityLeft,
                                BigDecimal costValue, BigDecimal saleValue) {}

    private static final String SQL = """
            WITH batches AS (
                SELECT pi.id, pi.product_id, pi.expiry_date, pi.quantity - pi.returned_qty AS qty, pu.number,
                       p.name, p.code, p.unit, p.stock_qty, p.purchase_price, p.sale_price,
                       COALESCE(SUM(pi.quantity - pi.returned_qty) OVER (
                           PARTITION BY pi.product_id ORDER BY pu.created_at DESC, pi.id
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS newer
                FROM purchase_items pi
                JOIN purchases pu ON pu.id = pi.purchase_id
                JOIN products p ON p.id = pi.product_id
                WHERE pi.store_id = :s AND p.active AND p.stock_qty > 0
            )
            SELECT b.*, LEAST(b.qty, GREATEST(0, b.stock_qty - b.newer)) AS left_qty
            FROM batches b
            WHERE b.expiry_date IS NOT NULL AND b.expiry_date <= :horizon
              AND LEAST(b.qty, GREATEST(0, b.stock_qty - b.newer)) > 0
            ORDER BY b.expiry_date, b.name
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final InventoryService inventory;

    public ExpiryService(JdbcClient jdbc, TenantService tenant, InventoryService inventory) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.inventory = inventory;
    }

    /** Batches on the shelf that expire within {@code days} days (already expired ones included). */
    public List<ExpiringBatch> expiring(int days) {
        if (days < 0 || days > 365) throw ApiException.badRequest("INVALID_DAYS", "Gün sayı 0-365 olmalıdır");
        CurrentMember m = inventory.requireInventory();
        return list(m.storeId(), days);
    }

    List<ExpiringBatch> list(UUID storeId, int days) {
        LocalDate today = tenant.today();
        return jdbc.sql(SQL).param("s", storeId).param("horizon", today.plusDays(days))
                .query((rs, n) -> {
                    BigDecimal left = rs.getBigDecimal("left_qty");
                    LocalDate exp = rs.getObject("expiry_date", LocalDate.class);
                    return new ExpiringBatch(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                            rs.getString("name"), rs.getString("code"), rs.getString("unit"), rs.getString("number"),
                            exp, ChronoUnit.DAYS.between(today, exp), left.stripTrailingZeros(),
                            Money.of(left.multiply(rs.getBigDecimal("purchase_price"))),
                            Money.of(left.multiply(rs.getBigDecimal("sale_price"))));
                }).list();
    }

    public record IdleProduct(UUID productId, String name, String code, String unit, BigDecimal stockQty,
                              LocalDate lastSoldOn, LocalDate lastReceivedOn, long idleDays,
                              BigDecimal costValue, BigDecimal saleValue) {}

    /**
     * Yatmış mallar: products in stock that have not been sold for {@code days} days or more (never sold counts from
     * when the product was added), with the money frozen in them. Most idle and most valuable first.
     */
    public List<IdleProduct> idle(int days) {
        if (days < 7 || days > 730) throw ApiException.badRequest("INVALID_DAYS", "Gün sayı 7-730 olmalıdır");
        CurrentMember m = inventory.requireInventory();
        LocalDate today = tenant.today();
        return jdbc.sql("""
                SELECT p.id, p.name, p.code, p.unit, p.stock_qty, p.purchase_price, p.sale_price,
                       CAST(p.created_at AT TIME ZONE 'Asia/Baku' AS date) AS added_on,
                       (SELECT MAX(s.sold_on) FROM sale_items i JOIN sales s ON s.id = i.sale_id
                         WHERE i.product_id = p.id AND s.status = 'COMPLETED') AS last_sold,
                       (SELECT MAX(pu.received_on) FROM purchase_items pi JOIN purchases pu ON pu.id = pi.purchase_id
                         WHERE pi.product_id = p.id) AS last_received
                FROM products p
                WHERE p.store_id = :s AND p.active AND p.stock_qty > 0
                """).param("s", m.storeId())
                .query((rs, n) -> {
                    LocalDate sold = rs.getObject("last_sold", LocalDate.class);
                    LocalDate since = sold != null ? sold : rs.getObject("added_on", LocalDate.class);
                    BigDecimal qty = rs.getBigDecimal("stock_qty");
                    return new IdleProduct(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("code"),
                            rs.getString("unit"), qty.stripTrailingZeros(), sold, rs.getObject("last_received", LocalDate.class),
                            ChronoUnit.DAYS.between(since, today),
                            Money.of(qty.multiply(rs.getBigDecimal("purchase_price"))),
                            Money.of(qty.multiply(rs.getBigDecimal("sale_price"))));
                }).list().stream()
                .filter(p -> p.idleDays() >= days)
                .sorted(java.util.Comparator.comparing(IdleProduct::costValue).reversed())
                .toList();
    }

    /** For the bell: how many batches expire within a week, and how many already have. */
    public int[] counts(UUID storeId) {
        List<ExpiringBatch> week = list(storeId, 7);
        int expired = (int) week.stream().filter(b -> b.daysLeft() < 0).count();
        return new int[]{week.size(), expired};
    }
}
