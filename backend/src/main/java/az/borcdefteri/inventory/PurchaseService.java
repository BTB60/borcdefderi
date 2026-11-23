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
 * "Bizim tədarükçüyə borcumuz" and the part paid now is paid off it at once; without one the paid part leaves the till.
 */
@Service
public class PurchaseService {

    public record ItemRequest(
            @NotNull UUID productId,
            @NotNull @DecimalMin(value = "0.001", message = "Miqdar 0-dan böyük olmalıdır")
            @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @NotNull @DecimalMin(value = "0.00", message = "Alış qiyməti mənfi ola bilməz")
            @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            /** Son istifadə tarixi of this batch (optional). */
            LocalDate expiryDate,
            /** New sale price of the product, set with the receipt (optional; null = keep). */
            @DecimalMin(value = "0.00", message = "Satış qiyməti mənfi ola bilməz")
            @Digits(integer = 12, fraction = 2) BigDecimal salePrice) {

        public ItemRequest(UUID productId, BigDecimal quantity, BigDecimal unitCost) {
            this(productId, quantity, unitCost, null, null);
        }

        public ItemRequest(UUID productId, BigDecimal quantity, BigDecimal unitCost, LocalDate expiryDate) {
            this(productId, quantity, unitCost, expiryDate, null);
        }
    }

    /** Tədarükçünü dəyiş: the right supplier of a receipt entered under the wrong one (or with none). */
    public record SupplierChange(@NotNull(message = "Tədarükçünü seçin") UUID companyId) {}

    public record PurchaseRequest(
            UUID companyId,
            @NotEmpty(message = "Ən azı bir məhsul əlavə edin") @Size(max = 300) List<@Valid ItemRequest> items,
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal paid,
            PaymentMethod paymentMethod,
            UUID cashAccountId,
            @Size(max = 60, message = "Qaimə nömrəsi ən çox 60 simvol ola bilər") String supplierInvoice,
            LocalDate dueDate,
            @Size(max = 500) String note,
            UUID clientRequestId,
            /** The supplier order these goods came for (optional): its lines count what came. */
            UUID purchaseOrderId) {

        public PurchaseRequest(UUID companyId, List<ItemRequest> items, BigDecimal paid, PaymentMethod paymentMethod,
                               UUID cashAccountId, String supplierInvoice, LocalDate dueDate, String note, UUID clientRequestId) {
            this(companyId, items, paid, paymentMethod, cashAccountId, supplierInvoice, dueDate, note, clientRequestId, null);
        }
    }

    public record Item(UUID id, UUID productId, String name, BigDecimal quantity, BigDecimal unitCost, BigDecimal lineTotal,
                       BigDecimal returnedQty, LocalDate expiryDate) {}

    public record Purchase(UUID id, String number, UUID companyId, String companyName, String supplierInvoice,
                           BigDecimal total, BigDecimal paid, BigDecimal unpaid, UUID debtId, LocalDate receivedOn, String note,
                           String createdByName, OffsetDateTime createdAt, List<Item> items, BigDecimal returnedAmount) {}

    public record ReturnItem(@NotNull UUID itemId,
                             @NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal quantity) {}

    /** Goods sent back to the supplier. Empty items = everything not returned yet (cancel the receipt). */
    public record ReturnRequest(List<@Valid ReturnItem> items, Boolean refundToCash, @Size(max = 500) String reason) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final InventoryService inventory;
    private final CashService cash;
    private final PartyAccountService accounts;
    private final AuditService audit;
    private final az.borcdefteri.debts.DebtService debts;
    private org.springframework.beans.factory.ObjectProvider<PurchaseOrderService> orders;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrders(org.springframework.beans.factory.ObjectProvider<PurchaseOrderService> orders) {
        this.orders = orders;
    }

