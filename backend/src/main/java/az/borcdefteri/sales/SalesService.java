package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.cash.CashService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.common.Search;
import az.borcdefteri.customers.PartyAccountRequests;
import az.borcdefteri.customers.PartyAccountService;
import az.borcdefteri.debts.DebtService;
import az.borcdefteri.debts.DebtService.DebtView;
import az.borcdefteri.inventory.InventoryService;
import az.borcdefteri.inventory.InventoryService.Product;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sales: one transaction updates stock (SALE movements), books the paid part into the cash desk / card account
 * (kind SALE) and adds the unpaid part to the customer's account (a debt). Sales revenue is the sale total; later
 * payments of that debt are debt collections, never revenue again.
 * Returns put goods back, lower the sale's debt first and refund the rest in money.
 */
@Service
public class SalesService {

    public static final String PERM_SALES = "SALES";

    public record ItemRequest(
            @jakarta.validation.constraints.NotNull UUID productId,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0.001", message = "Miqdar 0-dan böyük olmalıdır")
            @jakarta.validation.constraints.Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal unitPrice,
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal discount) {}

    public record SaleRequest(
            @jakarta.validation.constraints.NotEmpty(message = "Səbət boşdur")
            @jakarta.validation.constraints.Size(max = 200) List<@jakarta.validation.Valid ItemRequest> items,
            UUID customerId,
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal discount,
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal paidCash,
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal paidCard,
            UUID cashAccountId,
            UUID cardAccountId,
            @jakarta.validation.constraints.Size(max = 500) String note,
            UUID clientRequestId) {}

    public record ReturnItem(@jakarta.validation.constraints.NotNull UUID saleItemId,
                             @jakarta.validation.constraints.NotNull
                             @jakarta.validation.constraints.DecimalMin("0.001")
                             @jakarta.validation.constraints.Digits(integer = 11, fraction = 3) BigDecimal quantity) {}

    public record ReturnRequest(
            @jakarta.validation.constraints.NotEmpty List<@jakarta.validation.Valid ReturnItem> items,
            UUID refundAccountId,
            @jakarta.validation.constraints.Size(max = 500) String reason) {}

    public record CancelRequest(UUID refundAccountId, @jakarta.validation.constraints.Size(max = 500) String reason) {}

    public record Item(UUID id, UUID productId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
                       BigDecimal lineTotal, BigDecimal unitCost, BigDecimal returnedQty) {}

    public record ReturnView(UUID id, BigDecimal amount, BigDecimal debtReduced, BigDecimal refunded, LocalDate returnedOn,
                             String reason, String createdByName, OffsetDateTime createdAt) {}

    public record Sale(UUID id, String number, UUID customerId, String customerName, String customerCode, String status,
                       BigDecimal subtotal, BigDecimal discount, BigDecimal total, BigDecimal paidCash, BigDecimal paidCard,
                       BigDecimal creditAmount, BigDecimal returnedAmount, BigDecimal costTotal, UUID debtId, LocalDate soldOn,
                       String note, String createdByName, OffsetDateTime createdAt, List<Item> items, List<ReturnView> returns) {}

    public record SalePage(List<Sale> items, long total, BigDecimal sum, int page, int size) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final InventoryService inventory;
    private final CashService cash;
    private final PartyAccountService accounts;
    private final DebtService debts;
    private final AuditService audit;

    public SalesService(JdbcClient jdbc, TenantService tenant, InventoryService inventory, CashService cash,
                        PartyAccountService accounts, DebtService debts, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.inventory = inventory;
        this.cash = cash;
        this.accounts = accounts;
        this.debts = debts;
        this.audit = audit;
    }

    private CurrentMember requireSales() {
        return tenant.requirePermission(PERM_SALES);
    }

