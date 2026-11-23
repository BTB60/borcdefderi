package az.borcdefteri.inventory;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tədarükçüyə sifariş: what the store ordered from a supplier. When the goods come, the order is turned into a goods
 * receipt (Mal qəbulu with purchaseOrderId); each line counts what came, and what did not come stays visible.
 * OPEN → PARTIAL (something came) → RECEIVED (everything came), or CANCELLED.
 */
@Service
@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderService {

    public record ItemRequest(@NotNull UUID productId,
                              @NotNull @DecimalMin(value = "0.001", message = "Miqdar 0-dan böyük olmalıdır")
                              @Digits(integer = 11, fraction = 3) BigDecimal quantity) {}

    public record OrderRequest(UUID companyId,
                               @NotEmpty(message = "Ən azı bir məhsul əlavə edin") @Size(max = 300) List<@Valid ItemRequest> items,
                               @Size(max = 500) String note) {}

    public record Item(UUID id, UUID productId, String name, String code, String unit, BigDecimal quantity, BigDecimal receivedQty,
                       BigDecimal missingQty, BigDecimal purchasePrice) {}

    public record Order(UUID id, String number, UUID companyId, String companyName, String companyPhone, String status, String note,
                        String createdByName, OffsetDateTime createdAt, List<Item> items, long missingLines) {}

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final AuditService audit;

    public PurchaseOrderService(JdbcClient jdbc, InventoryService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
    }

    /** {@code status}: ACTIVE (open or partly received, the default), ALL, or one status. */
    @GetMapping
    public List<Order> list(@RequestParam(required = false) String status) {
        CurrentMember m = inventory.requireInventory();
        String st = status == null || status.isBlank() ? "ACTIVE" : status.toUpperCase(Locale.ROOT);
        String where = switch (st) {
            case "ACTIVE" -> " AND o.status IN ('OPEN', 'PARTIAL')";
            case "ALL" -> "";
            default -> " AND o.status = :st";
        };
        var spec = jdbc.sql("SELECT o.id FROM purchase_orders o WHERE o.store_id = :s" + where + " ORDER BY o.created_at DESC LIMIT 200")
                .param("s", m.storeId());
        if (!"ACTIVE".equals(st) && !"ALL".equals(st)) spec = spec.param("st", st);
        return spec.query(UUID.class).list().stream().map(id -> load(m.storeId(), id)).toList();
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable UUID id) {
        return load(inventory.requireInventory().storeId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Order create(@Valid @RequestBody OrderRequest req) {
        CurrentMember m = inventory.requireInventory();
        if (req.companyId() != null && jdbc.sql("SELECT COUNT(*) FROM companies WHERE id = :c AND store_id = :s")
                .param("c", req.companyId()).param("s", m.storeId()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Tədarükçü tapılmadı");
        }
        UUID id = UUID.randomUUID();
        String number = "SF-" + String.format("%06d", jdbc.sql("SELECT nextval('purchase_order_number_seq')").query(Long.class).single());
        jdbc.sql("INSERT INTO purchase_orders (id, store_id, number, company_id, note, created_by) VALUES (:id, :s, :n, :c, :note, :by)")
                .param("id", id).param("s", m.storeId()).param("n", number).param("c", req.companyId())
                .param("note", req.note() == null || req.note().isBlank() ? null : req.note().trim()).param("by", m.userId()).update();
        for (ItemRequest it : req.items()) {
            var p = inventory.load(m.storeId(), it.productId());
            jdbc.sql("INSERT INTO purchase_order_items (id, order_id, product_id, name, quantity) VALUES (:id, :o, :p, :n, :q)")
                    .param("id", UUID.randomUUID()).param("o", id).param("p", p.id()).param("n", p.name())
                    .param("q", it.quantity().setScale(3, RoundingMode.HALF_UP)).update();
        }
        audit.log(m.storeId(), m.userId(), "PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", id.toString(), number + ": " + req.items().size() + " məhsul");
        return load(m.storeId(), id);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public Order cancel(@PathVariable UUID id) {
        CurrentMember m = inventory.requireInventory();
        Order o = lock(m.storeId(), id);
        if ("RECEIVED".equals(o.status()) || "CANCELLED".equals(o.status())) {
            throw ApiException.conflict("ORDER_CLOSED", "Bu sifariş artıq bağlanıb");
        }
        jdbc.sql("UPDATE purchase_orders SET status = 'CANCELLED' WHERE id = :id").param("id", id).update();
        audit.log(m.storeId(), m.userId(), "PURCHASE_ORDER_CANCELLED", "PURCHASE_ORDER", id.toString(), o.number());
        return load(m.storeId(), id);
    }

    /**
     * Called by Mal qəbulu inside its transaction: the received quantities count against the order's lines (per product)
     * and the order becomes PARTIAL or RECEIVED.
     */
    void received(CurrentMember m, UUID orderId, Map<UUID, BigDecimal> qtyByProduct) {
        Order o = lock(m.storeId(), orderId);
        if ("CANCELLED".equals(o.status()) || "RECEIVED".equals(o.status())) {
            throw ApiException.conflict("ORDER_CLOSED", "Sifariş " + o.number() + " artıq bağlanıb");
        }
        for (var e : qtyByProduct.entrySet()) {
            jdbc.sql("UPDATE purchase_order_items SET received_qty = received_qty + :q WHERE order_id = :o AND product_id = :p")
                    .param("q", e.getValue()).param("o", orderId).param("p", e.getKey()).update();
        }
        long missing = jdbc.sql("SELECT COUNT(*) FROM purchase_order_items WHERE order_id = :o AND received_qty < quantity")
                .param("o", orderId).query(Long.class).single();
        jdbc.sql("UPDATE purchase_orders SET status = :st WHERE id = :o").param("st", missing == 0 ? "RECEIVED" : "PARTIAL")
                .param("o", orderId).update();
    }

    private Order lock(UUID storeId, UUID id) {
        jdbc.sql("SELECT id FROM purchase_orders WHERE id = :id AND store_id = :s FOR UPDATE").param("id", id).param("s", storeId)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Sifariş tapılmadı"));
        return load(storeId, id);
    }

    private Order load(UUID storeId, UUID id) {
        List<Item> items = jdbc.sql("""
                SELECT i.*, p.code, p.unit, p.purchase_price FROM purchase_order_items i LEFT JOIN products p ON p.id = i.product_id
                WHERE i.order_id = :o ORDER BY i.name""").param("o", id)
                .query((rs, n) -> {
                    BigDecimal q = rs.getBigDecimal("quantity");
                    BigDecimal r = rs.getBigDecimal("received_qty");
                    return new Item(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("name"),
                            rs.getString("code"), rs.getString("unit"), q, r, q.subtract(r).max(BigDecimal.ZERO),
                            rs.getBigDecimal("purchase_price"));
                }).list();
        return jdbc.sql("""
                SELECT o.*, c.name AS company_name, c.phone AS company_phone, u.full_name FROM purchase_orders o
                LEFT JOIN companies c ON c.id = o.company_id JOIN users u ON u.id = o.created_by
                WHERE o.id = :id AND o.store_id = :s""").param("id", id).param("s", storeId)
                .query((rs, n) -> new Order(rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("company_id", UUID.class),
                        rs.getString("company_name"), rs.getString("company_phone"), rs.getString("status"), rs.getString("note"),
                        rs.getString("full_name"), rs.getObject("created_at", OffsetDateTime.class), items,
                        items.stream().filter(i -> i.missingQty().signum() > 0).count()))
                .optional().orElseThrow(() -> ApiException.notFound("Sifariş tapılmadı"));
    }
}
