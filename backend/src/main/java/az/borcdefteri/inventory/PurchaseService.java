package az.borcdefteri.inventory;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.cash.CashService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.customers.PartyAccountRequests;
import az.borcdefteri.customers.PartyAccountService;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.inventory.InventoryService.Product;
import az.borcdefteri.payments.PaymentMethod;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mal qəbulu: goods bought from a supplier in one document. Each line raises stock (RECEIPT) and moves the product's
 * purchase price to the weighted average of old and new stock. With a supplier the whole amount goes onto
 * "Bizim şirkətə borcumuz" and the part paid now is paid off it at once; without one the paid part leaves the till.
 */
@Service
public class PurchaseService {

    public record ItemRequest(
            @NotNull UUID productId,
            @NotNull @DecimalMin(value = "0.001", message = "Miqdar 0-dan böyük olmalıdır")
            @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @NotNull @DecimalMin(value = "0.00", message = "Alış qiyməti mənfi ola bilməz")
            @Digits(integer = 12, fraction = 2) BigDecimal unitCost) {}

    public record PurchaseRequest(
            UUID companyId,
            @NotEmpty(message = "Ən azı bir məhsul əlavə edin") @Size(max = 300) List<@Valid ItemRequest> items,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal paid,
            PaymentMethod paymentMethod,
            UUID cashAccountId,
            @Size(max = 60, message = "Qaimə nömrəsi ən çox 60 simvol ola bilər") String supplierInvoice,
            LocalDate dueDate,
            @Size(max = 500) String note,
            UUID clientRequestId) {}

    public record Item(UUID id, UUID productId, String name, BigDecimal quantity, BigDecimal unitCost, BigDecimal lineTotal) {}

    public record Purchase(UUID id, String number, UUID companyId, String companyName, String supplierInvoice,
                           BigDecimal total, BigDecimal paid, BigDecimal unpaid, UUID debtId, LocalDate receivedOn, String note,
                           String createdByName, OffsetDateTime createdAt, List<Item> items) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final InventoryService inventory;
    private final CashService cash;
    private final PartyAccountService accounts;
    private final AuditService audit;

