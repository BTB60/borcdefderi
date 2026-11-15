package az.borcdefteri.customers;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Excel import of customers / companies, e.g. when a store moves over from another program. The file is checked
 * first and nothing is saved while a row has a problem. A row whose phone or name already exists is skipped, so
 * importing the same file twice does not double anything. An opening balance becomes the party's first debt.
 */
@Service
public class PartyImportService {

    private static final int MAX_ROWS = 5000;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public record RowError(int row, String message) {}

    public record Result(boolean applied, int created, int skipped, int withBalance, List<RowError> errors) {}

    private record Parsed(int row, String name, String phone, String note, BigDecimal owesUs, BigDecimal weOwe) {}

    private final PartyService parties;
    private final PartyAccountService accounts;
    private final TenantService tenant;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public PartyImportService(PartyService parties, PartyAccountService accounts, TenantService tenant, JdbcClient jdbc,
                              AuditService audit) {
        this.parties = parties;
        this.accounts = accounts;
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String[] headers(boolean customers) {
        return customers ? new String[]{"Ad", "Telefon", "Qeyd", "Borc"}
                : new String[]{"Ad", "Telefon", "Qeyd", "Bizə borcu", "Bizim borcumuz"};
    }

    public ExportFile template(boolean customers) {
        if (customers) tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        else tenant.requireBusinessMember();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(customers ? "Müştərilər" : "Şirkətlər");
            CellStyle head = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            head.setFont(font);
            head.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            String[] h = headers(customers);
            Row r = sheet.createRow(0);
            for (int i = 0; i < h.length; i++) {
                Cell c = r.createCell(i);
                c.setCellValue(h[i]);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, (i == 0 ? 32 : 18) * 256);
            }
            Row ex = sheet.createRow(1);
            if (customers) {
                ex.createCell(0).setCellValue("Nümunə Müştəri");
                ex.createCell(1).setCellValue("+994501234567");
                ex.createCell(2).setCellValue("Bu sətri silin");
                ex.createCell(3).setCellValue(150.0);
            } else {
                ex.createCell(0).setCellValue("Nümunə MMC");
                ex.createCell(1).setCellValue("+994125550000");
                ex.createCell(2).setCellValue("Bu sətri silin");
                ex.createCell(3).setCellValue(0.0);
                ex.createCell(4).setCellValue(1200.0);
            }
            wb.write(out);
            return new ExportFile(out.toByteArray(), XLSX, (customers ? "musteri" : "sirket") + "-import-sablonu.xlsx");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public Result importExcel(boolean customers, MultipartFile file) {
        CurrentMember m = customers ? tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS) : tenant.requireBusinessMember();
        if (file == null || file.isEmpty()) throw ApiException.badRequest("EMPTY_FILE", "Excel faylı seçin");
        List<RowError> errors = new ArrayList<>();
        List<Parsed> rows = read(customers, file, errors);
        if (rows.isEmpty() && errors.isEmpty()) errors.add(new RowError(0, "Faylda sətir tapılmadı"));
        if (!customers) {
            for (Parsed r : rows) {
                if (r.owesUs() != null && r.owesUs().signum() > 0 && !m.has(DebtType.COMPANY_RECEIVABLE.permission())) {
                    errors.add(new RowError(r.row(), "“Şirkətin bizə borcu” bölməsinə icazəniz yoxdur"));
                }
                if (r.weOwe() != null && r.weOwe().signum() > 0 && !m.has(DebtType.COMPANY_PAYABLE.permission())) {
                    errors.add(new RowError(r.row(), "“Bizim şirkətə borcumuz” bölməsinə icazəniz yoxdur"));
                }
            }
        }
        if (!errors.isEmpty()) {
            errors.sort((a, b) -> Integer.compare(a.row(), b.row()));
            return new Result(false, 0, 0, 0, errors);
        }

        String table = customers ? "customers" : "companies";
        Set<String> phones = new HashSet<>();
        Set<String> names = new HashSet<>();
        jdbc.sql("SELECT name, phone FROM " + table + " WHERE store_id = :s").param("s", m.storeId())
                .query((rs, n) -> {
                    names.add(rs.getString("name").trim().toLowerCase(Locale.ROOT));
                    String p = digits(rs.getString("phone"));
                    if (p != null) phones.add(p);
                    return null;
                }).list();

        int created = 0, skipped = 0, withBalance = 0;
        for (Parsed r : rows) {
            String phone = digits(r.phone());
            if ((phone != null && !phones.add(phone)) || !names.add(r.name().toLowerCase(Locale.ROOT))) {
                skipped++;
                continue;
            }
            try {
                PartyRequest req = new PartyRequest(r.name(), r.phone(), r.note());
                UUID id = customers ? parties.createCustomer(req).id() : parties.createCompany(req).id();
                created++;
                boolean balance = false;
                if (customers && r.owesUs() != null && r.owesUs().signum() > 0) {
                    accounts.charge(true, id, opening(null, r.owesUs()));
                    balance = true;
                }
                if (!customers && r.owesUs() != null && r.owesUs().signum() > 0) {
                    accounts.charge(false, id, opening(DebtType.COMPANY_RECEIVABLE, r.owesUs()));
                    balance = true;
                }
                if (!customers && r.weOwe() != null && r.weOwe().signum() > 0) {
                    accounts.charge(false, id, opening(DebtType.COMPANY_PAYABLE, r.weOwe()));
                    balance = true;
                }
                if (balance) withBalance++;
            } catch (ApiException e) {
                throw ApiException.badRequest("IMPORT_FAILED", "Sətir " + r.row() + ": " + e.getMessage());
            }
        }
        audit.log(m.storeId(), m.userId(), customers ? "CUSTOMERS_IMPORTED" : "COMPANIES_IMPORTED", customers ? "CUSTOMER" : "COMPANY",
                "excel", "yeni " + created + ", mövcud olduğu üçün keçilən " + skipped + ", borclu " + withBalance);
        return new Result(true, created, skipped, withBalance, List.of());
    }

