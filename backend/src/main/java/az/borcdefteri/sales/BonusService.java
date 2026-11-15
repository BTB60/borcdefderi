package az.borcdefteri.sales;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Customer bonus: the store gives {@code stores.bonus_percent} of what a customer pays at the till as bonus, which the
 * customer spends later as a discount. Every move is an entry; the balance is their sum and never goes below zero.
 */
@Service
public class BonusService {

    public record Entry(UUID id, String kind, BigDecimal amount, UUID saleId, String saleNumber, String note,
                       String createdByName, OffsetDateTime createdAt) {}

    public record Account(UUID customerId, BigDecimal balance, BigDecimal storePercent, BigDecimal discountPercent,
                          List<Entry> entries) {}

    public record Adjust(@jakarta.validation.constraints.NotNull
                         @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal amount,
                         @jakarta.validation.constraints.Size(max = 255) String note) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public BonusService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    public BigDecimal storePercent(UUID storeId) {
        return jdbc.sql("SELECT bonus_percent FROM stores WHERE id = :s").param("s", storeId).query(BigDecimal.class).single();
    }

    /** Locks the customer (so two sales cannot spend the same bonus) and returns the balance. */
    BigDecimal lockBalance(UUID storeId, UUID customerId) {
        jdbc.sql("SELECT id FROM customers WHERE id = :c AND store_id = :s FOR UPDATE").param("c", customerId).param("s", storeId)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Müştəri tapılmadı"));
        return balance(customerId);
    }

    private BigDecimal balance(UUID customerId) {
        return Money.of(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM customer_bonus_entries WHERE customer_id = :c")
                .param("c", customerId).query(BigDecimal.class).single());
    }

    void add(CurrentMember m, UUID customerId, UUID saleId, String kind, BigDecimal amount, String note) {
        BigDecimal a = Money.of(amount);
        if (a.signum() == 0) return;
        jdbc.sql("""
                INSERT INTO customer_bonus_entries (id, store_id, customer_id, sale_id, kind, amount, note, created_by)
                VALUES (:id, :s, :c, :sale, :k, :a, :n, :by)
                """).param("id", UUID.randomUUID()).param("s", m.storeId()).param("c", customerId).param("sale", saleId)
                .param("k", kind).param("a", a).param("n", note).param("by", m.userId()).update();
    }

    /** Bonus for the paid part of a sale (0 when the store gives no bonus). */
    BigDecimal earnedFor(UUID storeId, BigDecimal paid) {
        BigDecimal pct = storePercent(storeId);
        if (pct.signum() == 0 || paid.signum() <= 0) return Money.zero();
        return paid.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
    }

    /**
     * Takes back {@code share} (0..1) of a sale's earned bonus and gives back the same share of the bonus it used,
     * when goods come back. The earned part taken back never pushes the balance below zero.
     */
    void reverseSale(CurrentMember m, UUID customerId, UUID saleId, String number, BigDecimal earned, BigDecimal used,
                     BigDecimal share) {
        if (customerId == null) return;
        BigDecimal backUsed = Money.of(used.multiply(share));
        BigDecimal takeEarned = Money.of(earned.multiply(share));
        if (backUsed.signum() == 0 && takeEarned.signum() == 0) return;
        BigDecimal balance = lockBalance(m.storeId(), customerId);
        takeEarned = takeEarned.min(balance.add(backUsed)).max(BigDecimal.ZERO);
        add(m, customerId, saleId, "REVERSE", backUsed.subtract(takeEarned), number + " qaytarma");
    }

    public Account account(UUID customerId) {
        CurrentMember m = requireAccess();
        BigDecimal discount = jdbc.sql("SELECT discount_percent FROM customers WHERE id = :c AND store_id = :s")
                .param("c", customerId).param("s", m.storeId()).query(BigDecimal.class).optional()
                .orElseThrow(() -> ApiException.notFound("Müştəri tapılmadı"));
        List<Entry> entries = jdbc.sql("""
                SELECT b.id, b.kind, b.amount, b.sale_id, s.number, b.note, u.full_name, b.created_at
                FROM customer_bonus_entries b LEFT JOIN sales s ON s.id = b.sale_id JOIN users u ON u.id = b.created_by
                WHERE b.customer_id = :c ORDER BY b.created_at DESC LIMIT 200
                """).param("c", customerId)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("kind"), Money.of(rs.getBigDecimal("amount")),
                        rs.getObject("sale_id", UUID.class), rs.getString("number"), rs.getString("note"), rs.getString("full_name"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
        return new Account(customerId, balance(customerId), storePercent(m.storeId()), discount, entries);
    }

    /** Owner adds or removes bonus by hand (a gift, a correction). */
    @Transactional
    public Account adjust(UUID customerId, Adjust req) {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.isOwner()) throw ApiException.forbidden("OWNER_ONLY", "Bonusu yalnız mağaza sahibi dəyişə bilər");
        BigDecimal amount = Money.of(req.amount());
        if (amount.signum() == 0) throw ApiException.badRequest("INVALID_AMOUNT", "Məbləğ 0 ola bilməz");
        BigDecimal balance = lockBalance(m.storeId(), customerId);
        if (balance.add(amount).signum() < 0) {
            throw ApiException.conflict("BONUS_EXCEEDS", "Bonus balansı mənfi ola bilməz (balans: " + balance + " AZN)");
        }
        add(m, customerId, null, "ADJUST", amount, req.note() == null || req.note().isBlank() ? null : req.note().trim());
        audit.log(m.storeId(), m.userId(), "BONUS_ADJUSTED", "CUSTOMER", customerId.toString(), amount + " AZN");
        return account(customerId);
    }

    /** Store-wide bonus percent (0 = no bonus). Owner only. */
    @Transactional
    public BigDecimal setStorePercent(BigDecimal percent) {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.isOwner()) throw ApiException.forbidden("OWNER_ONLY", "Bonusu yalnız mağaza sahibi tənzimləyə bilər");
        if (percent == null || percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw ApiException.badRequest("INVALID_PERCENT", "Bonus faizi 0 ilə 50 arasında olmalıdır");
        }
        BigDecimal p = percent.setScale(2, RoundingMode.HALF_UP);
        jdbc.sql("UPDATE stores SET bonus_percent = :p WHERE id = :s").param("p", p).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "BONUS_PERCENT_SET", "STORE", m.storeId().toString(), p + "%");
        return p;
    }

    public BigDecimal currentStorePercent() {
        return storePercent(tenant.requireBusinessMember().storeId());
    }

    private CurrentMember requireAccess() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.has("SALES") && !m.has(TenantService.PERM_CUSTOMER_DEBTS)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return m;
    }
}
