package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.debts.DebtService;
import az.borcdefteri.debts.DebtService.DebtView;
import az.borcdefteri.debts.DebtType;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Excel (XLSX) and PDF exports of the three debt sections. Store-scoped through DebtService. */
@Service
public class ReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String[] HEADERS = {"Tarix", "Son tarix", "Ad", "Telefon", "Təsvir", "Məbləğ", "Ödənilib", "Qalıq", "Status"};
    private static final String HELVETICA_FALLBACK = "Helvetica";

    private final DebtService debts;

    public ReportService(DebtService debts) {
        this.debts = debts;
    }

    public record ExportFile(byte[] content, String contentType, String fileName) {}

    public ExportFile exportDebts(DebtType type, String status, LocalDate from, LocalDate to, String format) {
        List<DebtView> rows = debts.listAll(type, status, from, to);
        String base = "borc-" + type.name().toLowerCase() + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return switch (format == null ? "" : format.toLowerCase()) {
            case "xlsx", "excel" -> new ExportFile(excel(type, rows), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
            case "pdf" -> new ExportFile(pdf(type, rows), "application/pdf", base + ".pdf");
            default -> throw ApiException.badRequest("INVALID_FORMAT", "Format yalnız xlsx və ya pdf ola bilər");
        };
    }

    private byte[] excel(DebtType type, List<DebtView> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(title(type));
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(new byte[]{(byte) 0x10, (byte) 0x1C, (byte) 0x35}, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle moneyBold = wb.createCellStyle();
            moneyBold.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            moneyBold.setFont(bold);

            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                var cell = head.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(header);
                sheet.setColumnWidth(i, i == 4 ? 40 * 256 : 18 * 256);
            }
            int r = 1;
            BigDecimal sumA = BigDecimal.ZERO, sumP = BigDecimal.ZERO, sumR = BigDecimal.ZERO;
            for (DebtView d : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(d.debtDate().format(DATE));
                row.createCell(1).setCellValue(d.dueDate() == null ? "" : d.dueDate().format(DATE));
                row.createCell(2).setCellValue(nz(d.partyName()));
                row.createCell(3).setCellValue(nz(d.partyPhone()));
                row.createCell(4).setCellValue(nz(d.description()));
                setMoney(row.createCell(5), d.amount(), money);
                setMoney(row.createCell(6), d.paidAmount(), money);
                setMoney(row.createCell(7), d.remainingAmount(), money);
                row.createCell(8).setCellValue(statusLabel(d.status()));
                sumA = sumA.add(d.amount());
                sumP = sumP.add(d.paidAmount());
                sumR = sumR.add(d.remainingAmount());
            }
            Row total = sheet.createRow(r);
            total.createCell(4).setCellValue("CƏMİ");
            setMoney(total.createCell(5), sumA, moneyBold);
            setMoney(total.createCell(6), sumP, moneyBold);
            setMoney(total.createCell(7), sumR, moneyBold);
            sheet.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Excel faylı yaradıla bilmədi");
        }
    }

    private byte[] pdf(DebtType type, List<DebtView> rows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 36, 36, 46, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = baseFont(false);
            BaseFont boldBase = baseFont(true);
            Font titleFont = new Font(boldBase, 16, Font.NORMAL, new Color(0x10, 0x1C, 0x35));
            Font small = new Font(base, 9, Font.NORMAL, Color.DARK_GRAY);
            Font cellFont = new Font(base, 9, Font.NORMAL, Color.BLACK);
            Font headFont = new Font(boldBase, 9, Font.NORMAL, Color.WHITE);

            Paragraph title = new Paragraph("BORCDEFTERI — " + title(type), titleFont);
            title.setSpacingAfter(4);
            doc.add(title);
            doc.add(new Paragraph("Hesabat tarixi: " + LocalDate.now().format(DATE) + "   |   Qeyd sayı: " + rows.size(), small));
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(new float[]{1.1f, 1.1f, 2.2f, 1.4f, 3.0f, 1.3f, 1.3f, 1.3f, 1.2f});
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (String h : HEADERS) {
                PdfPCell c = new PdfPCell(new Phrase(h, headFont));
                c.setBackgroundColor(new Color(0x10, 0x1C, 0x35));
                c.setPadding(5);
                table.addCell(c);
            }
            BigDecimal sumA = BigDecimal.ZERO, sumP = BigDecimal.ZERO, sumR = BigDecimal.ZERO;
            for (DebtView d : rows) {
                addCell(table, d.debtDate().format(DATE), cellFont, Element.ALIGN_LEFT);
                addCell(table, d.dueDate() == null ? "" : d.dueDate().format(DATE), cellFont, Element.ALIGN_LEFT);
                addCell(table, nz(d.partyName()), cellFont, Element.ALIGN_LEFT);
                addCell(table, nz(d.partyPhone()), cellFont, Element.ALIGN_LEFT);
                addCell(table, nz(d.description()), cellFont, Element.ALIGN_LEFT);
                addCell(table, d.amount().toPlainString(), cellFont, Element.ALIGN_RIGHT);
                addCell(table, d.paidAmount().toPlainString(), cellFont, Element.ALIGN_RIGHT);
                addCell(table, d.remainingAmount().toPlainString(), cellFont, Element.ALIGN_RIGHT);
                addCell(table, statusLabel(d.status()), cellFont, Element.ALIGN_LEFT);
                sumA = sumA.add(d.amount());
                sumP = sumP.add(d.paidAmount());
                sumR = sumR.add(d.remainingAmount());
            }
            Font totalFont = new Font(boldBase, 9, Font.NORMAL, Color.BLACK);
            PdfPCell label = new PdfPCell(new Phrase("CƏMİ", totalFont));
            label.setColspan(5);
            label.setHorizontalAlignment(Element.ALIGN_RIGHT);
            label.setPadding(5);
            table.addCell(label);
            addCell(table, sumA.toPlainString(), totalFont, Element.ALIGN_RIGHT);
            addCell(table, sumP.toPlainString(), totalFont, Element.ALIGN_RIGHT);
            addCell(table, sumR.toPlainString(), totalFont, Element.ALIGN_RIGHT);
            table.addCell(new PdfPCell(new Phrase("")));
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "PDF faylı yaradıla bilmədi");
        }
    }

    private static BaseFont baseFont(boolean bold) throws Exception {
        String file = bold ? "fonts/DejaVuSans-Bold.ttf" : "fonts/DejaVuSans.ttf";
        byte[] bytes = new ClassPathResource(file).getContentAsByteArray();
        return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
    }

    private static void addCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        table.addCell(c);
    }

    private static void setMoney(org.apache.poi.ss.usermodel.Cell cell, BigDecimal value, CellStyle style) {
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    static String title(DebtType type) {
        return switch (type) {
            case CUSTOMER_DEBT -> "Müştəri borcları";
            case COMPANY_RECEIVABLE -> "Şirkət alacaqları";
            case COMPANY_PAYABLE -> "Şirkət borcları (ödənilməli)";
        };
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "OPEN" -> "Açıq";
            case "PARTIAL" -> "Qismən";
            case "PAID" -> "Ödənilib";
            default -> status;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
