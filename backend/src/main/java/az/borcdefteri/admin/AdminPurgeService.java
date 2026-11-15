package az.borcdefteri.admin;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Platform admin: wipes chosen parts of a store's data (stock, sales, debts, cash, expenses, customers, pocket money)
 * and finds / deletes single operations by sale number, receipt number or party name. Every delete runs inside the
 * store's purge transaction (see V19) so the append-only tables let it through, and is written to the audit log.
 */
@Service
public class AdminPurgeService {

    /** Parts an admin can wipe; some pull in others because rows reference them. */
    public static final List<String> PARTS = List.of("STOCK", "PRODUCTS", "SALES", "DEBTS", "CASH", "EXPENSES", "CUSTOMERS", "POCKET");

    private static final Map<String, List<String>> IMPLIES = Map.of(
            "PRODUCTS", List.of("STOCK", "SALES"),
            "CUSTOMERS", List.of("SALES", "DEBTS"));

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public AdminPurgeService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    // ---------- selective wipe ----------

    @Transactional
    public Map<String, Object> purge(UUID storeId, List<String> requested, String confirmName) {
        tenant.requireSuperAdmin();
        String name = lockStore(storeId, true, confirmName);
        if (requested == null || requested.isEmpty()) {
            throw ApiException.badRequest("NO_PARTS", "Silinəcək ən azı bir bölmə seçin");
        }
        Set<String> parts = new LinkedHashSet<>();
        for (String p : requested) {
            if (!PARTS.contains(p)) {
                throw ApiException.badRequest("INVALID_PART", "Naməlum bölmə: " + p);
            }
            parts.add(p);
            parts.addAll(IMPLIES.getOrDefault(p, List.of()));
        }
        long rows = 0;
        if (parts.contains("SALES")) {
            rows += deleteSales(storeId, jdbc.sql("SELECT id FROM sales WHERE store_id = :s").param("s", storeId).query(UUID.class).list());
        }
        if (parts.contains("DEBTS")) {
            rows += deleteDebts(storeId, jdbc.sql("SELECT id FROM debts WHERE store_id = :s").param("s", storeId).query(UUID.class).list());
        }
        if (parts.contains("EXPENSES")) {
            rows += del("""
                    DELETE FROM cash_transactions WHERE store_id = :s AND (expense_id IS NOT NULL
                        OR reverses_id IN (SELECT id FROM cash_transactions WHERE store_id = :s AND expense_id IS NOT NULL))""", storeId);
            for (String t : List.of("expense_attachments", "expenses", "recurring_expenses", "budgets", "expense_categories")) {
                rows += del("DELETE FROM " + t + " WHERE store_id = :s", storeId);
            }
        }
        if (parts.contains("CASH")) {
            rows += del("DELETE FROM cash_transactions WHERE store_id = :s", storeId);
        }
        if (parts.contains("STOCK")) {
            rows += del("DELETE FROM stock_count_lines WHERE store_id = :s", storeId);
            rows += del("DELETE FROM inventory_movements WHERE store_id = :s", storeId);
            rows += del("DELETE FROM stock_counts WHERE store_id = :s", storeId);
            jdbc.sql("UPDATE products SET stock_qty = 0, updated_at = now(), version = version + 1 WHERE store_id = :s")
                    .param("s", storeId).update();
        }
        if (parts.contains("PRODUCTS")) {
            rows += del("DELETE FROM products WHERE store_id = :s", storeId);
            rows += del("DELETE FROM product_categories WHERE store_id = :s", storeId);
        }
        if (parts.contains("CUSTOMERS")) {
            jdbc.sql("UPDATE products SET supplier_id = NULL WHERE store_id = :s").param("s", storeId).update();
            for (String t : List.of("customer_notes", "customer_portal_tokens", "customers", "companies")) {
                rows += del("DELETE FROM " + t + " WHERE store_id = :s", storeId);
            }
        }
        if (parts.contains("POCKET")) {
            rows += del("DELETE FROM pocket_entries WHERE store_id = :s", storeId);
        }
        endPurge();
        audit.log(storeId, tenant.currentUserId(), "STORE_DATA_RESET", "STORE", storeId.toString(),
                "Seçilmiş məlumatlar silindi: " + String.join(", ", parts) + " (" + rows + " sətir)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", storeId);
        out.put("store", name);
        out.put("parts", parts);
        out.put("rows", rows);
        return out;
    }

