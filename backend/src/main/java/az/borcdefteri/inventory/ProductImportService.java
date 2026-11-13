package az.borcdefteri.inventory;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.inventory.InventoryService.Category;
import az.borcdefteri.inventory.InventoryService.Product;
import az.borcdefteri.inventory.InventoryService.ProductRequest;
import az.borcdefteri.tenant.CurrentMember;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Imports products from an Excel sheet (the same columns as the product export). A row whose Kod (or, without a
 * Kod, Barkod) matches an existing product updates that product; any other row creates a new product, its Qalıq
 * booked as opening stock. The Qalıq of existing products is not changed by an import — stock changes go through
 * stock movements or a count (sayım). The whole file is checked first; when any row is wrong nothing is saved.
 */
@Service
public class ProductImportService {

    public static final String[] HEADERS = {"Kod", "Barkod", "Ad", "Kateqoriya", "Vahid", "Alış qiyməti", "Satış qiyməti",
            "Qalıq", "Minimum stok", "Aktiv"};
    private static final int MAX_ROWS = 3000;

    public record RowError(int row, String message) {}

    public record Result(boolean applied, int created, int updated, int unchanged, List<RowError> errors) {}

    private record Parsed(int row, String code, String barcode, String name, String category, String unit,
                          BigDecimal buy, BigDecimal sell, BigDecimal qty, BigDecimal min, Boolean active) {}

    private final InventoryService inventory;
    private final AuditService audit;

    public ProductImportService(InventoryService inventory, AuditService audit) {
        this.inventory = inventory;
        this.audit = audit;
    }

    @Transactional
    public Result importExcel(MultipartFile file) {
        CurrentMember m = inventory.requireInventory();
        if (file == null || file.isEmpty()) throw ApiException.badRequest("EMPTY_FILE", "Excel faylı seçin");
        List<RowError> errors = new ArrayList<>();
        List<Parsed> rows = read(file, errors);
        if (rows.isEmpty() && errors.isEmpty()) {
            errors.add(new RowError(0, "Faylda məhsul sətri tapılmadı"));
        }

        // match against existing products, catch duplicates inside the file
        List<Product> existing = inventory.products(null, null, null, null);
        Map<String, Product> byCode = new HashMap<>();
        Map<String, Product> byBarcode = new HashMap<>();
        for (Product p : existing) {
            byCode.put(p.code().toLowerCase(Locale.ROOT), p);
            if (p.barcode() != null) byBarcode.put(p.barcode(), p);
        }
        Set<String> seenCodes = new HashSet<>();
        Set<String> seenBarcodes = new HashSet<>();
        Map<Integer, Product> matched = new HashMap<>();
        for (Parsed r : rows) {
            if (r.code() != null && !seenCodes.add(r.code().toLowerCase(Locale.ROOT))) {
                errors.add(new RowError(r.row(), "Kod “" + r.code() + "” faylda təkrarlanır"));
            }
            if (r.barcode() != null && !seenBarcodes.add(r.barcode())) {
                errors.add(new RowError(r.row(), "Barkod “" + r.barcode() + "” faylda təkrarlanır"));
            }
            Product p = r.code() != null ? byCode.get(r.code().toLowerCase(Locale.ROOT))
                    : r.barcode() != null ? byBarcode.get(r.barcode()) : null;
            if (p != null) matched.put(r.row(), p);
            Product other = r.barcode() == null ? null : byBarcode.get(r.barcode());
            if (other != null && (p == null || !other.id().equals(p.id())) && r.code() != null) {
                errors.add(new RowError(r.row(), "Barkod “" + r.barcode() + "” artıq “" + other.name() + "” məhsulundadır"));
            }
        }
        if (!errors.isEmpty()) {
            errors.sort((a, b) -> Integer.compare(a.row(), b.row()));
            return new Result(false, 0, 0, 0, errors);
        }

        Map<String, UUID> categories = new HashMap<>();
        for (Category c : inventory.categories()) categories.put(c.name().toLowerCase(Locale.ROOT), c.id());

        int created = 0, updated = 0, unchanged = 0;
        for (Parsed r : rows) {
            UUID categoryId = null;
            if (r.category() != null) {
                categoryId = categories.get(r.category().toLowerCase(Locale.ROOT));
                if (categoryId == null) {
                    categoryId = inventory.createCategory(r.category()).id();
                    categories.put(r.category().toLowerCase(Locale.ROOT), categoryId);
                }
            }
            Product p = matched.get(r.row());
            try {
                if (p == null) {
                    inventory.create(new ProductRequest(r.name(), r.code(), r.barcode(), categoryId, null, r.unit(), r.buy(),
                            r.sell(), r.min(), r.qty(), r.active() == null || r.active(), null, null));
                    created++;
                } else {
                    String unit = r.unit() == null ? p.unit() : r.unit();
                    BigDecimal min = r.min() == null ? p.minStock() : r.min();
                    boolean active = r.active() == null ? p.active() : r.active();
                    UUID cat = r.category() == null ? p.categoryId() : categoryId;
                    String barcode = r.barcode() == null ? p.barcode() : r.barcode();
                    boolean same = p.name().equals(r.name()) && Objects.equals(p.barcode(), barcode) && Objects.equals(p.categoryId(), cat)
                            && p.unit().equals(unit) && p.purchasePrice().compareTo(r.buy()) == 0 && p.salePrice().compareTo(r.sell()) == 0
                            && p.minStock().compareTo(min) == 0 && p.active() == active;
                    if (same) {
                        unchanged++;
                        continue;
                    }
                    inventory.update(p.id(), new ProductRequest(r.name(), p.code(), barcode, cat, p.supplierId(), unit, r.buy(),
                            r.sell(), min, null, active, p.version(), null));
                    updated++;
                }
            } catch (ApiException e) {
                throw ApiException.badRequest("IMPORT_FAILED", "Sətir " + r.row() + ": " + e.getMessage());
            }
        }
        audit.log(m.storeId(), m.userId(), "PRODUCTS_IMPORTED", "PRODUCT", "excel",
                "yeni " + created + ", yenilənən " + updated + ", dəyişməyən " + unchanged);
        return new Result(true, created, updated, unchanged, List.of());
    }