    @Transactional
    public Sale create(SaleRequest req) {
        CurrentMember m = requireSales();
        if (req.clientRequestId() != null) {
            var dup = jdbc.sql("SELECT id FROM sales WHERE store_id = :s AND client_request_id = :r")
                    .param("s", m.storeId()).param("r", req.clientRequestId()).query(UUID.class).optional();
            if (dup.isPresent()) return load(m.storeId(), dup.get()); // double tap: the first sale, not a second one
        }
        LocalDate today = tenant.today();
        // lock products in a stable order so two sales of the same goods cannot deadlock
        Map<UUID, Product> products = new LinkedHashMap<>();
        req.items().stream().map(ItemRequest::productId).distinct().sorted(Comparator.naturalOrder())
                .forEach(id -> products.put(id, inventory.lockProduct(m.storeId(), id)));

        record Line(ItemRequest req, Product p, BigDecimal qty, BigDecimal price, BigDecimal disc, BigDecimal total) {}
        List<Line> lines = new ArrayList<>();
        BigDecimal subtotal = Money.zero();
        for (ItemRequest it : req.items()) {
            Product p = products.get(it.productId());
            if (!p.active()) throw ApiException.badRequest("PRODUCT_INACTIVE", "“" + p.name() + "” satışda deyil (deaktiv)");
            BigDecimal qty = it.quantity().setScale(3, RoundingMode.HALF_UP);
            BigDecimal price = Money.of(it.unitPrice() != null ? it.unitPrice() : p.salePrice());
            BigDecimal gross = Money.of(qty.multiply(price));
            BigDecimal disc = Money.of(it.discount() != null ? it.discount() : BigDecimal.ZERO);
            if (disc.compareTo(gross) > 0) throw ApiException.badRequest("INVALID_DISCOUNT", "Endirim məbləğdən çox ola bilməz");
            BigDecimal total = Money.of(gross.subtract(disc));
            lines.add(new Line(it, p, qty, price, disc, total));
            subtotal = subtotal.add(total);
        }
        subtotal = Money.of(subtotal);
        BigDecimal discount = Money.of(req.discount() != null ? req.discount() : BigDecimal.ZERO);
        if (discount.compareTo(subtotal) > 0) throw ApiException.badRequest("INVALID_DISCOUNT", "Endirim məbləğdən çox ola bilməz");
        BigDecimal total = Money.of(subtotal.subtract(discount));
        BigDecimal paidCash = Money.of(req.paidCash() != null ? req.paidCash() : BigDecimal.ZERO);
        BigDecimal paidCard = Money.of(req.paidCard() != null ? req.paidCard() : BigDecimal.ZERO);
        BigDecimal paid = paidCash.add(paidCard);
        if (paid.compareTo(total) > 0) {
            throw ApiException.badRequest("OVERPAID", "Ödənilən məbləğ satışdan çox ola bilməz (qalığı müştəriyə qaytarın)");
        }
        BigDecimal credit = Money.of(total.subtract(paid));
        if (credit.signum() > 0 && req.customerId() == null) {
            throw ApiException.badRequest("CUSTOMER_REQUIRED", "Borcla və ya qismən ödənişli satış üçün müştəri seçin");
        }

        UUID saleId = UUID.randomUUID();
        String number = "S-" + String.format("%06d", jdbc.sql("SELECT nextval('sale_number_seq')").query(Long.class).single());
        BigDecimal costTotal = Money.zero();
        for (Line l : lines) costTotal = costTotal.add(Money.of(l.qty().multiply(l.p().purchasePrice())));
        jdbc.sql("""
                INSERT INTO sales (id, store_id, number, customer_id, status, subtotal, discount, total, paid_cash, paid_card,
                                   credit_amount, cost_total, sold_on, note, created_by, client_request_id)
                VALUES (:id, :s, :n, :c, 'COMPLETED', :sub, :d, :t, :pc, :pk, :cr, :cost, :day, :note, :by, :req)
                """).param("id", saleId).param("s", m.storeId()).param("n", number).param("c", req.customerId())
                .param("sub", subtotal).param("d", discount).param("t", total).param("pc", paidCash).param("pk", paidCard)
                .param("cr", credit).param("cost", Money.of(costTotal)).param("day", today)
                .param("note", req.note() == null || req.note().isBlank() ? null : req.note().trim()).param("by", m.userId())
                .param("req", req.clientRequestId()).update();
        for (Line l : lines) {
            jdbc.sql("""
                    INSERT INTO sale_items (id, sale_id, store_id, product_id, name, quantity, unit_price, discount, line_total, unit_cost)
                    VALUES (:id, :sale, :s, :p, :n, :q, :up, :d, :lt, :uc)
                    """).param("id", UUID.randomUUID()).param("sale", saleId).param("s", m.storeId()).param("p", l.p().id())
                    .param("n", l.p().name()).param("q", l.qty()).param("up", l.price()).param("d", l.disc())
                    .param("lt", l.total()).param("uc", l.p().purchasePrice()).update();
            inventory.move(m, l.p().id(), "SALE", l.qty().negate(), l.p().purchasePrice(), saleId, number);
        }
        if (paidCash.signum() > 0) {
            UUID acc = cash.accountFor(m.storeId(), "CASH", req.cashAccountId(), m.userId());
            cash.postSale(m.storeId(), acc, "IN", paidCash, "SALE", today, saleId, number, m.userId());
        }
        if (paidCard.signum() > 0) {
            UUID acc = cash.accountFor(m.storeId(), "CARD", req.cardAccountId(), m.userId());
            cash.postSale(m.storeId(), acc, "IN", paidCard, "SALE", today, saleId, number, m.userId());
        }
        if (credit.signum() > 0) {
            // the unpaid part goes onto the customer's running account (same as "Borc əlavə et")
            var res = accounts.charge(true, req.customerId(), new PartyAccountRequests.Charge(null, credit, today, number,
                    "Satış " + number, null, null));
            jdbc.sql("UPDATE sales SET debt_id = :d WHERE id = :id").param("d", res.debt().id()).param("id", saleId).update();
        } else if (req.customerId() != null) {
            if (jdbc.sql("SELECT COUNT(*) FROM customers WHERE id = :c AND store_id = :s").param("c", req.customerId())
                    .param("s", m.storeId()).query(Long.class).single() == 0) {
                throw ApiException.notFound("Müştəri tapılmadı");
            }
        }
        audit.log(m.storeId(), m.userId(), "SALE_CREATED", "SALE", saleId.toString(),
                number + ": " + total + " AZN (nağd " + paidCash + ", kart " + paidCard + ", borc " + credit + ")");
        return load(m.storeId(), saleId);
    }

