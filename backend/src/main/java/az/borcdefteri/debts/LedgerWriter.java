package az.borcdefteri.debts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Appends to the immutable financial ledger (table financial_transactions; UPDATE/DELETE forbidden by trigger). */
@Component
public class LedgerWriter {

    private final JdbcClient jdbc;

    public LedgerWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID storeId, UUID debtId, UUID paymentId, String type,
                       BigDecimal amount, BigDecimal balanceAfter, UUID actorId) {
        jdbc.sql("""
                INSERT INTO financial_transactions (id, store_id, debt_id, payment_id, transaction_type, amount, balance_after, actor_id)
                VALUES (:id, :storeId, :debtId, :paymentId, :type, :amount, :balance, :actor)
                """)
                .param("id", UUID.randomUUID())
                .param("storeId", storeId)
                .param("debtId", debtId)
                .param("paymentId", paymentId)
                .param("type", type)
                .param("amount", amount)
                .param("balance", balanceAfter)
                .param("actor", actorId)
                .update();
    }
}