    private PartyAccountRequests.Charge opening(DebtType type, BigDecimal amount) {
        return new PartyAccountRequests.Charge(type, amount, tenant.today(), null, "Başlanğıc qalıq (Excel import)", null, null);
    }

    private List<Parsed> read(boolean customers, MultipartFile file, List<RowError> errors) {
        List<Parsed> out = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) return out;
            Map<String, Integer> col = new HashMap<>();
            for (Cell c : header) col.put(norm(fmt.formatCellValue(c)), c.getColumnIndex());
            if (!col.containsKey(norm("Ad"))) {
                errors.add(new RowError(1, "“Ad” sütunu tapılmadı. Sütunlar: " + String.join(", ", headers(customers))));
                return out;
            }
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                int line = i + 1;
                String name = text(row, col, "Ad", fmt);
                String phone = text(row, col, "Telefon", fmt);
                if (name == null && phone == null) continue;
                if (out.size() >= MAX_ROWS) {
                    errors.add(new RowError(line, "Bir faylda ən çox " + MAX_ROWS + " sətir ola bilər"));
                    break;
                }
                List<String> problems = new ArrayList<>();
                if (name == null) problems.add("Ad boşdur");
                else if (name.length() > 150) problems.add("Ad 150 simvoldan uzundur");
                if (phone != null && !phone.matches("^[+0-9 ()-]{0,30}$")) problems.add("Telefon nömrəsi düzgün deyil: “" + phone + "”");
                String note = text(row, col, "Qeyd", fmt);
                if (note != null && note.length() > 1000) problems.add("Qeyd 1000 simvoldan uzundur");
                BigDecimal owes = number(row, col, customers ? "Borc" : "Bizə borcu", fmt, problems);
                BigDecimal weOwe = customers ? null : number(row, col, "Bizim borcumuz", fmt, problems);
                if (!problems.isEmpty()) {
                    errors.add(new RowError(line, String.join("; ", problems)));
                    continue;
                }
                out.add(new Parsed(line, name, phone, note, owes, weOwe));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_FILE", "Fayl oxuna bilmədi. Excel (.xlsx) faylı seçin");
        }
        return out;
    }

    private static String digits(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("[^0-9]", "");
        if (d.length() < 7) return null;
        return d.length() > 9 ? d.substring(d.length() - 9) : d; // 050..., +99450..., 99450... are the same number
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(" (₼)", "").replace("(₼)", "").trim();
    }

    private static String text(Row row, Map<String, Integer> col, String header, DataFormatter fmt) {
        Integer i = col.get(norm(header));
        if (i == null) return null;
        Cell c = row.getCell(i);
        if (c == null) return null;
        String v = c.getCellType() == CellType.NUMERIC && "Telefon".equals(header)
                ? BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString()
                : fmt.formatCellValue(c);
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal number(Row row, Map<String, Integer> col, String header, DataFormatter fmt, List<String> problems) {
        Integer i = col.get(norm(header));
        Cell c = i == null ? null : row.getCell(i);
        BigDecimal v = null;
        if (c != null && c.getCellType() == CellType.NUMERIC) {
            v = BigDecimal.valueOf(c.getNumericCellValue());
        } else if (c != null) {
            String s = fmt.formatCellValue(c).trim().replace("₼", "").replace(" ", "").replace(',', '.');
            if (!s.isEmpty()) {
                try {
                    v = new BigDecimal(s);
                } catch (NumberFormatException e) {
                    problems.add(header + " rəqəm deyil: “" + s + "”");
                    return null;
                }
            }
        }
        if (v == null) return null;
        if (v.signum() < 0) {
            problems.add(header + " mənfi ola bilməz");
            return null;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