    @Transactional
    public Sale returnItems(UUID saleId, ReturnRequest req) {
        CurrentMember m = requireSales();
        Sale sale = lock(m.storeId(), saleId);
        if (!"COMPLETED".equals(sale.status())) throw ApiException.conflict("SALE_CANCELLED", "Satış artıq ləğv edilib");
        Map<UUID, Item> byId = new LinkedHashMap<>();
        sale.items().forEach(i -> byId.put(i.id(), i));
        BigDecimal amount = Money.zero(), cost = Money.zero();
        for (ReturnItem r : req.items()) {
            Item it = byId.get(r.saleItemId());
            if (it == null) throw ApiException.notFound("Satış sətri tapılmadı");
            BigDecimal q = r.quantity().setScale(3, RoundingMode.HALF_UP);
            if (q.compareTo(it.quantity().subtract(it.returnedQty())) > 0) {
                throw ApiException.badRequest("RETURN_EXCEEDS", "“" + it.name() + "” üçün qaytarılacaq miqdar satılandan çox ola bilməz");
            }
            boolean last = q.add(it.returnedQty()).compareTo(it.quantity()) == 0;
            // the last return of a line takes what is left of it, so rounding never leaves cents behind
            BigDecimal already = Money.of(it.lineTotal().multiply(it.returnedQty()).divide(it.quantity(), 2, RoundingMode.HALF_UP));
            BigDecimal lineAmount = last ? Money.of(it.lineTotal().subtract(already))
                    : Money.of(it.lineTotal().multiply(q).divide(it.quantity(), 2, RoundingMode.HALF_UP));
            amount = amount.add(lineAmount);
            cost = cost.add(Money.of(it.unitCost().multiply(q)));
            jdbc.sql("UPDATE sale_items SET returned_qty = returned_qty + :q WHERE id = :id").param("q", q).param("id", it.id()).update();
            inventory.move(m, it.productId(), "SALE_RETURN", q, it.unitCost(), saleId, sale.number() + " qaytarma");
        }
        // the whole sale's discount is shared in proportion to what is returned
        if (sale.discount().signum() > 0 && sale.subtotal().signum() > 0) {
            amount = Money.of(amount.multiply(sale.total()).divide(sale.subtotal(), 2, RoundingMode.HALF_UP));
        }
        BigDecimal maxLeft = Money.of(sale.total().subtract(sale.returnedAmount()));
        if (amount.compareTo(maxLeft) > 0) amount = maxLeft;
        settleReturn(m, sale, amount, cost, req.refundAccountId(), req.reason());
        return load(m.storeId(), saleId);
    }