    private List<Parsed> read(MultipartFile file, List<RowError> errors) {
        List<Parsed> out = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) return out;
            Map<String, Integer> col = new HashMap<>();
            for (Cell c : header) col.put(norm(fmt.formatCellValue(c)), c.getColumnIndex());
            for (String required : new String[]{"Ad", "Alış qiyməti", "Satış qiyməti"}) {
                if (!col.containsKey(norm(required))) {
                    errors.add(new RowError(1, "“" + required + "” sütunu tapılmadı. Sütunlar: " + String.join(", ", HEADERS)));
                }
            }
            if (!errors.isEmpty()) return out;
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                int line = i + 1;
                String name = text(row, col, "Ad", fmt);
                String code = text(row, col, "Kod", fmt);
                String barcode = text(row, col, "Barkod", fmt);
                if (name == null && code == null && barcode == null && text(row, col, "Alış qiyməti", fmt) == null) continue;
                if (out.size() >= MAX_ROWS) {
                    errors.add(new RowError(line, "Bir faylda ən çox " + MAX_ROWS + " məhsul ola bilər"));
                    break;
                }
                List<String> problems = new ArrayList<>();
                if (name == null) problems.add("Ad boşdur");
                else if (name.length() > 200) problems.add("Ad 200 simvoldan uzundur");
                if (code != null && code.length() > 30) problems.add("Kod 30 simvoldan uzundur");
                if (barcode != null && !barcode.matches("[0-9A-Za-z-]{1,64}")) problems.add("Barkod yalnız rəqəm/hərf ola bilər");
                BigDecimal buy = number(row, col, "Alış qiyməti", fmt, true, problems, 2);
                BigDecimal sell = number(row, col, "Satış qiyməti", fmt, true, problems, 2);
                BigDecimal qty = number(row, col, "Qalıq", fmt, false, problems, 3);
                BigDecimal min = number(row, col, "Minimum stok", fmt, false, problems, 3);
                String unit = text(row, col, "Vahid", fmt);
                if (unit != null && unit.length() > 20) problems.add("Vahid 20 simvoldan uzundur");
                String category = text(row, col, "Kateqoriya", fmt);
                if (category != null && category.length() > 100) problems.add("Kateqoriya 100 simvoldan uzundur");
                String act = text(row, col, "Aktiv", fmt);
                Boolean active = act == null ? null : !(act.equalsIgnoreCase("xeyr") || act.equalsIgnoreCase("yox")
                        || act.equalsIgnoreCase("no") || act.equals("0") || act.equalsIgnoreCase("false"));
                if (!problems.isEmpty()) {
                    errors.add(new RowError(line, String.join("; ", problems)));
                    continue;
                }
                out.add(new Parsed(line, code, barcode, name, category, unit, buy, sell, qty, min, active));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_FILE", "Fayl oxuna bilmədi. Excel (.xlsx) faylı seçin");
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(" (₼)", "").replace("(₼)", "").trim();
    }

    private static String text(Row row, Map<String, Integer> col, String header, DataFormatter fmt) {
        Integer i = col.get(norm(header));
        if (i == null) return null;
        Cell c = row.getCell(i);
        if (c == null) return null;
        String v = fmt.formatCellValue(c);
        // barcodes typed into Excel become numbers (4.76E+12); keep all digits
        if (c.getCellType() == CellType.NUMERIC && ("Barkod".equals(header) || "Kod".equals(header))) {
            v = BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal number(Row row, Map<String, Integer> col, String header, DataFormatter fmt, boolean required,
                                     List<String> problems, int scale) {
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
        if (v == null) {
            if (required) problems.add(header + " boşdur");
            return null;
        }
        if (v.signum() < 0) {
            problems.add(header + " mənfi ola bilməz");
            return null;
        }
        return v.setScale(scale, RoundingMode.HALF_UP);
    }
}
