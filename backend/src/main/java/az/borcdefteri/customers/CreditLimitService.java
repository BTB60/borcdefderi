package az.borcdefteri.customers;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Müştəri borc limiti: new debt (a credit sale, "Borc yaz", "Borcu artır", "Borc əlavə et") may not push the
 * customer's open debt over their limit. The owner may let it through ({@code allow}); anyone else gets
 * CREDIT_LIMIT with the numbers (limit, owes, after).
 */
@Service
public class CreditLimitService {

    private final JdbcClient jdbc;

    public CreditLimitService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void check(CurrentMember m, UUID customerId, BigDecimal add, boolean allow) {
        BigDecimal limit = jdbc.sql("SELECT credit_limit FROM customers WHERE id = :c AND store_id = :s")
                .param("c", customerId).param("s", m.storeId()).query(BigDecimal.class).optional().orElse(null);
        if (limit == null) return;
        BigDecimal owes = Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(remaining_amount), 0) FROM debts
                WHERE store_id = :s AND customer_id = :c AND status <> 'PAID'
                """).param("s", m.storeId()).param("c", customerId).query(BigDecimal.class).single());
        BigDecimal after = Money.of(owes.add(add));
        if (after.compareTo(limit) <= 0) return;
        if (allow && m.isOwner()) return;
        Map<String, String> details = new LinkedHashMap<>();
        details.put("limit", Money.of(limit).toPlainString());
        details.put("owes", owes.toPlainString());
        details.put("after", after.toPlainString());
        throw new ApiException(HttpStatus.CONFLICT, "CREDIT_LIMIT",
                "Müştərinin borc limiti " + Money.of(limit) + " AZN-dir. İndiki borcu " + owes + " AZN, bununla "
                        + after + " AZN olur" + (m.isOwner() ? "" : ". Mağaza sahibinin icazəsi lazımdır"), details);
    }
}