    @Transactional
    public Sale cancel(UUID saleId, CancelRequest req) {
        CurrentMember m = requireSales();
        Sale sale = lock(m.storeId(), saleId);
        if (!"COMPLETED".equals(sale.status())) throw ApiException.conflict("SALE_CANCELLED", "Satış artıq ləğv edilib");
        BigDecimal cost = Money.zero();
        for (Item it : sale.items()) {
            BigDecimal left = it.quantity().subtract(it.returnedQty());
            if (left.signum() > 0) {
                jdbc.sql("UPDATE sale_items SET returned_qty = quantity WHERE id = :id").param("id", it.id()).update();
                inventory.move(m, it.productId(), "SALE_RETURN", left, it.unitCost(), saleId, sale.number() + " ləğv");
                cost = cost.add(Money.of(it.unitCost().multiply(left)));
            }
        }
        BigDecimal amount = Money.of(sale.total().subtract(sale.returnedAmount()));
        if (amount.signum() > 0) settleReturn(m, sale, amount, cost, req == null ? null : req.refundAccountId(),
                req == null || req.reason() == null ? "Satış ləğv edildi" : req.reason());
        jdbc.sql("UPDATE sales SET status = 'CANCELLED', cancelled_at = now() WHERE id = :id").param("id", saleId).update();
        audit.log(m.storeId(), m.userId(), "SALE_CANCELLED", "SALE", saleId.toString(), sale.number());
        return load(m.storeId(), saleId);
    }

    /** Lowers the sale's still-unpaid debt first, refunds the rest in money; records the return. */
    private void settleReturn(CurrentMember m, Sale sale, BigDecimal amount, BigDecimal cost, UUID refundAccountId, String reason) {
        BigDecimal debtReduced = Money.zero();
        if (sale.debtId() != null) {
            DebtView d = debts.lockAndLoad(m, sale.debtId());
            BigDecimal alreadyReduced = jdbc.sql("SELECT COALESCE(SUM(debt_reduced), 0) FROM sale_returns WHERE sale_id = :id")
                    .param("id", sale.id()).query(BigDecimal.class).single();
            BigDecimal creditLeft = Money.of(sale.creditAmount().subtract(alreadyReduced));
            debtReduced = Money.of(amount.min(creditLeft).min(d.remainingAmount()).max(BigDecimal.ZERO));
            if (debtReduced.signum() > 0) {
                debts.decreaseLocked(m, d, debtReduced, sale.number() + " qaytarma");
            }
        }
        BigDecimal refund = Money.of(amount.subtract(debtReduced));
        UUID refundAccount = null;
        if (refund.signum() > 0) {
            String method = sale.paidCash().signum() > 0 || sale.paidCard().signum() == 0 ? "CASH" : "CARD";
            refundAccount = cash.accountFor(m.storeId(), method, refundAccountId, m.userId());
            cash.postSale(m.storeId(), refundAccount, "OUT", refund, "SALE_REFUND", tenant.today(), sale.id(),
                    sale.number() + " qaytarma", m.userId());
        }
        jdbc.sql("""
                INSERT INTO sale_returns (id, sale_id, store_id, amount, cost_amount, debt_reduced, refunded, refund_account_id,
                                          returned_on, reason, created_by)
                VALUES (:id, :sale, :s, :a, :c, :dr, :rf, :acc, :day, :reason, :by)
                """).param("id", UUID.randomUUID()).param("sale", sale.id()).param("s", m.storeId()).param("a", amount)
                .param("c", Money.of(cost)).param("dr", debtReduced).param("rf", refund).param("acc", refundAccount)
                .param("day", tenant.today()).param("reason", reason == null || reason.isBlank() ? null : reason.trim())
                .param("by", m.userId()).update();
        jdbc.sql("UPDATE sales SET returned_amount = returned_amount + :a WHERE id = :id").param("a", amount).param("id", sale.id()).update();
        audit.log(m.storeId(), m.userId(), "SALE_RETURNED", "SALE", sale.id().toString(),
                sale.number() + ": " + amount + " AZN (borcdan " + debtReduced + ", qaytarılan pul " + refund + ")");
    }

    // ----- read -----

