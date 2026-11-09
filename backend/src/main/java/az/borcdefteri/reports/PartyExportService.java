package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Excel lists of customers and companies with their balances (REPORTS permission). */
@Service
public class PartyExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public PartyExportService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    private record Line(Object[] cells) {}

    public ExportFile customers() {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        if (!m.has(TenantService.PERM_CUSTOMER_DEBTS)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        List<Line> rows = jdbc.sql("""
                SELECT c.code, c.name, c.phone, c.created_at,
                       COALESCE(SUM(d.amount), 0) AS total, COALESCE(SUM(d.paid_amount), 0) AS paid,
                       COALESCE(SUM(d.remaining_amount), 0) AS remaining,
                       COUNT(d.id) FILTER (WHERE d.remaining_amount > 0 AND d.due_date < :today) AS overdue
                FROM customers c LEFT JOIN debts d ON d.customer_id = c.id AND d.store_id = c.store_id
                WHERE c.store_id = :s GROUP BY c.id, c.code, c.name, c.phone, c.created_at ORDER BY c.code
                """).param("s", m.storeId()).param("today", tenant.today())
                .query((rs, n) -> new Line(new Object[]{rs.getString("code"), rs.getString("name"), nz(rs.getString("phone")),
                        date(rs.getObject("created_at", OffsetDateTime.class)), Money.of(rs.getBigDecimal("total")),
                        Money.of(rs.getBigDecimal("paid")), Money.of(rs.getBigDecimal("remaining")),
                        rs.getLong("overdue") > 0 ? "Gecikmiş" : rs.getBigDecimal("remaining").signum() > 0 ? "Aktiv" : "Borcsuz"}))
                .list();
        return excel("Müştərilər", new String[]{"Kod", "Ad", "Telefon", "Qeydiyyat", "Ümumi borc", "Ödənilib", "Qalıq", "Status"},
                rows, "musteriler");
    }

    public ExportFile companies() {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        boolean rec = m.has(TenantService.PERM_COMPANY_RECEIVABLES);
        boolean pay = m.has(TenantService.PERM_COMPANY_PAYABLES);
        if (!rec && !pay) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        List<Line> rows = jdbc.sql("""
                SELECT co.name, co.phone,
                       COALESCE(SUM(d.amount) FILTER (WHERE d.debt_type = 'COMPANY_RECEIVABLE'), 0) AS rec_total,
                       COALESCE(SUM(d.remaining_amount) FILTER (WHERE d.debt_type = 'COMPANY_RECEIVABLE'), 0) AS rec_rem,
                       COALESCE(SUM(d.amount) FILTER (WHERE d.debt_type = 'COMPANY_PAYABLE'), 0) AS pay_total,
                       COALESCE(SUM(d.remaining_amount) FILTER (WHERE d.debt_type = 'COMPANY_PAYABLE'), 0) AS pay_rem
                FROM companies co LEFT JOIN debts d ON d.company_id = co.id AND d.store_id = co.store_id
                WHERE co.store_id = :s GROUP BY co.id, co.name, co.phone ORDER BY co.name
                """).param("s", m.storeId())
                .query((rs, n) -> new Line(new Object[]{rs.getString("name"), nz(rs.getString("phone")),
                        rec ? Money.of(rs.getBigDecimal("rec_total")) : "—", rec ? Money.of(rs.getBigDecimal("rec_rem")) : "—",
                        pay ? Money.of(rs.getBigDecimal("pay_total")) : "—", pay ? Money.of(rs.getBigDecimal("pay_rem")) : "—"}))
                .list();
        return excel("Şirkətlər", new String[]{"Şirkət", "Telefon", "Bizə borc (cəmi)", "Bizə borc (qalıq)",
                "Bizim borc (cəmi)", "Bizim borc (qalıq)"}, rows, "sirketler");
    }

    private ExportFile excel(String sheetName, String[] headers, List<Line> rows, String fileBase) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(new byte[]{(byte) 0x10, (byte) 0x1C, (byte) 0x35}, null));
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            Row h = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                var c = h.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, (i == 1 || i == 0 ? 28 : 18) * 256);
            }
            int r = 1;
            for (Line line : rows) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < line.cells().length; i++) {
                    Object v = line.cells()[i];
                    var c = row.createCell(i);
                    if (v instanceof BigDecimal b) {
                        c.setCellValue(b.doubleValue());
                        c.setCellStyle(money);
                    } else {
                        c.setCellValue(v == null ? "" : v.toString());
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            wb.write(out);
            String stamp = tenant.today().format(DateTimeFormatter.BASIC_ISO_DATE);
            return new ExportFile(out.toByteArray(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    fileBase + "-" + stamp + ".xlsx");
        } catch (java.io.IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Excel faylı yaradıla bilmədi");
        }
    }

    private static String date(OffsetDateTime t) {
        return t == null ? "" : t.atZoneSameInstant(ZoneId.of("Asia/Baku")).toLocalDate().format(DATE);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