    // ---------- find one operation ----------

    /** Sales (by number), debts (by receipt number) and payments, matching the text; the latest ones when it is empty. */
    public List<Map<String, Object>> findOperations(UUID storeId, String q) {
        tenant.requireSuperAdmin();
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.sql("""
                SELECT s.id, s.number, s.total, s.status, s.created_at, c.name AS party
                FROM sales s LEFT JOIN customers c ON c.id = s.customer_id
                WHERE s.store_id = :s AND (LOWER(s.number) LIKE :q OR LOWER(COALESCE(c.name, '')) LIKE :q)
                ORDER BY s.created_at DESC LIMIT 40
                """).param("s", storeId).param("q", like).query((rs, n) -> op("SALE", rs.getObject("id", UUID.class),
                        rs.getString("number"), rs.getString("party"), rs.getBigDecimal("total"),
                        rs.getObject("created_at", OffsetDateTime.class), "CANCELLED".equals(rs.getString("status")) ? "Ləğv edilib" : "Satış"))
                .list().forEach(out::add);
        jdbc.sql("""
                SELECT d.id, d.amount, d.created_at, d.debt_type, d.cancelled_at, COALESCE(c.name, co.name) AS party,
                       (SELECT string_agg(t.receipt_number, ', ') FROM financial_transactions t
                         WHERE t.debt_id = d.id AND t.receipt_number IS NOT NULL) AS receipts
                FROM debts d LEFT JOIN customers c ON c.id = d.customer_id LEFT JOIN companies co ON co.id = d.company_id
                WHERE d.store_id = :s AND (LOWER(COALESCE(c.name, co.name, '')) LIKE :q
                      OR EXISTS (SELECT 1 FROM financial_transactions t WHERE t.debt_id = d.id AND LOWER(COALESCE(t.receipt_number, '')) LIKE :q))
                ORDER BY d.created_at DESC LIMIT 40
                """).param("s", storeId).param("q", like).query((rs, n) -> op("DEBT", rs.getObject("id", UUID.class),
                        rs.getString("receipts"), rs.getString("party"), rs.getBigDecimal("amount"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        (rs.getObject("cancelled_at") != null ? "Ləğv edilmiş borc · " : "Borc · ") + typeLabel(rs.getString("debt_type"))))
                .list().forEach(out::add);
        jdbc.sql("""
                SELECT p.id, p.amount, p.paid_at, p.voided_at, COALESCE(c.name, co.name) AS party
                FROM payments p JOIN debts d ON d.id = p.debt_id
                LEFT JOIN customers c ON c.id = d.customer_id LEFT JOIN companies co ON co.id = d.company_id
                WHERE p.store_id = :s AND (LOWER(COALESCE(c.name, co.name, '')) LIKE :q OR CAST(p.amount AS text) LIKE :q)
                ORDER BY p.paid_at DESC LIMIT 40
                """).param("s", storeId).param("q", like).query((rs, n) -> op("PAYMENT", rs.getObject("id", UUID.class),
                        null, rs.getString("party"), rs.getBigDecimal("amount"), rs.getObject("paid_at", OffsetDateTime.class),
                        rs.getObject("voided_at") != null ? "Ləğv edilmiş ödəniş" : "Ödəniş"))
                .list().forEach(out::add);
        out.sort((a, b) -> ((OffsetDateTime) b.get("at")).compareTo((OffsetDateTime) a.get("at")));
        return out;
    }

    /** Deletes one sale (stock comes back, its money and its credit debt go), debt (with payments) or payment. */
    @Transactional
    public Map<String, Object> deleteOperation(UUID storeId, String type, UUID opId) {
        tenant.requireSuperAdmin();
        lockStore(storeId, false, null);
        long rows;
        String what;
        switch (type) {
            case "SALE" -> {
                what = jdbc.sql("SELECT number FROM sales WHERE id = :id AND store_id = :s").param("id", opId).param("s", storeId)
                        .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Satış tapılmadı"));
                rows = deleteSales(storeId, List.of(opId));
            }
            case "DEBT" -> {
                what = "borc " + jdbc.sql("SELECT amount FROM debts WHERE id = :id AND store_id = :s").param("id", opId).param("s", storeId)
                        .query(BigDecimal.class).optional().orElseThrow(() -> ApiException.notFound("Borc tapılmadı")) + " AZN";
                rows = deleteDebts(storeId, List.of(opId));
            }
            case "PAYMENT" -> {
                record P(UUID debt, BigDecimal amount) {}
                P p = jdbc.sql("SELECT debt_id, amount FROM payments WHERE id = :id AND store_id = :s").param("id", opId).param("s", storeId)
                        .query((rs, n) -> new P(rs.getObject("debt_id", UUID.class), rs.getBigDecimal("amount")))
                        .optional().orElseThrow(() -> ApiException.notFound("Ödəniş tapılmadı"));
                what = "ödəniş " + p.amount() + " AZN";
                rows = deletePayments(storeId, List.of(opId));
                recomputeDebt(storeId, p.debt());
            }
            default -> throw ApiException.badRequest("INVALID_TYPE", "Naməlum əməliyyat növü");
        }
        endPurge();
        audit.log(storeId, tenant.currentUserId(), "OPERATION_DELETED", type, opId.toString(),
                "Admin əməliyyatı sildi: " + what + " (" + rows + " sətir)");
        return Map.of("id", opId, "rows", rows);
    }

    // ---------- helpers ----------

    /** Locks the store row, checks the typed name when asked, and opens the purge for this transaction. */
    private String lockStore(UUID storeId, boolean checkName, String confirmName) {
        String name = jdbc.sql("SELECT name FROM stores WHERE id = :id FOR UPDATE").param("id", storeId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Mağaza tapılmadı"));
        if (checkName && (confirmName == null
                || !name.trim().toLowerCase(Locale.ROOT).equals(confirmName.trim().toLowerCase(Locale.ROOT)))) {
            throw ApiException.badRequest("CONFIRM_NAME_MISMATCH", "Təsdiq üçün mağazanın adını dəqiq yazın: " + name);
        }
        jdbc.sql("SELECT set_config('app.store_purge', :id, true)").param("id", storeId.toString()).query(String.class).single();
        return name;
    }

    private void endPurge() {
        jdbc.sql("SELECT set_config('app.store_purge', '', true)").query(String.class).single();
    }

    private long del(String sql, UUID storeId) {
        return jdbc.sql(sql).param("s", storeId).update();
    }

    /**
     * Sales with their items, returns, money and stock movements (stock is put back). A credit sale wrote its unpaid
     * part onto the customer's debt (a ledger row with the sale number as receipt); only those rows go, and the debt
     * is recomputed from what is left — or removed when nothing else was on it.
     */
    private long deleteSales(UUID storeId, List<UUID> saleIds) {
        if (saleIds.isEmpty()) {
            return 0;
        }
        record SaleDebt(UUID debt, String number) {}
        List<SaleDebt> debts = jdbc.sql("SELECT debt_id, number FROM sales WHERE store_id = :s AND id IN (:ids) AND debt_id IS NOT NULL")
                .param("s", storeId).param("ids", saleIds)
                .query((rs, n) -> new SaleDebt(rs.getObject("debt_id", UUID.class), rs.getString("number"))).list();
        jdbc.sql("""
                UPDATE products p SET stock_qty = p.stock_qty - x.q, updated_at = now(), version = p.version + 1
                FROM (SELECT product_id, SUM(quantity) AS q FROM inventory_movements
                      WHERE store_id = :s AND sale_id IN (:ids) GROUP BY product_id) x
                WHERE p.id = x.product_id AND p.store_id = :s
                """).param("s", storeId).param("ids", saleIds).update();
        long rows = jdbc.sql("DELETE FROM inventory_movements WHERE store_id = :s AND sale_id IN (:ids)")
                .param("s", storeId).param("ids", saleIds).update();
        rows += jdbc.sql("""
                DELETE FROM cash_transactions WHERE store_id = :s AND (sale_id IN (:ids)
                    OR reverses_id IN (SELECT id FROM cash_transactions WHERE store_id = :s AND sale_id IN (:ids)))
                """).param("s", storeId).param("ids", saleIds).update();
        for (String t : List.of("sale_returns", "sale_items")) {
            rows += jdbc.sql("DELETE FROM " + t + " WHERE store_id = :s AND sale_id IN (:ids)")
                    .param("s", storeId).param("ids", saleIds).update();
        }
        rows += jdbc.sql("DELETE FROM sales WHERE store_id = :s AND id IN (:ids)").param("s", storeId).param("ids", saleIds).update();
        for (SaleDebt d : debts) {
            rows += removeSaleFromDebt(storeId, d.debt(), d.number());
        }
        return rows;
    }

    /** Drops a sale's rows from a debt's history (its charge and its returns) and recomputes the debt. */
    private long removeSaleFromDebt(UUID storeId, UUID debtId, String saleNumber) {
        String entries = """
                SELECT id FROM financial_transactions WHERE store_id = :s AND debt_id = :d AND payment_id IS NULL
                  AND (receipt_number = :no OR note LIKE :ret)""";
        long rows = jdbc.sql("DELETE FROM ledger_hidden WHERE store_id = :s AND entry_id IN (" + entries + ")")
                .param("s", storeId).param("d", debtId).param("no", saleNumber).param("ret", saleNumber + " qaytarma%").update();
        rows += jdbc.sql("DELETE FROM financial_transactions WHERE store_id = :s AND debt_id = :d AND (id IN (" + entries
                        + ") OR reverses_entry_id IN (" + entries + "))")
                .param("s", storeId).param("d", debtId).param("no", saleNumber).param("ret", saleNumber + " qaytarma%").update();
        long charges = jdbc.sql("""
                SELECT COUNT(*) FROM financial_transactions WHERE debt_id = :d AND transaction_type IN ('DEBT_CREATED', 'DEBT_INCREASED')
                """).param("d", debtId).query(Long.class).single();
        if (charges == 0) {
            return rows + deleteDebts(storeId, List.of(debtId));
        }
        jdbc.sql("""
                UPDATE debts d SET amount = GREATEST(x.amt, x.paid), paid_amount = x.paid,
                       remaining_amount = GREATEST(x.amt, x.paid) - x.paid,
                       status = CASE WHEN x.amt <= x.paid THEN 'PAID' WHEN x.paid > 0 THEN 'PARTIAL' ELSE 'OPEN' END,
                       updated_at = now(), version = d.version + 1
                FROM (SELECT (SELECT COALESCE(SUM(amount), 0) FROM financial_transactions WHERE debt_id = :d
                              AND transaction_type IN ('DEBT_CREATED', 'DEBT_INCREASED', 'DEBT_ADJUSTED')) AS amt,
                             (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE debt_id = :d AND voided_at IS NULL) AS paid) x
                WHERE d.id = :d AND d.store_id = :s
                """).param("d", debtId).param("s", storeId).update();
        return rows;
    }

    /** Debts with their history, payments and the payments' cash movements. */
    private long deleteDebts(UUID storeId, List<UUID> debtIds) {
        if (debtIds.isEmpty()) {
            return 0;
        }
        List<UUID> payments = jdbc.sql("SELECT id FROM payments WHERE store_id = :s AND debt_id IN (:ids)")
                .param("s", storeId).param("ids", debtIds).query(UUID.class).list();
        long rows = jdbc.sql("""
                DELETE FROM ledger_hidden WHERE store_id = :s
                  AND entry_id IN (SELECT id FROM financial_transactions WHERE debt_id IN (:ids))
                """).param("s", storeId).param("ids", debtIds).update();
        rows += jdbc.sql("DELETE FROM financial_transactions WHERE store_id = :s AND debt_id IN (:ids)")
                .param("s", storeId).param("ids", debtIds).update();
        rows += deletePaymentRows(storeId, payments);
        jdbc.sql("UPDATE sales SET debt_id = NULL WHERE store_id = :s AND debt_id IN (:ids)")
                .param("s", storeId).param("ids", debtIds).update();
        rows += jdbc.sql("DELETE FROM debts WHERE store_id = :s AND id IN (:ids)").param("s", storeId).param("ids", debtIds).update();
        return rows;
    }

    /** One or more payments with their ledger rows and cash movements (the debt is recomputed by the caller). */
    private long deletePayments(UUID storeId, List<UUID> paymentIds) {
        long rows = jdbc.sql("""
                DELETE FROM ledger_hidden WHERE store_id = :s
                  AND entry_id IN (SELECT id FROM financial_transactions WHERE payment_id IN (:ids))
                """).param("s", storeId).param("ids", paymentIds).update();
        rows += jdbc.sql("DELETE FROM financial_transactions WHERE store_id = :s AND payment_id IN (:ids)")
                .param("s", storeId).param("ids", paymentIds).update();
        return rows + deletePaymentRows(storeId, paymentIds);
    }

    private long deletePaymentRows(UUID storeId, List<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return 0;
        }
        long rows = jdbc.sql("""
                DELETE FROM cash_transactions WHERE store_id = :s AND (payment_id IN (:ids)
                    OR reverses_id IN (SELECT id FROM cash_transactions WHERE store_id = :s AND payment_id IN (:ids)))
                """).param("s", storeId).param("ids", paymentIds).update();
        return rows + jdbc.sql("DELETE FROM payments WHERE store_id = :s AND id IN (:ids)")
                .param("s", storeId).param("ids", paymentIds).update();
    }

    /** After a payment is gone: paid = live payments, remaining = amount − paid. */
    private void recomputeDebt(UUID storeId, UUID debtId) {
        jdbc.sql("""
                UPDATE debts d SET paid_amount = x.paid,
                       remaining_amount = GREATEST(d.amount - x.paid, 0),
                       status = CASE WHEN d.amount - x.paid <= 0 THEN 'PAID' WHEN x.paid > 0 THEN 'PARTIAL' ELSE 'OPEN' END,
                       updated_at = now(), version = d.version + 1
                FROM (SELECT COALESCE(SUM(amount), 0) AS paid FROM payments
                      WHERE debt_id = :d AND store_id = :s AND voided_at IS NULL) x
                WHERE d.id = :d AND d.store_id = :s
                """).param("d", debtId).param("s", storeId).update();
    }

    private static Map<String, Object> op(String type, UUID id, String number, String party, BigDecimal amount,
                                          OffsetDateTime at, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("number", number);
        m.put("party", party);
        m.put("amount", Money.of(amount));
        m.put("at", at);
        m.put("label", label);
        return m;
    }

    private static String typeLabel(String debtType) {
        return switch (debtType) {
            case "CUSTOMER_DEBT" -> "müştəri";
            case "COMPANY_RECEIVABLE" -> "şirkətin bizə borcu";
            default -> "bizim şirkətə borcumuz";
        };
    }
}