    public SalePage list(LocalDate from, LocalDate to, String q, UUID customerId, String status, int page, int size) {
        CurrentMember m = requireSales();
        StringBuilder where = new StringBuilder(" WHERE s.store_id = :st");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("st", m.storeId());
        if (from != null) { where.append(" AND s.sold_on >= :f"); p.put("f", from); }
        if (to != null) { where.append(" AND s.sold_on <= :t"); p.put("t", to); }
        if (customerId != null) { where.append(" AND s.customer_id = :c"); p.put("c", customerId); }
        if (status != null && !status.isBlank()) { where.append(" AND s.status = :status"); p.put("status", status.toUpperCase(java.util.Locale.ROOT)); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(s.number) LIKE LOWER(:q) OR LOWER(COALESCE(c.name, '')) LIKE LOWER(:q) OR LOWER(COALESCE(c.code, '')) LIKE LOWER(:q))");
            p.put("q", Search.contains(q));
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        var spec = jdbc.sql("SELECT s.id FROM sales s LEFT JOIN customers c ON c.id = s.customer_id" + where
                + " ORDER BY s.created_at DESC LIMIT " + safeSize + " OFFSET " + ((long) safePage * safeSize));
        var tot = jdbc.sql("SELECT COUNT(*) AS n, COALESCE(SUM(s.total - s.returned_amount), 0) AS sum FROM sales s LEFT JOIN customers c ON c.id = s.customer_id" + where);
        for (var e : p.entrySet()) { spec = spec.param(e.getKey(), e.getValue()); tot = tot.param(e.getKey(), e.getValue()); }
        List<Sale> items = spec.query(UUID.class).list().stream().map(id -> load(m.storeId(), id)).toList();
        Object[] t = tot.query((rs, n) -> new Object[]{rs.getLong("n"), Money.of(rs.getBigDecimal("sum"))}).single();
        return new SalePage(items, (Long) t[0], (BigDecimal) t[1], safePage, safeSize);
    }

    public Sale get(UUID id) {
        CurrentMember m = requireSales();
        return load(m.storeId(), id);
    }

    private Sale lock(UUID storeId, UUID id) {
        jdbc.sql("SELECT id FROM sales WHERE id = :id AND store_id = :s FOR UPDATE").param("id", id).param("s", storeId)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Satış tapılmadı"));
        return load(storeId, id);
    }

    Sale load(UUID storeId, UUID id) {
        List<Item> items = jdbc.sql("""
                SELECT id, product_id, name, quantity, unit_price, discount, line_total, unit_cost, returned_qty
                FROM sale_items WHERE sale_id = :id AND store_id = :s ORDER BY name""").param("id", id).param("s", storeId)
                .query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("name"),
                        rs.getBigDecimal("quantity"), Money.of(rs.getBigDecimal("unit_price")), Money.of(rs.getBigDecimal("discount")),
                        Money.of(rs.getBigDecimal("line_total")), Money.of(rs.getBigDecimal("unit_cost")), rs.getBigDecimal("returned_qty")))
                .list();
        List<ReturnView> returns = jdbc.sql("""
                SELECT r.id, r.amount, r.debt_reduced, r.refunded, r.returned_on, r.reason, u.full_name, r.created_at
                FROM sale_returns r JOIN users u ON u.id = r.created_by WHERE r.sale_id = :id ORDER BY r.created_at""")
                .param("id", id).query((rs, n) -> new ReturnView(rs.getObject("id", UUID.class), Money.of(rs.getBigDecimal("amount")),
                        Money.of(rs.getBigDecimal("debt_reduced")), Money.of(rs.getBigDecimal("refunded")),
                        rs.getObject("returned_on", LocalDate.class), rs.getString("reason"), rs.getString("full_name"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
        return jdbc.sql("""
                SELECT s.*, c.name AS customer_name, c.code AS customer_code, u.full_name AS created_by_name
                FROM sales s LEFT JOIN customers c ON c.id = s.customer_id JOIN users u ON u.id = s.created_by
                WHERE s.id = :id AND s.store_id = :s""").param("id", id).param("s", storeId)
                .query((rs, n) -> new Sale(rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("customer_id", UUID.class),
                        rs.getString("customer_name"), rs.getString("customer_code"), rs.getString("status"),
                        Money.of(rs.getBigDecimal("subtotal")), Money.of(rs.getBigDecimal("discount")), Money.of(rs.getBigDecimal("total")),
                        Money.of(rs.getBigDecimal("paid_cash")), Money.of(rs.getBigDecimal("paid_card")),
                        Money.of(rs.getBigDecimal("credit_amount")), Money.of(rs.getBigDecimal("returned_amount")),
                        Money.of(rs.getBigDecimal("cost_total")), rs.getObject("debt_id", UUID.class),
                        rs.getObject("sold_on", LocalDate.class), rs.getString("note"), rs.getString("created_by_name"),
                        rs.getObject("created_at", OffsetDateTime.class), items, returns))
                .optional().orElseThrow(() -> ApiException.notFound("Satış tapılmadı"));
    }
}
