package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.inventory.InventoryService;
import az.borcdefteri.inventory.InventoryService.Product;
import az.borcdefteri.inventory.ProductImportService;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * All products of the store as Excel or PDF. The Excel sheet has exactly the columns the import reads
 * ({@link ProductImportService#HEADERS}), so an exported file can be edited and imported back.
 */
@Service
public class ProductExportService {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Color NAVY = new Color(0x10, 0x1C, 0x35);

    private final InventoryService inventory;
    private final Clock clock;

    public ProductExportService(InventoryService inventory, Clock clock) {
        this.inventory = inventory;
        this.clock = clock;
    }

    public ExportFile export(String format) {
        CurrentMember m = inventory.requireInventory();
        List<Product> products = inventory.products(null, null, null, null);
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(BAKU);
        String base = "mehsullar-" + now.format(FILE_DATE);
        return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "xlsx", "excel" -> new ExportFile(excel(products),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
            case "pdf" -> new ExportFile(pdf(m.storeName(), products, now), "application/pdf", base + ".pdf");
            default -> throw ApiException.badRequest("INVALID_FORMAT", "Format yalnız xlsx və ya pdf ola bilər");
        };
    }

    /** Empty sheet with the import columns and one example row. */
    public ExportFile template() {
        inventory.requireInventory();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = header(wb);
            Row r = sheet.createRow(1);
            Object[] example = {"", "4760000000011", "Şəkər 1 kq", "Ərzaq", "ədəd", 1.60, 2.20, 40, 5, "Bəli"};
            for (int i = 0; i < example.length; i++) {
                if (example[i] instanceof Number n) r.createCell(i).setCellValue(n.doubleValue());
                else r.createCell(i).setCellValue((String) example[i]);
            }
            wb.write(out);
            return new ExportFile(out.toByteArray(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "mehsul-import-sablonu.xlsx");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Excel faylı yaradıla bilmədi");
        }
    }

    private static Sheet header(Workbook wb) {
        Sheet sheet = wb.createSheet("Məhsullar");
        org.apache.poi.ss.usermodel.Font bold = wb.createFont();
        bold.setBold(true);
        CellStyle head = wb.createCellStyle();
        head.setFont(bold);
        Row h = sheet.createRow(0);
        String[] headers = ProductImportService.HEADERS;
        for (int i = 0; i < headers.length; i++) {
            var c = h.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(head);
            sheet.setColumnWidth(i, (i == 2 ? 34 : i == 1 ? 18 : 14) * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private byte[] excel(List<Product> products) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = header(wb);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle qty = wb.createCellStyle();
            qty.setDataFormat(wb.createDataFormat().getFormat("#,##0.###"));
            int r = 1;
            for (Product p : products) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(p.code());
                row.createCell(1).setCellValue(p.barcode() == null ? "" : p.barcode());
                row.createCell(2).setCellValue(p.name());
                row.createCell(3).setCellValue(p.categoryName() == null ? "" : p.categoryName());
                row.createCell(4).setCellValue(p.unit());
                num(row, 5, p.purchasePrice(), money);
                num(row, 6, p.salePrice(), money);
                num(row, 7, p.stockQty(), qty);
                num(row, 8, p.minStock(), qty);
                row.createCell(9).setCellValue(p.active() ? "Bəli" : "Xeyr");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Excel faylı yaradıla bilmədi");
        }
    }

    private byte[] pdf(String storeName, List<Product> products, ZonedDateTime now) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 30, 30, 36, 30);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = ReportService.baseFont(false);
            BaseFont boldBase = ReportService.baseFont(true);
            Font title = new Font(boldBase, 15, Font.NORMAL, NAVY);
            Font small = new Font(base, 9, Font.NORMAL, Color.DARK_GRAY);
            Font cell = new Font(base, 8.5f, Font.NORMAL, Color.BLACK);
            Font head = new Font(boldBase, 8.5f, Font.NORMAL, Color.WHITE);
            Font bold = new Font(boldBase, 9, Font.NORMAL, Color.BLACK);

            doc.add(new Paragraph(storeName + " — Anbar məhsulları", title));
            doc.add(new Paragraph(now.format(DATE_TIME) + "   |   Məhsul sayı: " + products.size(), small));
            doc.add(new Paragraph(" "));

            String[] headers = {"Kod", "Barkod", "Məhsul", "Kateqoriya", "Vahid", "Alış", "Satış", "Qalıq", "Dəyər (alış)"};
            PdfPTable t = new PdfPTable(new float[]{1f, 1.6f, 3.2f, 1.5f, 0.8f, 0.9f, 0.9f, 0.9f, 1.1f});
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            for (String h : headers) {
                PdfPCell c = new PdfPCell(new Phrase(h, head));
                c.setBackgroundColor(NAVY);
                c.setPadding(4);
                t.addCell(c);
            }
            BigDecimal cost = BigDecimal.ZERO;
            BigDecimal sale = BigDecimal.ZERO;
            for (Product p : products) {
                Font f = p.active() ? cell : new Font(base, 8.5f, Font.NORMAL, Color.GRAY);
                add(t, p.code(), f, Element.ALIGN_LEFT);
                add(t, p.barcode() == null ? "" : p.barcode(), f, Element.ALIGN_LEFT);
                add(t, p.name() + (p.active() ? "" : " (deaktiv)"), f, Element.ALIGN_LEFT);
                add(t, p.categoryName() == null ? "" : p.categoryName(), f, Element.ALIGN_LEFT);
                add(t, p.unit(), f, Element.ALIGN_LEFT);
                add(t, Money.of(p.purchasePrice()).toPlainString(), f, Element.ALIGN_RIGHT);
                add(t, Money.of(p.salePrice()).toPlainString(), f, Element.ALIGN_RIGHT);
                add(t, p.stockQty().stripTrailingZeros().toPlainString(), f, Element.ALIGN_RIGHT);
                add(t, Money.of(p.stockCostValue()).toPlainString(), f, Element.ALIGN_RIGHT);
                cost = cost.add(p.stockCostValue());
                sale = sale.add(p.stockSaleValue());
            }
            doc.add(t);

            PdfPTable sum = new PdfPTable(new float[]{2f, 1f});
            sum.setWidthPercentage(35);
            sum.setHorizontalAlignment(Element.ALIGN_RIGHT);
            sum.setSpacingBefore(10);
            add(sum, "Anbar dəyəri (alış)", bold, Element.ALIGN_LEFT);
            add(sum, Money.of(cost).toPlainString() + " ₼", bold, Element.ALIGN_RIGHT);
            add(sum, "Anbar dəyəri (satış)", bold, Element.ALIGN_LEFT);
            add(sum, Money.of(sale).toPlainString() + " ₼", bold, Element.ALIGN_RIGHT);
            doc.add(sum);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "PDF faylı yaradıla bilmədi");
        }
    }

    private static void num(Row row, int col, BigDecimal v, CellStyle style) {
        var c = row.createCell(col);
        c.setCellValue(v == null ? 0 : v.doubleValue());
        c.setCellStyle(style);
    }

    private static void add(PdfPTable t, String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setPadding(3.5f);
        t.addCell(c);
    }
}
