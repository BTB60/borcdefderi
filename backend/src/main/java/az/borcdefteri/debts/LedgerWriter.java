package az.borcdefteri.debts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Appends to the immutable financial ledger (table financial_transactions; UPDATE/DELETE forbidden by trigger).
 * created_at is clock_timestamp(), not now(): several entries of one transaction (a payment correction writes a
 * REVERSAL and a new payment) must keep their order.
 */
@Component
public class LedgerWriter {

    private final JdbcClient jdbc;

    public LedgerWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID storeId, UUID debtId, UUID paymentId, String type,
                       BigDecimal amount, BigDecimal balanceAfter, UUID actorId) {
        append(storeId, debtId, paymentId, type, amount, balanceAfter, actorId, null, null, null);
    }

    /** Same, with the business date, receipt (çek) number and note of the entry (used by "Borc artır"). */
    public void append(UUID storeId, UUID debtId, UUID paymentId, String type, BigDecimal amount,
                       BigDecimal balanceAfter, UUID actorId, java.time.LocalDate entryDate,
                       String receiptNumber, String note) {
        jdbc.sql("""
                INSERT INTO financial_transactions (id, store_id, debt_id, payment_id, transaction_type, amount,
                                                    balance_after, actor_id, entry_date, receipt_number, note, created_at)
                VALUES (:id, :storeId, :debtId, :paymentId, :type, :amount, :balance, :actor,
                        :entryDate, :receipt, :note, clock_timestamp())
                """)
                .param("entryDate", entryDate)
                .param("receipt", receiptNumber)
                .param("note", note)
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