    public PurchaseService(JdbcClient jdbc, TenantService tenant, InventoryService inventory, CashService cash,
                           PartyAccountService accounts, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.inventory = inventory;
        this.cash = cash;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public Purchase create(PurchaseRequest req) {
        CurrentMember m = inventory.requireInventory();
        if (req.clientRequestId() != null) {
            var dup = jdbc.sql("SELECT id FROM purchases WHERE store_id = :s AND client_request_id = :r")
                    .param("s", m.storeId()).param("r", req.clientRequestId()).query(UUID.class).optional();
            if (dup.isPresent()) return load(m.storeId(), dup.get());
        }
        LocalDate today = tenant.today();
        Map<UUID, Product> products = new LinkedHashMap<>();
        req.items().stream().map(ItemRequest::productId).distinct().sorted(Comparator.naturalOrder())
                .forEach(id -> products.put(id, inventory.lockProduct(m.storeId(), id)));

        BigDecimal total = Money.zero();
        for (ItemRequest it : req.items()) {
            total = total.add(Money.of(qty(it).multiply(Money.of(it.unitCost()))));
        }
        total = Money.of(total);
        BigDecimal paid = Money.of(req.paid() != null ? req.paid() : BigDecimal.ZERO);
        if (paid.compareTo(total) > 0) throw ApiException.badRequest("OVERPAID", "Ödənilən məbləğ alışdan çox ola bilməz");
        PaymentMethod method = req.paymentMethod() != null ? req.paymentMethod() : PaymentMethod.CASH;

        UUID id = UUID.randomUUID();
        String number = "A-" + String.format("%06d", jdbc.sql("SELECT nextval('purchase_number_seq')").query(Long.class).single());
        String invoice = blank(req.supplierInvoice());
        jdbc.sql("""
                INSERT INTO purchases (id, store_id, number, company_id, supplier_invoice, total, paid, received_on, note,
                                       created_by, client_request_id)
                VALUES (:id, :s, :n, :c, :inv, :t, :p, :day, :note, :by, :req)
                """).param("id", id).param("s", m.storeId()).param("n", number).param("c", req.companyId())
                .param("inv", invoice).param("t", total).param("p", paid).param("day", today)
                .param("note", blank(req.note())).param("by", m.userId()).param("req", req.clientRequestId()).update();

        for (ItemRequest it : req.items()) {
            Product p = products.get(it.productId());
            BigDecimal q = qty(it);
            BigDecimal cost = Money.of(it.unitCost());
            jdbc.sql("""
                    INSERT INTO purchase_items (id, purchase_id, store_id, product_id, name, quantity, unit_cost, line_total)
                    VALUES (:id, :pur, :s, :p, :n, :q, :c, :lt)
                    """).param("id", UUID.randomUUID()).param("pur", id).param("s", m.storeId()).param("p", p.id())
                    .param("n", p.name()).param("q", q).param("c", cost).param("lt", Money.of(q.multiply(cost))).update();
            // the price is averaged over the stock as it is right now (an earlier line of the same product counts)
            BigDecimal before = jdbc.sql("SELECT stock_qty FROM products WHERE id = :id").param("id", p.id()).query(BigDecimal.class).single();
            BigDecimal oldPrice = jdbc.sql("SELECT purchase_price FROM products WHERE id = :id").param("id", p.id()).query(BigDecimal.class).single();
            inventory.move(m, p.id(), "RECEIPT", q, cost, null, number + (invoice != null ? " · " + invoice : ""));
            if (cost.compareTo(oldPrice) != 0) {
                BigDecimal oldQty = before.max(BigDecimal.ZERO);
                BigDecimal avg = oldQty.signum() == 0 ? cost
                        : oldQty.multiply(oldPrice).add(q.multiply(cost)).divide(oldQty.add(q), 2, RoundingMode.HALF_UP);
                jdbc.sql("UPDATE products SET purchase_price = :pp, version = version + 1 WHERE id = :id")
                        .param("pp", avg).param("id", p.id()).update();
            }
        }

        if (req.companyId() != null && total.signum() > 0) {
            var charged = accounts.charge(false, req.companyId(), new PartyAccountRequests.Charge(DebtType.COMPANY_PAYABLE, total,
                    today, invoice != null ? invoice : number, "Mal alışı " + number, null, req.dueDate()));
            jdbc.sql("UPDATE purchases SET debt_id = :d WHERE id = :id").param("d", charged.debt().id()).param("id", id).update();
            if (paid.signum() > 0) {
                accounts.pay(false, req.companyId(), new PartyAccountRequests.Pay(DebtType.COMPANY_PAYABLE, paid, method, null,
                        "Mal alışı " + number, null, null, req.cashAccountId()));
            }
        } else if (paid.signum() > 0) {
            if (paid.compareTo(total) != 0) {
                throw ApiException.badRequest("SUPPLIER_REQUIRED", "Borcla və ya qismən ödənişli alış üçün təchizatçı seçin");
            }
            UUID acc = cash.accountFor(m.storeId(), method == PaymentMethod.CASH ? "CASH" : "CARD", req.cashAccountId(), m.userId());
            BigDecimal balance = cash.balance(m.storeId(), acc);
            if (balance.compareTo(paid) < 0) {
                throw ApiException.conflict("INSUFFICIENT_FUNDS", "Hesabda kifayət qədər pul yoxdur (qalıq: " + balance + " AZN)");
            }
            cash.post(m.storeId(), acc, "OUT", paid, "PURCHASE", today, null, null, "Mal alışı " + number, m.userId());
        }
        audit.log(m.storeId(), m.userId(), "PURCHASE_CREATED", "PURCHASE", id.toString(),
                number + ": " + total + " AZN (ödənildi " + paid + ")");
        return load(m.storeId(), id);
    }

    public List<Purchase> list(LocalDate from, LocalDate to, UUID companyId, int limit) {
        CurrentMember m = inventory.requireInventory();
        StringBuilder where = new StringBuilder(" WHERE p.store_id = :s");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("s", m.storeId());
        if (from != null) { where.append(" AND p.received_on >= :f"); params.put("f", from); }
        if (to != null) { where.append(" AND p.received_on <= :t"); params.put("t", to); }
        if (companyId != null) { where.append(" AND p.company_id = :c"); params.put("c", companyId); }
        var spec = jdbc.sql(SELECT + where + " ORDER BY p.created_at DESC LIMIT " + Math.min(Math.max(limit, 1), 500));
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec.query((rs, n) -> map(rs, List.of())).list();
    }

    public Purchase get(UUID id) {
        return load(inventory.requireInventory().storeId(), id);
    }

    private Purchase load(UUID storeId, UUID id) {
        List<Item> items = jdbc.sql("""
                SELECT id, product_id, name, quantity, unit_cost, line_total FROM purchase_items
                WHERE purchase_id = :id ORDER BY name""").param("id", id)
                .query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("name"), rs.getBigDecimal("quantity"), Money.of(rs.getBigDecimal("unit_cost")),
                        Money.of(rs.getBigDecimal("line_total")))).list();
        return jdbc.sql(SELECT + " WHERE p.store_id = :s AND p.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> map(rs, items)).optional()
                .orElseThrow(() -> ApiException.notFound("Alış tapılmadı"));
    }

    private static final String SELECT = """
            SELECT p.id, p.number, p.company_id, c.name AS company_name, p.supplier_invoice, p.total, p.paid, p.debt_id,
                   p.received_on, p.note, u.full_name AS created_by_name, p.created_at
            FROM purchases p LEFT JOIN companies c ON c.id = p.company_id JOIN users u ON u.id = p.created_by""";

    private static Purchase map(java.sql.ResultSet rs, List<Item> items) throws java.sql.SQLException {
        BigDecimal total = Money.of(rs.getBigDecimal("total"));
        BigDecimal paid = Money.of(rs.getBigDecimal("paid"));
        return new Purchase(rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("company_id", UUID.class),
                rs.getString("company_name"), rs.getString("supplier_invoice"), total, paid,
                rs.getObject("company_id") != null ? Money.of(total.subtract(paid)) : Money.zero(),
                rs.getObject("debt_id", UUID.class), rs.getObject("received_on", LocalDate.class), rs.getString("note"),
                rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class), items);
    }

    private static BigDecimal qty(ItemRequest it) {
        return it.quantity().setScale(3, RoundingMode.HALF_UP);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
