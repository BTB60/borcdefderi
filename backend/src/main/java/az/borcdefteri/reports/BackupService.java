package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.time.temporal.TemporalAccessor;
import java.util.UUID;

/**
 * Ehtiyat nüsxəsi: everything of the store in one Excel file, one sheet per section — for the owner to keep or to move
 * to another program. Owner only.
 */
@Service
public class BackupService {

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public BackupService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public ExportFile export() {
        CurrentMember m = tenant.requireMember();
        if (!m.isOwner()) throw ApiException.forbidden("OWNER_ONLY", "Ehtiyat nüsxəsini yalnız mağaza sahibi yükləyə bilər");
        UUID s = m.storeId();
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            sheet(wb, head, s, "Müştərilər", """
                    SELECT code AS "Kod", name AS "Ad", phone AS "Telefon", discount_percent AS "Endirim %", note AS "Qeyd", created_at AS "Yaradılıb"
                    FROM customers WHERE store_id = :s ORDER BY code""");
            sheet(wb, head, s, "Şirkətlər", """
                    SELECT name AS "Ad", phone AS "Telefon", note AS "Qeyd", created_at AS "Yaradılıb" FROM companies WHERE store_id = :s ORDER BY name""");
            sheet(wb, head, s, "Borclar", """
                    SELECT d.debt_type AS "Növ", COALESCE(c.name, co.name) AS "Tərəf", d.amount AS "Məbləğ", d.paid_amount AS "Ödənilib",
                           d.remaining_amount AS "Qalıq", d.status AS "Status", d.debt_date AS "Tarix", d.due_date AS "Son ödəmə", d.description AS "Təsvir"
                    FROM debts d LEFT JOIN customers c ON c.id = d.customer_id LEFT JOIN companies co ON co.id = d.company_id
                    WHERE d.store_id = :s ORDER BY d.debt_date""");
            sheet(wb, head, s, "Ödənişlər", """
                    SELECT p.paid_at AS "Tarix", COALESCE(c.name, co.name) AS "Tərəf", p.amount AS "Məbləğ", p.payment_method AS "Üsul",
                           p.description AS "Təsvir", CASE WHEN p.voided_at IS NULL THEN '' ELSE 'ləğv edilib' END AS "Status"
                    FROM payments p JOIN debts d ON d.id = p.debt_id LEFT JOIN customers c ON c.id = d.customer_id
                    LEFT JOIN companies co ON co.id = d.company_id WHERE p.store_id = :s ORDER BY p.paid_at""");
            sheet(wb, head, s, "Məhsullar", """
                    SELECT p.code AS "Kod", p.barcode AS "Barkod", p.name AS "Ad", c.name AS "Kateqoriya", p.unit AS "Vahid",
                           p.purchase_price AS "Alış", p.wholesale_price AS "Top 1 (topdan)", p.sale_price AS "Top 2 (pərakəndə)",
                           p.stock_qty AS "Qalıq", p.min_stock AS "Minimum", p.active AS "Aktiv"
                    FROM products p LEFT JOIN product_categories c ON c.id = p.category_id WHERE p.store_id = :s ORDER BY p.code""");
            sheet(wb, head, s, "Satışlar", """
                    SELECT s.number AS "Nömrə", s.sold_on AS "Tarix", c.name AS "Müştəri", s.total AS "Cəmi", s.discount AS "Endirim",
                           s.paid_cash AS "Nağd", s.paid_card AS "Kart", s.credit_amount AS "Borca", s.returned_amount AS "Qaytarılıb",
                           s.status AS "Status", u.full_name AS "Kassir"
                    FROM sales s LEFT JOIN customers c ON c.id = s.customer_id JOIN users u ON u.id = s.created_by
                    WHERE s.store_id = :s ORDER BY s.created_at""");
            sheet(wb, head, s, "Satış sətirləri", """
                    SELECT s.number AS "Satış", i.name AS "Məhsul", i.quantity AS "Miqdar", i.unit_price AS "Qiymət",
                           i.discount AS "Endirim", i.line_total AS "Cəmi", i.returned_qty AS "Qaytarılıb"
                    FROM sale_items i JOIN sales s ON s.id = i.sale_id WHERE i.store_id = :s ORDER BY s.created_at""");
            sheet(wb, head, s, "Kassa", """
                    SELECT t.occurred_on AS "Tarix", a.name AS "Hesab", t.direction AS "İstiqamət", t.kind AS "Növ", t.amount AS "Məbləğ",
                           t.note AS "Qeyd", u.full_name AS "İşçi"
                    FROM cash_transactions t JOIN cash_accounts a ON a.id = t.account_id JOIN users u ON u.id = t.actor_id
                    WHERE t.store_id = :s ORDER BY t.created_at""");
            sheet(wb, head, s, "Xərclər", """
                    SELECT e.expense_date AS "Tarix", c.name AS "Kateqoriya", e.amount AS "Məbləğ", e.note AS "Qeyd"
                    FROM expenses e JOIN expense_categories c ON c.id = e.category_id WHERE e.store_id = :s ORDER BY e.expense_date""");
            sheet(wb, head, s, "Mal qəbulu", """
                    SELECT p.number AS "Nömrə", p.received_on AS "Tarix", c.name AS "Təchizatçı", p.supplier_invoice AS "Qaimə",
                           p.total AS "Cəmi", p.paid AS "Ödənilib", p.returned_amount AS "Qaytarılıb"
                    FROM purchases p LEFT JOIN companies c ON c.id = p.company_id WHERE p.store_id = :s ORDER BY p.created_at""");
            wb.write(out);
            wb.dispose();
            return new ExportFile(out.toByteArray(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "hesabpro-ehtiyat-" + tenant.today() + ".xlsx");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void sheet(SXSSFWorkbook wb, CellStyle head, UUID storeId, String name, String sql) {
        Sheet sh = wb.createSheet(name);
        int[] r = {0};
        jdbc.sql(sql).param("s", storeId).query(rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            if (r[0] == 0) {
                Row h = sh.createRow(r[0]++);
                for (int i = 1; i <= cols; i++) {
                    var c = h.createCell(i - 1);
                    c.setCellValue(md.getColumnLabel(i));
                    c.setCellStyle(head);
                    sh.setColumnWidth(i - 1, 18 * 256);
                }
            }
            Row row = sh.createRow(r[0]++);
            for (int i = 1; i <= cols; i++) {
                Object v = rs.getObject(i);
                var c = row.createCell(i - 1);
                if (v == null) continue;
                if (v instanceof BigDecimal b) c.setCellValue(b.doubleValue());
                else if (v instanceof Number n) c.setCellValue(n.doubleValue());
                else if (v instanceof Boolean b) c.setCellValue(b ? "Bəli" : "Xeyr");
                else if (v instanceof java.sql.Timestamp t) c.setCellValue(t.toLocalDateTime().toString().replace('T', ' '));
                else if (v instanceof TemporalAccessor) c.setCellValue(v.toString().replace('T', ' '));
                else c.setCellValue(v.toString());
            }
        });
        if (r[0] == 0) sh.createRow(0).createCell(0).setCellValue("Məlumat yoxdur");
    }
}
