package az.borcdefteri.stores;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.inventory.InventoryService;
import az.borcdefteri.reports.DaySummaryService;
import az.borcdefteri.subscriptions.PromoService;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Filiallar: one owner, several stores. Each branch is a store of its own (own goods, stock, till, customers and
 * subscription); the owner switches between them with one account, sends goods from one branch to another and
 * sees all branches' day in one table.
 */
@Service
@RestController
public class BranchService {

    public record Branch(UUID id, String name, String address, String phone, boolean current, boolean main,
                         boolean subscriptionActive, LocalDate subscriptionEnds) {}

    public record NewBranch(String name, String address, String phone) {}

    public record TransferItem(UUID productId, BigDecimal quantity) {}

    public record Transfer(UUID toStoreId, List<TransferItem> items, String note) {}

    public record TransferResult(String toStore, int lines, List<String> created) {}

    public record BranchDay(UUID id, String name, boolean current, long salesCount, BigDecimal salesTotal, BigDecimal paidCash,
                            BigDecimal paidCard, BigDecimal credit, BigDecimal grossProfit, BigDecimal expenses, BigDecimal stockValue) {}

    public record Overview(LocalDate date, List<BranchDay> branches, BranchDay total) {}

    private static final int MAX_BRANCHES = 20;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final PromoService promos;
    private final InventoryService inventory;
    private final DaySummaryService days;

