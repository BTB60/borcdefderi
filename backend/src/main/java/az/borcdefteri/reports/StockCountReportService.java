package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.inventory.InventoryService;
import az.borcdefteri.inventory.StockCountService;
import az.borcdefteri.inventory.StockCountService.CountDetail;
import az.borcdefteri.inventory.StockCountService.CountSummary;
import az.borcdefteri.inventory.StockCountService.Line;
import az.borcdefteri.inventory.StockCountService.Totals;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The stock-count report (sayım hesabatı) of one count as Excel or PDF: counted lines with their +/− differences. */
@Service
public class StockCountReportService {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Color NAVY = new Color(0x10, 0x1C, 0x35);
    private static final Color GREEN = new Color(0x15, 0x80, 0x3D);
    private static final Color RED = new Color(0xB9, 0x1C, 0x1C);
    private static final String[] HEADERS = {"Kod", "Məhsul", "Vahid", "Sistem", "Sayılan", "Fərq", "Alış qiyməti", "Fərq (₼)", "Sayan"};

    private final StockCountService counts;
    private final InventoryService inventory;

    public StockCountReportService(StockCountService counts, InventoryService inventory) {
        this.counts = counts;
        this.inventory = inventory;
    }

    public ExportFile export(UUID countId, String format) {
        CurrentMember m = inventory.requireInventory();
        CountDetail d = counts.detail(countId);
        List<Line> lines = d.lines().stream().filter(l -> l.countedQty() != null).toList();
        String base = "sayim-" + d.count().number();
        return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "xlsx", "excel" -> new ExportFile(excel(d.count(), lines),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
            case "pdf" -> new ExportFile(pdf(m.storeName(), d.count(), lines), "application/pdf", base + ".pdf");
            default -> throw ApiException.badRequest("INVALID_FORMAT", "Format yalnız xlsx və ya pdf ola bilər");
        };
    }

    private static String[][] info(CountSummary c) {
        return new String[][]{
                {"Sayım", c.number() + " — " + ("COMPLETED".equals(c.status()) ? "Tamamlanıb" : "Davam edir")},
                {"Əhatə", c.categoryName() == null ? "Bütün məhsullar" : c.categoryName()},
                {"Başladı", dt(c.createdAt()) + " — " + c.createdByName()},
                {"Tamamladı", c.completedAt() == null ? "—" : dt(c.completedAt()) + " — " + c.completedByName()},
                {"Qeyd", c.note() == null ? "—" : c.note()},
        };
    }

    private static String[][] totals(Totals t) {
        return new String[][]{
                {"Sayılan məhsul", t.countedItems() + " / " + t.totalItems()},
                {"Uyğun", String.valueOf(t.matchedItems())},
                {"Artıq (+)", t.surplusItems() + " məhsul, " + Money.of(t.surplusValue()).toPlainString() + " ₼"},
                {"Kəsir (−)", t.shortageItems() + " məhsul, " + Money.of(t.shortageValue()).toPlainString() + " ₼"},
                {"Xalis fərq", signed(t.netValue()) + " ₼"},
        };
    }

    private byte[] excel(CountSummary c, List<Line> lines) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sayım " + c.number());
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            CellStyle qty = wb.createCellStyle();
            qty.setDataFormat(wb.createDataFormat().getFormat("#,##0.###"));
            CellStyle diffQty = wb.createCellStyle();
            diffQty.setDataFormat(wb.createDataFormat().getFormat("+#,##0.###;-#,##0.###;0"));
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle diffMoney = wb.createCellStyle();
            diffMoney.setDataFormat(wb.createDataFormat().getFormat("+#,##0.00;-#,##0.00;0.00"));

