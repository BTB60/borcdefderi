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
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal discount,
            /** Only for "Sərbəst məbləğ": what was sold (shown on the receipt). */
            @jakarta.validation.constraints.Size(max = 200) String name,
            /** IMEI / seriya nömrələri — one per unit, for goods that ask for them. */
            @jakarta.validation.constraints.Size(max = 200) List<@jakarta.validation.constraints.Size(max = 64) String> serials) {

        public ItemRequest(UUID productId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount) {
            this(productId, quantity, unitPrice, discount, null, null);
        }

        public ItemRequest(UUID productId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, String name) {
            this(productId, quantity, unitPrice, discount, name, null);
        }
    }

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
            UUID clientRequestId,
            /** Customer bonus spent on this sale (works as a discount). */
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal bonusUsed,
            /** RETAIL (Top 2, the default) or WHOLESALE (Top 1, topdan): which price the goods are sold at. */
            @jakarta.validation.constraints.Pattern(regexp = "RETAIL|WHOLESALE") String priceLevel,
            /** The owner lets a credit sale through although it goes over the customer's borc limiti. */
            Boolean allowOverLimit) {}

    public record ReturnItem(@jakarta.validation.constraints.NotNull UUID saleItemId,
                             @jakarta.validation.constraints.NotNull
                             @jakarta.validation.constraints.DecimalMin("0.001")
                             @jakarta.validation.constraints.Digits(integer = 11, fraction = 3) BigDecimal quantity) {}

    public record ReturnRequest(
            @jakarta.validation.constraints.NotEmpty List<@jakarta.validation.Valid ReturnItem> items,
            UUID refundAccountId,
            @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.Size(max = 200) String approvalPin) {}

    public record CancelRequest(UUID refundAccountId, @jakarta.validation.constraints.Size(max = 500) String reason,
                                @jakarta.validation.constraints.Size(max = 200) String approvalPin) {}

    public record Item(UUID id, UUID productId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
                       BigDecimal lineTotal, BigDecimal unitCost, BigDecimal returnedQty, String unit,
                       List<String> serials, Integer warrantyMonths) {}

    public record ReturnView(UUID id, BigDecimal amount, BigDecimal debtReduced, BigDecimal refunded, LocalDate returnedOn,
                             String reason, String createdByName, OffsetDateTime createdAt, String approvedByName) {}

    public record Sale(UUID id, String number, UUID customerId, String customerName, String customerCode, String status,
                       BigDecimal subtotal, BigDecimal discount, BigDecimal total, BigDecimal paidCash, BigDecimal paidCard,
                       BigDecimal creditAmount, BigDecimal returnedAmount, BigDecimal costTotal, UUID debtId, LocalDate soldOn,
                       String note, String createdByName, OffsetDateTime createdAt, List<Item> items, List<ReturnView> returns,
                       UUID createdBy, BigDecimal bonusUsed, BigDecimal bonusEarned, String priceLevel) {}

    public record SalePage(List<Sale> items, long total, BigDecimal sum, int page, int size) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final InventoryService inventory;
    private final CashService cash;
    private final PartyAccountService accounts;
    private final DebtService debts;
    private final AuditService audit;
    private final BonusService bonus;
    private final ReturnApprovalService approvals;

    public SalesService(JdbcClient jdbc, TenantService tenant, InventoryService inventory, CashService cash,
                        PartyAccountService accounts, DebtService debts, AuditService audit, BonusService bonus,
                        ReturnApprovalService approvals) {
        this.bonus = bonus;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.inventory = inventory;
        this.cash = cash;
        this.accounts = accounts;
        this.debts = debts;
        this.audit = audit;
        this.approvals = approvals;
    }

    /** One IMEI / serial per unit, none twice, none sold before and not returned. */
    private void checkSerials(UUID storeId, Product p, BigDecimal qty, List<String> serials) {
        if (qty.stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest("SERIAL_QTY", "“" + p.name() + "” ədədlə satılır (IMEI hər ədəd üçün)");
        }
        List<String> clean = serials == null ? List.of() : serials.stream().map(s -> s == null ? "" : s.trim()).toList();
        if (clean.size() != qty.intValue() || clean.stream().anyMatch(String::isEmpty)) {
            throw ApiException.badRequest("SERIALS_REQUIRED", "“" + p.name() + "” üçün hər ədədin IMEI / seriya nömrəsini oxudun ("
                    + qty.intValue() + " ədəd, " + clean.stream().filter(s -> !s.isEmpty()).count() + " nömrə)");
        }
        if (new java.util.HashSet<>(clean).size() != clean.size()) {
            throw ApiException.badRequest("SERIAL_DUPLICATE", "Eyni IMEI / seriya nömrəsi iki dəfə yazılıb");
        }
        for (String s : clean) {
            boolean sold = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM sale_item_serials x JOIN sale_items si ON si.id = x.sale_item_id JOIN sales sa ON sa.id = x.sale_id
                                   WHERE x.store_id = :st AND x.serial = :s AND sa.status = 'COMPLETED' AND si.returned_qty < si.quantity)
                    """).param("st", storeId).param("s", s).query(Boolean.class).single();
            if (sold) throw ApiException.conflict("SERIAL_SOLD", "IMEI / seriya " + s + " artıq satılıb");
        }
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

        boolean wholesale = "WHOLESALE".equals(req.priceLevel());
        record Line(ItemRequest req, Product p, String name, BigDecimal qty, BigDecimal price, BigDecimal disc, BigDecimal total) {}
        List<Line> lines = new ArrayList<>();
        BigDecimal subtotal = Money.zero();
        for (ItemRequest it : req.items()) {
            Product p = products.get(it.productId());
            boolean free = inventory.isSystem(m.storeId(), p.id());
            if (free && (it.unitPrice() == null || it.unitPrice().signum() <= 0)) {
                throw ApiException.badRequest("FREE_AMOUNT_REQUIRED", "Sərbəst məbləğ üçün məbləği yazın");
            }
            if (!p.active() && !free) throw ApiException.badRequest("PRODUCT_INACTIVE", "“" + p.name() + "” satışda deyil (deaktiv)");
            BigDecimal qty = it.quantity().setScale(3, RoundingMode.HALF_UP);
            BigDecimal list = wholesale && p.wholesalePrice() != null ? p.wholesalePrice() : p.salePrice();
            BigDecimal price = Money.of(it.unitPrice() != null ? it.unitPrice() : list);
            BigDecimal gross = Money.of(qty.multiply(price));
            BigDecimal disc = Money.of(it.discount() != null ? it.discount() : BigDecimal.ZERO);
            if (disc.compareTo(gross) > 0) throw ApiException.badRequest("INVALID_DISCOUNT", "Endirim məbləğdən çox ola bilməz");
            BigDecimal total = Money.of(gross.subtract(disc));
            if (p.trackSerial()) checkSerials(m.storeId(), p, qty, it.serials());
            String lineName = free && it.name() != null && !it.name().isBlank() ? it.name().trim() : p.name();
            lines.add(new Line(it, p, lineName, qty, price, disc, total));
            subtotal = subtotal.add(total);
        }
        subtotal = Money.of(subtotal);
        BigDecimal discount = Money.of(req.discount() != null ? req.discount() : BigDecimal.ZERO);
        if (discount.compareTo(subtotal) > 0) throw ApiException.badRequest("INVALID_DISCOUNT", "Endirim məbləğdən çox ola bilməz");
        BigDecimal bonusUsed = Money.of(req.bonusUsed() != null ? req.bonusUsed() : BigDecimal.ZERO);
        if (bonusUsed.signum() > 0) {
            if (req.customerId() == null) throw ApiException.badRequest("CUSTOMER_REQUIRED", "Bonusla ödəniş üçün müştəri seçin");
            if (bonus.cardBlocked(req.customerId())) throw ApiException.conflict("CARD_BLOCKED", "Müştərinin bonus kartı bloklanıb");
            BigDecimal available = bonus.lockBalance(m.storeId(), req.customerId());
            if (bonusUsed.compareTo(available) > 0) {
                throw ApiException.conflict("BONUS_EXCEEDS", "Müştərinin bonusu kifayət etmir (balans: " + available + " AZN)");
            }
            if (bonusUsed.compareTo(subtotal.subtract(discount)) > 0) {
                throw ApiException.badRequest("BONUS_EXCEEDS", "Bonus satış məbləğindən çox ola bilməz");
            }
            discount = Money.of(discount.add(bonusUsed)); // bonus is spent as a discount
        }
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

        if (credit.signum() > 0) checkCreditLimit(m, req.customerId(), credit, Boolean.TRUE.equals(req.allowOverLimit()));

        BigDecimal bonusEarned = req.customerId() == null ? Money.zero() : bonus.earnedFor(m.storeId(), req.customerId(), paid);
        UUID saleId = UUID.randomUUID();
        String number = "S-" + String.format("%06d", jdbc.sql("SELECT nextval('sale_number_seq')").query(Long.class).single());
        BigDecimal costTotal = Money.zero();
        for (Line l : lines) costTotal = costTotal.add(Money.of(l.qty().multiply(l.p().purchasePrice())));
        jdbc.sql("""
                INSERT INTO sales (id, store_id, number, customer_id, status, subtotal, discount, total, paid_cash, paid_card,
                                   credit_amount, cost_total, sold_on, note, created_by, client_request_id, bonus_used, bonus_earned,
                                   price_level)
                VALUES (:id, :s, :n, :c, 'COMPLETED', :sub, :d, :t, :pc, :pk, :cr, :cost, :day, :note, :by, :req, :bu, :be, :pl)
                """).param("id", saleId).param("s", m.storeId()).param("n", number).param("c", req.customerId())
                .param("sub", subtotal).param("d", discount).param("t", total).param("pc", paidCash).param("pk", paidCard)
                .param("cr", credit).param("cost", Money.of(costTotal)).param("day", today)
                .param("note", req.note() == null || req.note().isBlank() ? null : req.note().trim()).param("by", m.userId())
                .param("req", req.clientRequestId()).param("bu", bonusUsed).param("be", bonusEarned)
                .param("pl", wholesale ? "WHOLESALE" : "RETAIL").update();
        for (Line l : lines) {
            UUID itemId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO sale_items (id, sale_id, store_id, product_id, name, quantity, unit_price, discount, line_total, unit_cost)
                    VALUES (:id, :sale, :s, :p, :n, :q, :up, :d, :lt, :uc)
                    """).param("id", itemId).param("sale", saleId).param("s", m.storeId()).param("p", l.p().id())
                    .param("n", l.name()).param("q", l.qty()).param("up", l.price()).param("d", l.disc())
                    .param("lt", l.total()).param("uc", l.p().purchasePrice()).update();
            inventory.move(m, l.p().id(), "SALE", l.qty().negate(), l.p().purchasePrice(), saleId, number);
            if (l.p().trackSerial()) {
                for (String serial : l.req().serials()) {
                    jdbc.sql("""
                            INSERT INTO sale_item_serials (id, store_id, sale_id, sale_item_id, product_id, serial)
                            VALUES (:id, :s, :sale, :item, :p, :ser)
                            """).param("id", UUID.randomUUID()).param("s", m.storeId()).param("sale", saleId).param("item", itemId)
                            .param("p", l.p().id()).param("ser", serial.trim()).update();
                }
            }
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
        if (req.customerId() != null) {
            bonus.add(m, req.customerId(), saleId, "USE", bonusUsed.negate(), number);
            bonus.add(m, req.customerId(), saleId, "EARN", bonusEarned, number);
        }
        audit.log(m.storeId(), m.userId(), "SALE_CREATED", "SALE", saleId.toString(),
                number + (wholesale ? " (topdan)" : "") + ": " + total + " AZN (nağd " + paidCash + ", kart " + paidCard + ", borc " + credit + ")");
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
        UUID approvedBy = approvals.approve(m, amount, req.approvalPin());
        settleReturn(m, sale, amount, cost, req.refundAccountId(), req.reason(), approvedBy);
        return load(m.storeId(), saleId);
    }

    @Transactional
    public Sale cancel(UUID saleId, CancelRequest req) {
        CurrentMember m = requireSales();
        Sale sale = lock(m.storeId(), saleId);
        if (!m.isOwner() && !m.userId().equals(sale.createdBy())) {
            throw ApiException.forbidden("NOT_YOUR_OPERATION", "Yalnız özünüzün etdiyi satışı ləğv edə bilərsiniz");
        }
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
        if (amount.signum() > 0) {
            UUID approvedBy = approvals.approve(m, amount, req == null ? null : req.approvalPin());
            settleReturn(m, sale, amount, cost, req == null ? null : req.refundAccountId(),
                    req == null || req.reason() == null ? "Satış ləğv edildi" : req.reason(), approvedBy);
        } else if (sale.total().signum() == 0) {
            bonus.reverseSale(m, sale.customerId(), sale.id(), sale.number(), sale.bonusEarned(), sale.bonusUsed(), BigDecimal.ONE);
        }
        jdbc.sql("UPDATE sales SET status = 'CANCELLED', cancelled_at = now() WHERE id = :id").param("id", saleId).update();
        audit.log(m.storeId(), m.userId(), "SALE_CANCELLED", "SALE", saleId.toString(), sale.number());
        return load(m.storeId(), saleId);
    }

    /** Lowers the sale's still-unpaid debt first, refunds the rest in money; records the return. */
    private void settleReturn(CurrentMember m, Sale sale, BigDecimal amount, BigDecimal cost, UUID refundAccountId, String reason,
                              UUID approvedBy) {
        // the same share of the sale's bonus goes back: earned bonus is taken back, spent bonus is returned
        BigDecimal share = sale.total().signum() == 0 ? BigDecimal.ONE
                : amount.divide(sale.total(), 6, java.math.RoundingMode.HALF_UP).min(BigDecimal.ONE);
        bonus.reverseSale(m, sale.customerId(), sale.id(), sale.number(), sale.bonusEarned(), sale.bonusUsed(), share);
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
                                          returned_on, reason, created_by, approved_by)
                VALUES (:id, :sale, :s, :a, :c, :dr, :rf, :acc, :day, :reason, :by, :appr)
                """).param("id", UUID.randomUUID()).param("sale", sale.id()).param("s", m.storeId()).param("a", amount)
                .param("c", Money.of(cost)).param("dr", debtReduced).param("rf", refund).param("acc", refundAccount)
                .param("day", tenant.today()).param("reason", reason == null || reason.isBlank() ? null : reason.trim())
                .param("by", m.userId()).param("appr", approvedBy).update();
        jdbc.sql("UPDATE sales SET returned_amount = returned_amount + :a WHERE id = :id").param("a", amount).param("id", sale.id()).update();
        audit.log(m.storeId(), m.userId(), "SALE_RETURNED", "SALE", sale.id().toString(),
                sale.number() + ": " + amount + " AZN (borcdan " + debtReduced + ", qaytarılan pul " + refund + ")"
                        + (approvedBy != null && !approvedBy.equals(m.userId()) ? ", menecer təsdiqi ilə" : ""));
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
            where.append(" AND (search_norm(s.number) LIKE search_norm(:q) OR search_norm(COALESCE(c.name, '')) LIKE search_norm(:q) OR search_norm(COALESCE(c.code, '')) LIKE search_norm(:q))");
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
                SELECT si.id, si.product_id, si.name, si.quantity, si.unit_price, si.discount, si.line_total, si.unit_cost,
                       si.returned_qty, COALESCE(p.unit, 'ədəd') AS unit, p.warranty_months,
                       (SELECT string_agg(x.serial, ',' ORDER BY x.created_at, x.serial) FROM sale_item_serials x WHERE x.sale_item_id = si.id) AS serials
                FROM sale_items si LEFT JOIN products p ON p.id = si.product_id
                WHERE si.sale_id = :id AND si.store_id = :s ORDER BY si.name""").param("id", id).param("s", storeId)
                .query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("name"),
                        rs.getBigDecimal("quantity"), Money.of(rs.getBigDecimal("unit_price")), Money.of(rs.getBigDecimal("discount")),
                        Money.of(rs.getBigDecimal("line_total")), Money.of(rs.getBigDecimal("unit_cost")), rs.getBigDecimal("returned_qty"),
                        rs.getString("unit"),
                        rs.getString("serials") == null ? List.of() : List.of(rs.getString("serials").split(",")),
                        (Integer) rs.getObject("warranty_months", Integer.class)))
                .list();
        List<ReturnView> returns = jdbc.sql("""
                SELECT r.id, r.amount, r.debt_reduced, r.refunded, r.returned_on, r.reason, u.full_name, r.created_at,
                       a.full_name AS approved_by_name
                FROM sale_returns r JOIN users u ON u.id = r.created_by LEFT JOIN users a ON a.id = r.approved_by
                WHERE r.sale_id = :id ORDER BY r.created_at""")
                .param("id", id).query((rs, n) -> new ReturnView(rs.getObject("id", UUID.class), Money.of(rs.getBigDecimal("amount")),
                        Money.of(rs.getBigDecimal("debt_reduced")), Money.of(rs.getBigDecimal("refunded")),
                        rs.getObject("returned_on", LocalDate.class), rs.getString("reason"), rs.getString("full_name"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("approved_by_name"))).list();
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
                        rs.getObject("created_at", OffsetDateTime.class), items, returns, rs.getObject("created_by", UUID.class),
                        Money.of(rs.getBigDecimal("bonus_used")), Money.of(rs.getBigDecimal("bonus_earned")),
                        rs.getString("price_level")))
                .optional().orElseThrow(() -> ApiException.notFound("Satış tapılmadı"));
    }

    /**
     * Müştəri borc limiti: a credit sale may not push the customer's open debt over their limit. The owner may let
     * it through ({@code allow}); anyone else gets CREDIT_LIMIT with the numbers.
     */
    private void checkCreditLimit(CurrentMember m, UUID customerId, BigDecimal credit, boolean allow) {
        BigDecimal limit = jdbc.sql("SELECT credit_limit FROM customers WHERE id = :c AND store_id = :s")
                .param("c", customerId).param("s", m.storeId()).query(BigDecimal.class).optional().orElse(null);
        if (limit == null) return;
        BigDecimal owes = Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(remaining_amount), 0) FROM debts
                WHERE store_id = :s AND customer_id = :c AND status <> 'PAID'
                """).param("s", m.storeId()).param("c", customerId).query(BigDecimal.class).single());
        BigDecimal after = Money.of(owes.add(credit));
        if (after.compareTo(limit) <= 0) return;
        if (allow && m.isOwner()) return;
        java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("limit", Money.of(limit).toPlainString());
        details.put("owes", owes.toPlainString());
        details.put("after", after.toPlainString());
        throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "CREDIT_LIMIT",
                "Müştərinin borc limiti " + Money.of(limit) + " AZN-dir. İndiki borcu " + owes + " AZN, bu satışla "
                        + after + " AZN olur" + (m.isOwner() ? "" : ". Mağaza sahibinin icazəsi lazımdır"), details);
    }
}
