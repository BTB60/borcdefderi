package az.borcdefteri.inventory;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tədarükçünün qiymət siyahısı: the supplier's Excel price list updates the purchase prices of the matching products
 * (by barcode, then code, then exact name). First a preview of what changes, then {@code apply=true} saves it;
 * optionally the sale price moves by the same percentage so the margin stays.
 */
@Service
@RestController
public class PriceListService {

    public record Line(int row, String fileName, UUID productId, String productName, BigDecimal oldPrice, BigDecimal newPrice,
                       BigDecimal oldSalePrice, BigDecimal newSalePrice, String status) {}

    public record Result(boolean applied, int changed, int same, int notFound, List<Line> lines) {}

    private static final int MAX_ROWS = 3000;
    private static final String[] PRICE = {"qiymət", "alış qiyməti", "qiymet", "price", "цена", "alis qiymeti"};
    private static final String[] BARCODE = {"barkod", "barcode", "штрихкод", "ştrixkod"};
    private static final String[] CODE = {"kod", "code", "артикул", "artikul"};
    private static final String[] NAME = {"ad", "məhsul", "malın adı", "name", "наименование", "товар"};

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final AuditService audit;

    public PriceListService(JdbcClient jdbc, InventoryService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
    }

    @PostMapping(value = "/api/products/price-list", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Result upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(defaultValue = "false") boolean apply,
                         @RequestParam(defaultValue = "false") boolean keepMargin) {
        CurrentMember m = inventory.requireInventory();
        record Product(UUID id, String name, String code, String barcode, BigDecimal buy, BigDecimal sell) {}
        List<Product> products = jdbc.sql("""
                SELECT id, name, code, barcode, purchase_price, sale_price FROM products WHERE store_id = :s AND active
                """).param("s", m.storeId())
                .query((rs, n) -> new Product(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("code"),
                        rs.getString("barcode"), rs.getBigDecimal("purchase_price"), rs.getBigDecimal("sale_price"))).list();
        Map<String, Product> byBarcode = new HashMap<>(), byCode = new HashMap<>(), byName = new HashMap<>();
        for (Product p : products) {
            if (p.barcode() != null) byBarcode.put(p.barcode().trim(), p);
            if (p.code() != null) byCode.put(p.code().trim().toLowerCase(Locale.ROOT), p);
            byName.putIfAbsent(p.name().trim().toLowerCase(Locale.ROOT), p);
        }

        List<Line> lines = new ArrayList<>();
        int changed = 0, same = 0, notFound = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            // the header is the first of the top 10 rows that has a price column
            Map<String, Integer> col = null;
            int headerRow = -1;
            for (int i = sheet.getFirstRowNum(); i <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 10) && col == null; i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                Map<String, Integer> c = new HashMap<>();
                for (Cell cell : r) c.put(fmt.formatCellValue(cell).trim().toLowerCase(Locale.ROOT).replace("(₼)", "").replace("(azn)", "").trim(), cell.getColumnIndex());
                if (find(c, PRICE) != null && (find(c, BARCODE) != null || find(c, CODE) != null || find(c, NAME) != null)) {
                    col = c;
                    headerRow = i;
                }
            }
            if (col == null) {
                throw ApiException.badRequest("NO_HEADER", "Faylda “Qiymət” və “Barkod” / “Kod” / “Ad” sütunları tapılmadı");
            }
            Integer pc = find(col, PRICE), bc = find(col, BARCODE), cc = find(col, CODE), nc = find(col, NAME);
            for (int i = headerRow + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                if (lines.size() >= MAX_ROWS) throw ApiException.badRequest("TOO_MANY_ROWS", "Bir faylda ən çox " + MAX_ROWS + " sətir ola bilər");
                String barcode = text(r, bc, fmt, true), code = text(r, cc, fmt, true), name = text(r, nc, fmt, false);
                BigDecimal price = number(r, pc, fmt);
                if (price == null || (barcode == null && code == null && name == null)) continue;
                Product p = barcode != null ? byBarcode.get(barcode) : null;
                if (p == null && code != null) p = byCode.get(code.toLowerCase(Locale.ROOT));
                if (p == null && name != null) p = byName.get(name.toLowerCase(Locale.ROOT));
                String label = name != null ? name : barcode != null ? barcode : code;
                if (p == null) {
                    notFound++;
                    lines.add(new Line(i + 1, label, null, null, null, price, null, null, "NOT_FOUND"));
                    continue;
                }
                BigDecimal newSale = p.sell();
                if (keepMargin && p.buy().signum() > 0 && price.compareTo(p.buy()) != 0) {
                    newSale = p.sell().multiply(price).divide(p.buy(), 2, RoundingMode.HALF_UP);
                }
                boolean diff = price.compareTo(p.buy()) != 0;
                if (diff) changed++; else same++;
                lines.add(new Line(i + 1, label, p.id(), p.name(), Money.of(p.buy()), price, Money.of(p.sell()), Money.of(newSale),
                        diff ? "CHANGED" : "SAME"));
                if (apply && diff) {
                    jdbc.sql("UPDATE products SET purchase_price = :pp, sale_price = :sp, updated_at = now(), version = version + 1 WHERE id = :id")
                            .param("pp", price).param("sp", newSale).param("id", p.id()).update();
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_FILE", "Fayl oxuna bilmədi. Excel (.xlsx) faylı seçin");
        }
        if (apply && changed > 0) {
            audit.log(m.storeId(), m.userId(), "PRICE_LIST_APPLIED", "PRODUCT", "price-list",
                    changed + " məhsulun alış qiyməti yeniləndi" + (keepMargin ? " (satış qiyməti də eyni faizlə)" : ""));
        }
        return new Result(apply, changed, same, notFound, lines);
    }

    private static Integer find(Map<String, Integer> col, String[] names) {
        for (String n : names) {
            if (col.containsKey(n)) return col.get(n);
        }
        return null;
    }

    private static String text(Row r, Integer i, DataFormatter fmt, boolean digits) {
        if (i == null) return null;
        Cell c = r.getCell(i);
        if (c == null) return null;
        String v = digits && c.getCellType() == CellType.NUMERIC
                ? BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString()
                : fmt.formatCellValue(c);
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal number(Row r, Integer i, DataFormatter fmt) {
        Cell c = r.getCell(i);
        if (c == null) return null;
        try {
            BigDecimal v = c.getCellType() == CellType.NUMERIC ? BigDecimal.valueOf(c.getNumericCellValue())
                    : new BigDecimal(fmt.formatCellValue(c).trim().replace("₼", "").replace("AZN", "").replace(" ", "").replace(',', '.'));
            return v.signum() < 0 ? null : v.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