            int r = 0;
            Row titleRow = sheet.createRow(r++);
            titleRow.createCell(0).setCellValue("Sayım hesabatı " + c.number());
            titleRow.getCell(0).setCellStyle(header);
            for (String[] i : info(c)) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(i[0]);
                row.createCell(1).setCellValue(i[1]);
            }
            r++;
            Row head = sheet.createRow(r++);
            for (int i = 0; i < HEADERS.length; i++) {
                var cell = head.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(header);
                sheet.setColumnWidth(i, (i == 1 ? 34 : i == 8 ? 20 : 13) * 256);
            }
            for (Line l : lines) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(l.code());
                row.createCell(1).setCellValue(l.name());
                row.createCell(2).setCellValue(l.unit());
                num(row, 3, l.systemQty(), qty);
                num(row, 4, l.countedQty(), qty);
                num(row, 5, l.difference(), diffQty);
                num(row, 6, l.unitCost(), money);
                num(row, 7, l.differenceValue(), diffMoney);
                row.createCell(8).setCellValue(l.countedByName() == null ? "" : l.countedByName());
            }
            r++;
            for (String[] t : totals(c.totals())) {
                Row row = sheet.createRow(r++);
                var a = row.createCell(1);
                a.setCellValue(t[0]);
                a.setCellStyle(header);
                row.createCell(2).setCellValue(t[1]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Excel faylı yaradıla bilmədi");
        }
    }

    private byte[] pdf(String storeName, CountSummary c, List<Line> lines) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 36, 36, 40, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = ReportService.baseFont(false);
            BaseFont boldBase = ReportService.baseFont(true);
            Font title = new Font(boldBase, 15, Font.NORMAL, NAVY);
            Font small = new Font(base, 9, Font.NORMAL, Color.DARK_GRAY);
            Font cell = new Font(base, 9, Font.NORMAL, Color.BLACK);
            Font head = new Font(boldBase, 9, Font.NORMAL, Color.WHITE);
            Font bold = new Font(boldBase, 9, Font.NORMAL, Color.BLACK);
            Font plus = new Font(boldBase, 9, Font.NORMAL, GREEN);
            Font minus = new Font(boldBase, 9, Font.NORMAL, RED);

            doc.add(new Paragraph(storeName + " — Sayım hesabatı " + c.number(), title));
            for (String[] i : info(c)) doc.add(new Paragraph(i[0] + ": " + i[1], small));
            doc.add(new Paragraph(" "));

            PdfPTable t = new PdfPTable(new float[]{1.1f, 3.2f, 0.8f, 1f, 1f, 1f, 1.1f, 1.2f, 1.8f});
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            for (String h : HEADERS) {
                PdfPCell hc = new PdfPCell(new Phrase(h, head));
                hc.setBackgroundColor(NAVY);
                hc.setPadding(5);
                t.addCell(hc);
            }
            for (Line l : lines) {
                Font df = l.difference().signum() > 0 ? plus : l.difference().signum() < 0 ? minus : cell;
                add(t, l.code(), cell, Element.ALIGN_LEFT);
                add(t, l.name(), cell, Element.ALIGN_LEFT);
                add(t, l.unit(), cell, Element.ALIGN_LEFT);
                add(t, q(l.systemQty()), cell, Element.ALIGN_RIGHT);
                add(t, q(l.countedQty()), cell, Element.ALIGN_RIGHT);
                add(t, signedQty(l.difference()), df, Element.ALIGN_RIGHT);
                add(t, Money.of(l.unitCost()).toPlainString(), cell, Element.ALIGN_RIGHT);
                add(t, signed(l.differenceValue()), df, Element.ALIGN_RIGHT);
                add(t, l.countedByName() == null ? "" : l.countedByName(), cell, Element.ALIGN_LEFT);
            }
            doc.add(t);

            PdfPTable sum = new PdfPTable(new float[]{1.3f, 2f});
            sum.setWidthPercentage(45);
            sum.setHorizontalAlignment(Element.ALIGN_RIGHT);
            sum.setSpacingBefore(12);
            for (String[] row : totals(c.totals())) {
                add(sum, row[0], bold, Element.ALIGN_LEFT);
                add(sum, row[1], cell, Element.ALIGN_RIGHT);
            }
            doc.add(sum);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "PDF faylı yaradıla bilmədi");
        }
    }

    private static void num(Row row, int col, BigDecimal v, CellStyle style) {
        var c = row.createCell(col);
        if (v != null) c.setCellValue(v.doubleValue());
        c.setCellStyle(style);
    }

    private static void add(PdfPTable t, String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        t.addCell(c);
    }

    private static String dt(OffsetDateTime t) {
        return t.atZoneSameInstant(BAKU).format(DATE_TIME);
    }

    private static String q(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String signedQty(BigDecimal v) {
        return (v.signum() > 0 ? "+" : "") + q(v);
    }

    private static String signed(BigDecimal v) {
        BigDecimal m = Money.of(v);
        return (m.signum() > 0 ? "+" : "") + m.toPlainString();
    }
}