    public PurchaseService(JdbcClient jdbc, TenantService tenant, InventoryService inventory, CashService cash,
                           PartyAccountService accounts, AuditService audit, az.borcdefteri.debts.DebtService debts) {
        this.debts = debts;
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
            if (it.expiryDate() != null && it.expiryDate().isBefore(today.minusYears(1))) {
                throw ApiException.badRequest("INVALID_EXPIRY", "Son istifadə tarixi düzgün deyil");
            }
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
                    INSERT INTO purchase_items (id, purchase_id, store_id, product_id, name, quantity, unit_cost, line_total, expiry_date)
                    VALUES (:id, :pur, :s, :p, :n, :q, :c, :lt, :exp)
                    """).param("id", UUID.randomUUID()).param("pur", id).param("s", m.storeId()).param("p", p.id())
                    .param("n", p.name()).param("q", q).param("c", cost).param("lt", Money.of(q.multiply(cost)))
                    .param("exp", it.expiryDate()).update();
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
            if (it.salePrice() != null && Money.of(it.salePrice()).compareTo(Money.of(p.salePrice())) != 0) {
                jdbc.sql("UPDATE products SET sale_price = :sp, updated_at = now(), version = version + 1 WHERE id = :id")
                        .param("sp", Money.of(it.salePrice())).param("id", p.id()).update();
                audit.log(m.storeId(), m.userId(), "PRODUCT_PRICE_CHANGED", "PRODUCT", p.id().toString(),
                        p.name() + ": satış " + p.salePrice() + " -> " + Money.of(it.salePrice()) + " (" + number + ")");
            }
        }

        if (req.purchaseOrderId() != null) {
            Map<UUID, BigDecimal> got = new LinkedHashMap<>();
            for (ItemRequest it : req.items()) got.merge(it.productId(), qty(it), BigDecimal::add);
            orders.getObject().received(m, req.purchaseOrderId(), got);
            jdbc.sql("UPDATE purchases SET purchase_order_id = :o WHERE id = :id").param("o", req.purchaseOrderId()).param("id", id).update();
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

    /**
     * Returns goods to the supplier: stock goes down (ISSUE), the supplier debt of this receipt is lowered by their
     * value, and whatever is left over (already paid) comes back to the till when {@code refundToCash} (always
     * without a supplier).
     */
    @Transactional
    public Purchase returnItems(UUID id, ReturnRequest req) {
        CurrentMember m = inventory.requireInventory();
        jdbc.sql("SELECT id FROM purchases WHERE id = :id AND store_id = :s FOR UPDATE").param("id", id).param("s", m.storeId())
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Alış tapılmadı"));
        Purchase p = load(m.storeId(), id);
        Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            for (Item it : p.items()) {
                BigDecimal left = it.quantity().subtract(it.returnedQty());
                if (left.signum() > 0) wanted.put(it.id(), left);
            }
        } else {
            for (ReturnItem r : req.items()) wanted.merge(r.itemId(), r.quantity().setScale(3, RoundingMode.HALF_UP), BigDecimal::add);
        }
        if (wanted.isEmpty()) throw ApiException.conflict("NOTHING_TO_RETURN", "Bu alışda qaytarılacaq mal qalmayıb");
        BigDecimal amount = Money.zero();
        for (var e : wanted.entrySet()) {
            Item it = p.items().stream().filter(x -> x.id().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("INVALID_ITEM", "Bu alışda belə məhsul yoxdur"));
            if (e.getValue().compareTo(it.quantity().subtract(it.returnedQty())) > 0) {
                throw ApiException.badRequest("RETURN_EXCEEDS", "“" + it.name() + "”: alınandan çox qaytarmaq olmaz");
            }
            if (it.productId() != null) {
                inventory.lockProduct(m.storeId(), it.productId());
                inventory.move(m, it.productId(), "ISSUE", e.getValue().negate(), it.unitCost(), null, p.number() + " təchizatçıya qaytarıldı");
            }
            jdbc.sql("UPDATE purchase_items SET returned_qty = returned_qty + :q WHERE id = :id").param("q", e.getValue()).param("id", it.id()).update();
            amount = amount.add(Money.of(e.getValue().multiply(it.unitCost())));
        }
        amount = Money.of(amount);
        BigDecimal debtLowered = Money.zero();
        if (p.companyId() != null && p.debtId() != null) {
            try {
                var d = debts.lockAndLoad(m, p.debtId());
                debtLowered = Money.of(amount.min(d.remainingAmount()).max(BigDecimal.ZERO));
                if (debtLowered.signum() > 0) debts.decreaseLocked(m, d, debtLowered, p.number() + " təchizatçıya qaytarma");
            } catch (ApiException e) {
                if (!"DEBT_CANCELLED".equals(e.getCode())) throw e;
            }
        }
        BigDecimal refund = Money.of(amount.subtract(debtLowered));
        boolean toCash = p.companyId() == null || (req != null && Boolean.TRUE.equals(req.refundToCash()));
        if (refund.signum() > 0 && toCash) {
            UUID acc = cash.accountFor(m.storeId(), "CASH", null, m.userId());
            cash.post(m.storeId(), acc, "IN", refund, "PURCHASE", tenant.today(), null, null, p.number() + " qaytarma: pul geri alındı", m.userId());
        }
        jdbc.sql("UPDATE purchases SET returned_amount = returned_amount + :a WHERE id = :id").param("a", amount).param("id", id).update();
        audit.log(m.storeId(), m.userId(), "PURCHASE_RETURNED", "PURCHASE", id.toString(),
                p.number() + ": " + amount + " AZN (borcdan " + debtLowered + ", kassaya " + (toCash ? refund : Money.zero()) + ")");
        return load(m.storeId(), id);
    }

    /**
     * Tədarükçünü dəyiş. Goods, stock and prices stay as they are; only who the store owes changes. What this receipt
     * still adds to the old supplier's balance (its total less what was paid at the time and the goods returned) is
     * taken off the old supplier and put on the new one. A receipt without a supplier (paid in full then) just gets one.
     * The payment made with the receipt stays in the old supplier's history, where it now nets to zero.
     */
    @Transactional
    public Purchase changeSupplier(UUID id, SupplierChange req) {
        CurrentMember m = inventory.requireInventory();
        if (!m.has(DebtType.COMPANY_PAYABLE.permission())) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Tədarükçünü dəyişmək üçün “Bizim tədarükçüyə borcumuz” icazəsi lazımdır");
        }
        jdbc.sql("SELECT id FROM purchases WHERE id = :id AND store_id = :s FOR UPDATE").param("id", id).param("s", m.storeId())
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Alış tapılmadı"));
        record Row(UUID company, String companyName, String number, String invoice, BigDecimal total, BigDecimal paid, BigDecimal returned) {}
        Row r = jdbc.sql("""
                SELECT p.company_id, c.name, p.number, p.supplier_invoice, p.total, p.paid, p.returned_amount
                FROM purchases p LEFT JOIN companies c ON c.id = p.company_id WHERE p.id = :id""").param("id", id)
                .query((rs, n) -> new Row(rs.getObject("company_id", UUID.class), rs.getString("name"), rs.getString("number"),
                        rs.getString("supplier_invoice"), Money.of(rs.getBigDecimal("total")), Money.of(rs.getBigDecimal("paid")),
                        Money.of(rs.getBigDecimal("returned_amount")))).single();
        String newName = jdbc.sql("SELECT name FROM companies WHERE id = :id AND store_id = :s")
                .param("id", req.companyId()).param("s", m.storeId()).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Tədarükçü tapılmadı"));
        if (req.companyId().equals(r.company())) throw ApiException.badRequest("SAME_SUPPLIER", "Bu qaimə artıq həmin tədarükçüdədir");

        BigDecimal moved = Money.zero();
        UUID newDebt = null;
        if (r.company() != null) {
            moved = Money.of(r.total().subtract(r.paid()).subtract(r.returned()).max(BigDecimal.ZERO));
            // paid later on the receipt's own debt ("Borcu ödə"): only what is still open moves
            BigDecimal debtLeft = jdbc.sql("SELECT d.remaining_amount FROM purchases p JOIN debts d ON d.id = p.debt_id WHERE p.id = :id")
                    .param("id", id).query(BigDecimal.class).optional().orElse(null);
            if (debtLeft != null) moved = Money.of(moved.min(debtLeft));
            if (moved.signum() > 0) {
                List<az.borcdefteri.debts.DebtService.DebtView> old = debts.lockForParty(m, DebtType.COMPANY_PAYABLE, r.company()).stream()
                        .filter(d -> d.remainingAmount().signum() > 0).toList();
                BigDecimal balance = Money.of(old.stream().map(az.borcdefteri.debts.DebtService.DebtView::remainingAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                if (balance.compareTo(moved) < 0) {
                    throw ApiException.conflict("SUPPLIER_PAID_OFF", "“" + r.companyName() + "” üzrə qalıq borc (" + balance
                            + " AZN) bu qaimənin borcundan (" + moved + " AZN) azdır: ona sonradan ödəniş edilib. "
                            + "Əvvəlcə həmin ödənişi ləğv edin və ya düzgün tədarükçüyə köçürün");
                }
                String note = r.number() + ": tədarükçü dəyişdirildi → " + newName;
                BigDecimal left = moved;
                for (int i = old.size() - 1; i >= 0 && left.signum() > 0; i--) { // the newest debt carries this receipt
                    var d = old.get(i);
                    BigDecimal part = Money.of(left.min(d.remainingAmount()));
                    debts.decreaseLocked(m, d, part, note);
                    left = Money.of(left.subtract(part));
                }
                // a debt of its own, so the receipt keeps showing exactly what is still owed for it
                newDebt = debts.create(new az.borcdefteri.debts.DebtRequest(DebtType.COMPANY_PAYABLE, req.companyId(), moved,
                        tenant.today(), null, "Mal alışı " + r.number() + " (əvvəl “" + r.companyName() + "” adına yazılmışdı)", null),
                        r.invoice() != null ? r.invoice() : r.number()).id();
            }
        }
        jdbc.sql("UPDATE purchases SET company_id = :c, debt_id = :d WHERE id = :id")
                .param("c", req.companyId()).param("d", newDebt).param("id", id).update();
        audit.log(m.storeId(), m.userId(), "PURCHASE_SUPPLIER_CHANGED", "PURCHASE", id.toString(),
                r.number() + ": " + (r.companyName() != null ? r.companyName() : "tədarükçüsüz") + " → " + newName
                        + (moved.signum() > 0 ? ", borc " + moved + " AZN köçürüldü" : ""));
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
                SELECT id, product_id, name, quantity, unit_cost, line_total, returned_qty, expiry_date FROM purchase_items
                WHERE purchase_id = :id ORDER BY name""").param("id", id)
                .query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("name"), rs.getBigDecimal("quantity"), Money.of(rs.getBigDecimal("unit_cost")),
                        Money.of(rs.getBigDecimal("line_total")), rs.getBigDecimal("returned_qty"),
                        rs.getObject("expiry_date", LocalDate.class))).list();
        return jdbc.sql(SELECT + " WHERE p.store_id = :s AND p.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> map(rs, items)).optional()
                .orElseThrow(() -> ApiException.notFound("Alış tapılmadı"));
    }

    private static final String SELECT = """
            SELECT p.id, p.number, p.company_id, c.name AS company_name, p.supplier_invoice, p.total, p.paid, p.debt_id,
                   p.received_on, p.note, u.full_name AS created_by_name, p.created_at, p.returned_amount,
                   d.paid_amount AS debt_paid, d.remaining_amount AS debt_remaining
            FROM purchases p LEFT JOIN companies c ON c.id = p.company_id JOIN users u ON u.id = p.created_by
            LEFT JOIN debts d ON d.id = p.debt_id""";

    private static Purchase map(java.sql.ResultSet rs, List<Item> items) throws java.sql.SQLException {
        BigDecimal total = Money.of(rs.getBigDecimal("total"));
        BigDecimal paid = Money.of(rs.getBigDecimal("paid"));
        BigDecimal unpaid = rs.getObject("company_id") != null ? Money.of(total.subtract(paid)) : Money.zero();
        // with a supplier debt the debt is the truth: later payments (here or in "Bizim tədarükçüyə borcumuz") and
        // returns to the supplier show on the receipt
        if (rs.getBigDecimal("debt_remaining") != null) {
            BigDecimal remaining = Money.of(rs.getBigDecimal("debt_remaining"));
            // a receipt moved to another supplier ("Tədarükçünü dəyiş") has a debt for its open part only, so what was
            // paid is the rest of the receipt; otherwise the debt's own payments
            BigDecimal rest = Money.of(total.subtract(Money.of(rs.getBigDecimal("returned_amount"))).subtract(remaining));
            paid = Money.of(rs.getBigDecimal("debt_paid")).max(rest);
            unpaid = remaining;
        }
        return new Purchase(rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("company_id", UUID.class),
                rs.getString("company_name"), rs.getString("supplier_invoice"), total, paid, unpaid,
                rs.getObject("debt_id", UUID.class), rs.getObject("received_on", LocalDate.class), rs.getString("note"),
                rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class), items,
                Money.of(rs.getBigDecimal("returned_amount")));
    }

    private static BigDecimal qty(ItemRequest it) {
        return it.quantity().setScale(3, RoundingMode.HALF_UP);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