    public BranchService(JdbcClient jdbc, TenantService tenant, AuditService audit, PromoService promos,
                         InventoryService inventory, DaySummaryService days) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.promos = promos;
        this.inventory = inventory;
        this.days = days;
    }

    /** The owner (works even when the current branch has no active subscription, so one can switch away). */
    private CurrentMember owner() {
        CurrentMember m = tenant.requireMember();
        if (!m.isOwner()) throw ApiException.forbidden("OWNER_ONLY", "Yalnız mağaza sahibi filialları idarə edə bilər");
        return m;
    }

    @GetMapping("/api/branches")
    public List<Branch> list() {
        CurrentMember m = owner();
        LocalDate today = tenant.today();
        return jdbc.sql("""
                SELECT s.id, s.name, s.address, s.phone, sm.is_current, s.branch_of IS NULL AS main,
                       (SELECT MAX(sub.end_date) FROM subscriptions sub
                        WHERE sub.store_id = s.id AND sub.status = 'ACTIVE' AND sub.end_date >= :today) AS ends
                FROM store_members sm JOIN stores s ON s.id = sm.store_id
                WHERE sm.user_id = :u AND sm.role = 'STORE_OWNER' AND sm.active AND s.status = 'ACTIVE'
                ORDER BY s.branch_of IS NOT NULL, s.created_at
                """).param("u", m.userId()).param("today", today)
                .query((rs, n) -> {
                    LocalDate ends = rs.getObject("ends", LocalDate.class);
                    return new Branch(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                            rs.getString("phone"), rs.getBoolean("is_current"), rs.getBoolean("main"), ends != null, ends);
                }).list();
    }

    /** Opens a new branch (a new store of the same owner) — the owner stays in the current one. */
    @PostMapping("/api/branches")
    @Transactional
    public Branch create(@RequestBody NewBranch req) {
        CurrentMember m = tenant.requireOwner();
        String name = req.name() == null ? "" : req.name().trim();
        if (name.length() < 2 || name.length() > 150) throw ApiException.badRequest("INVALID_NAME", "Filialın adını yazın");
        long count = jdbc.sql("SELECT COUNT(*) FROM store_members WHERE user_id = :u AND role = 'STORE_OWNER'")
                .param("u", m.userId()).query(Long.class).single();
        if (count >= MAX_BRANCHES) throw ApiException.conflict("TOO_MANY_BRANCHES", "Ən çox " + MAX_BRANCHES + " filial ola bilər");
        UUID root = jdbc.sql("SELECT COALESCE(branch_of, id) FROM stores WHERE id = :s").param("s", m.storeId()).query(UUID.class).single();
        UUID storeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO stores (id, name, phone, address, status, branch_of) VALUES (:id, :n, :p, :a, 'ACTIVE', :root)
                """).param("id", storeId).param("n", name).param("p", blank(req.phone())).param("a", blank(req.address()))
                .param("root", root).update();
        jdbc.sql("""
                INSERT INTO store_members (id, store_id, user_id, role, active, is_current)
                VALUES (:id, :s, :u, 'STORE_OWNER', TRUE, FALSE)
                """).param("id", UUID.randomUUID()).param("s", storeId).param("u", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "BRANCH_CREATED", "STORE", storeId.toString(), "Yeni filial: " + name);
        audit.log(storeId, m.userId(), "STORE_REGISTERED", "STORE", storeId.toString(), "Filial açıldı (" + m.storeName() + " mağazasından)");
        promos.startTrial(storeId, m.userId());
        return list().stream().filter(b -> b.id().equals(storeId)).findFirst().orElseThrow();
    }

    /** Makes another branch the current one: every screen then shows that branch. */
    @PostMapping("/api/branches/{id}/switch")
    @Transactional
    public List<Branch> switchTo(@PathVariable UUID id) {
        CurrentMember m = owner();
        boolean mine = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM store_members sm JOIN stores s ON s.id = sm.store_id
                               WHERE sm.user_id = :u AND sm.store_id = :s AND sm.role = 'STORE_OWNER' AND sm.active AND s.status = 'ACTIVE')
                """).param("u", m.userId()).param("s", id).query(Boolean.class).single();
        if (!mine) throw ApiException.notFound("Filial tapılmadı");
        jdbc.sql("UPDATE store_members SET is_current = FALSE WHERE user_id = :u AND is_current").param("u", m.userId()).update();
        jdbc.sql("UPDATE store_members SET is_current = TRUE WHERE user_id = :u AND store_id = :s").param("u", m.userId()).param("s", id).update();
        audit.log(id, m.userId(), "BRANCH_SWITCHED", "STORE", id.toString(), "Filiala keçid");
        return list();
    }

    /**
     * Sends goods from the current branch to another one. A product the other branch does not have yet (same code
     * or barcode) is created there with the same name, code, barcode, unit and prices.
     */
    @PostMapping("/api/branches/transfer")
    @Transactional
    public TransferResult transfer(@RequestBody Transfer req) {
        CurrentMember m = tenant.requireOwner();
        if (req.toStoreId() == null || req.toStoreId().equals(m.storeId())) {
            throw ApiException.badRequest("INVALID_BRANCH", "Göndəriləcək filialı seçin");
        }
        if (req.items() == null || req.items().isEmpty() || req.items().size() > 200) {
            throw ApiException.badRequest("EMPTY_TRANSFER", "Göndəriləcək malları seçin");
        }
        var target = jdbc.sql("""
                SELECT s.name, sm.id AS member_id FROM store_members sm JOIN stores s ON s.id = sm.store_id
                WHERE sm.user_id = :u AND sm.store_id = :s AND sm.role = 'STORE_OWNER' AND sm.active AND s.status = 'ACTIVE'
                """).param("u", m.userId()).param("s", req.toStoreId())
                .query((rs, n) -> Map.entry(rs.getString("name"), rs.getObject("member_id", UUID.class))).optional()
                .orElseThrow(() -> ApiException.notFound("Filial tapılmadı"));
        CurrentMember there = new CurrentMember(m.userId(), target.getValue(), req.toStoreId(), target.getKey(), "STORE_OWNER",
                m.platformRole(), m.permissions());
        String note = "Filiallar arası: " + m.storeName() + " → " + target.getKey() + (blank(req.note()) == null ? "" : " · " + req.note().trim());
        List<String> created = new ArrayList<>();
        // lock in a stable order (two transfers at once cannot deadlock)
        List<TransferItem> items = new ArrayList<>(req.items());
        items.sort(Comparator.comparing(TransferItem::productId));
        for (TransferItem it : items) {
            if (it.productId() == null || it.quantity() == null || it.quantity().signum() <= 0) {
                throw ApiException.badRequest("INVALID_QUANTITY", "Miqdar 0-dan böyük olmalıdır");
            }
            BigDecimal qty = it.quantity().setScale(3, RoundingMode.HALF_UP);
            InventoryService.Product p = inventory.lockProduct(m.storeId(), it.productId());
            UUID dest = jdbc.sql("""
                    SELECT id FROM products WHERE store_id = :s AND system_kind IS NULL
                      AND (LOWER(code) = LOWER(:code) OR (:bc <> '' AND barcode = :bc)) ORDER BY active DESC LIMIT 1
                    """).param("s", req.toStoreId()).param("code", p.code()).param("bc", p.barcode() == null ? "" : p.barcode())
                    .query(UUID.class).optional().orElse(null);
            if (dest == null) {
                dest = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO products (id, store_id, code, barcode, name, unit, purchase_price, sale_price, min_stock, active,
                                              created_by, old_price, wholesale_price, track_serial, warranty_months)
                        VALUES (:id, :s, :code, :bc, :n, :u, :pp, :sp, :min, TRUE, :by, :op, :wp, :ts, :wm)
                        """).param("id", dest).param("s", req.toStoreId()).param("code", p.code()).param("bc", p.barcode())
                        .param("n", p.name()).param("u", p.unit()).param("pp", p.purchasePrice()).param("sp", p.salePrice())
                        .param("min", p.minStock()).param("by", m.userId()).param("op", p.oldPrice()).param("wp", p.wholesalePrice())
                        .param("ts", p.trackSerial()).param("wm", p.warrantyMonths()).update();
                created.add(p.name());
            } else {
                inventory.lockProduct(req.toStoreId(), dest);
            }
            inventory.move(m, p.id(), "TRANSFER_OUT", qty.negate(), p.purchasePrice(), null, note);
            inventory.move(there, dest, "TRANSFER_IN", qty, p.purchasePrice(), null, note);
        }
        audit.log(m.storeId(), m.userId(), "BRANCH_TRANSFER", "STORE", req.toStoreId().toString(), items.size() + " mal → " + target.getKey());
        return new TransferResult(target.getKey(), items.size(), created);
    }

    /** All branches' day side by side, with the sum. */
    @GetMapping("/api/branches/overview")
    public Overview overview(@RequestParam(required = false) LocalDate date) {
        CurrentMember m = owner();
        LocalDate day = date != null ? date : tenant.today();
        List<BranchDay> rows = new ArrayList<>();
        for (Branch b : list()) {
            var s = days.summaryFor(b.id(), b.name(), day);
            BigDecimal stock = Money.of(jdbc.sql("""
                    SELECT COALESCE(SUM(ROUND(stock_qty * purchase_price, 2)), 0) FROM products WHERE store_id = :s AND active
                    """).param("s", b.id()).query(BigDecimal.class).single());
            rows.add(new BranchDay(b.id(), b.name(), b.current(), s.salesCount(), s.salesTotal(), s.paidCash(), s.paidCard(),
                    s.credit(), s.grossProfit(), s.expensesTotal(), stock));
        }
        BranchDay total = new BranchDay(null, "Cəmi", false, rows.stream().mapToLong(BranchDay::salesCount).sum(),
                sum(rows, BranchDay::salesTotal), sum(rows, BranchDay::paidCash), sum(rows, BranchDay::paidCard),
                sum(rows, BranchDay::credit), sum(rows, BranchDay::grossProfit), sum(rows, BranchDay::expenses), sum(rows, BranchDay::stockValue));
        return new Overview(day, rows, total);
    }

    private static BigDecimal sum(List<BranchDay> rows, java.util.function.Function<BranchDay, BigDecimal> f) {
        return Money.of(rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
