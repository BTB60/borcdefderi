package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.payments.PaymentMethod;
import az.borcdefteri.payments.PaymentService;
import az.borcdefteri.payments.PaymentService.PaymentReceipt;
import az.borcdefteri.payments.PaymentService.PaymentView;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Payment receipts (çek) and the daily / monthly payments report (Excel or PDF). */
@Service
public class PaymentReportService {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Color NAVY = new Color(0x10, 0x1C, 0x35);

    private final PaymentService payments;
    private final TenantService tenant;
    private final Clock clock;

    public PaymentReportService(PaymentService payments, TenantService tenant, Clock clock) {
        this.payments = payments;
        this.tenant = tenant;
        this.clock = clock;
    }

    public static String methodLabel(PaymentMethod m) {
        return switch (m) {
            case CASH -> "Nağd";
            case CARD -> "Kart";
            case TRANSFER -> "Köçürmə";
            case OTHER -> "Digər";
        };
    }

    // ---------------------------------------------------------------- receipt (çek)

    public ExportFile receiptPdf(UUID paymentId) {
        return render(payments.receipt(paymentId));
    }

    /** Receipt opened by the customer from a shared (HMAC-signed) link — no login. */
    public ExportFile sharedReceiptPdf(UUID paymentId) {
        return render(payments.receiptForSharedLink(paymentId));
    }

    private ExportFile render(PaymentReceipt r) {
        String number = r.id().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A5, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = ReportService.baseFont(false);
            BaseFont bold = ReportService.baseFont(true);
            Font title = new Font(bold, 15, Font.NORMAL, NAVY);
            Font small = new Font(base, 8.5f, Font.NORMAL, Color.DARK_GRAY);
            Font label = new Font(base, 9.5f, Font.NORMAL, Color.GRAY);
            Font value = new Font(bold, 9.5f, Font.NORMAL, Color.BLACK);
            Font big = new Font(bold, 18, Font.NORMAL, NAVY);

            Paragraph store = new Paragraph(r.storeName(), title);
            store.setAlignment(Element.ALIGN_CENTER);
            doc.add(store);
            String contact = join(r.storePhone(), r.storeAddress());
            if (!contact.isEmpty()) {
                Paragraph c = new Paragraph(contact, small);
                c.setAlignment(Element.ALIGN_CENTER);
                doc.add(c);
            }
            Paragraph head = new Paragraph("ÖDƏNİŞ ÇEKİ № " + number, new Font(bold, 11, Font.NORMAL, Color.BLACK));
            head.setAlignment(Element.ALIGN_CENTER);
            head.setSpacingBefore(10);
            head.setSpacingAfter(10);
            doc.add(head);

            PdfPTable t = new PdfPTable(new float[]{1.1f, 1.6f});
            t.setWidthPercentage(100);
            row(t, "Tarix", r.paidAt().atZoneSameInstant(BAKU).format(DATE_TIME), label, value);
            row(t, r.debtType().isCustomer() ? "Müştəri" : "Şirkət", nz(r.partyName()), label, value);
            if (r.partyPhone() != null) {
                row(t, "Telefon", r.partyPhone(), label, value);
            }
            row(t, "Bölmə", ReportService.title(r.debtType()), label, value);
            row(t, "Ödəniş üsulu", methodLabel(r.method()), label, value);
            if (r.description() != null) {
                row(t, "Təsvir", r.description(), label, value);
            }
            row(t, "Borc məbləği", money(r.debtAmount()) + " (" + r.debtDate().format(DATE) + ")", label, value);
            row(t, "Qəbul edən", nz(r.recordedByName()), label, value);
            doc.add(t);

            PdfPTable sum = new PdfPTable(new float[]{1.1f, 1.6f});
            sum.setWidthPercentage(100);
            sum.setSpacingBefore(12);
            PdfPCell l = new PdfPCell(new Phrase("ÖDƏNİLDİ", new Font(bold, 11, Font.NORMAL, Color.BLACK)));
            PdfPCell v = new PdfPCell(new Phrase(money(r.amount()), big));
            for (PdfPCell c : new PdfPCell[]{l, v}) {
                c.setBorder(Rectangle.TOP);
                c.setPaddingTop(8);
            }
            v.setHorizontalAlignment(Element.ALIGN_RIGHT);
            sum.addCell(l);
            sum.addCell(v);
            PdfPCell rl = new PdfPCell(new Phrase("Qalıq borc", label));
            PdfPCell rv = new PdfPCell(new Phrase(money(r.balanceAfter()), value));
            rl.setBorder(Rectangle.NO_BORDER);
            rv.setBorder(Rectangle.NO_BORDER);
            rv.setHorizontalAlignment(Element.ALIGN_RIGHT);
            sum.addCell(rl);
            sum.addCell(rv);
            doc.add(sum);

            Paragraph foot = new Paragraph("HesabPro · Bu çek elektron qaydada yaradılıb", small);
            foot.setAlignment(Element.ALIGN_CENTER);
            foot.setSpacingBefore(18);
            doc.add(foot);
            doc.close();
            return new ExportFile(out.toByteArray(), "application/pdf", "cek-" + number + ".pdf");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Çek yaradıla bilmədi");
        }
    }

    // ---------------------------------------------------------------- payments report

    /** Payments in [from, to] (business dates). Default: the current month. Requires the REPORTS permission. */
    public ExportFile paymentsReport(LocalDate from, LocalDate to, String format) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_REPORTS);
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;
        if (end.isBefore(start)) {
            throw ApiException.badRequest("INVALID_RANGE", "Son tarix başlanğıc tarixdən əvvəl ola bilməz");
        }
        List<PaymentView> rows = payments.listForExport(m, start, end);
        Map<PaymentMethod, BigDecimal> totals = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod pm : PaymentMethod.values()) {
            totals.put(pm, BigDecimal.ZERO);
        }
        for (PaymentView p : rows) {
            totals.merge(p.paymentMethod(), p.amount(), BigDecimal::add);
        }
        String base = "odenisler-" + start.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + end.format(DateTimeFormatter.BASIC_ISO_DATE);
        String period = start.format(DATE) + " – " + end.format(DATE);
        return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "xlsx", "excel" -> new ExportFile(paymentsExcel(rows, totals, period),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
            case "pdf" -> new ExportFile(paymentsPdf(m.storeName(), rows, totals, period), "application/pdf", base + ".pdf");
            default -> throw ApiException.badRequest("INVALID_FORMAT", "Format yalnız xlsx və ya pdf ola bilər");
        };
    }

    private static final String[] HEADERS = {"Tarix", "Ad", "Bölmə", "Üsul", "Məbləğ", "Təsvir", "Qəbul edən"};

    private byte[] paymentsExcel(List<PaymentView> rows, Map<PaymentMethod, BigDecimal> totals, String period) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Ödənişlər");
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle moneyBold = wb.createCellStyle();
            moneyBold.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            moneyBold.setFont(bold);

            sheet.createRow(0).createCell(0).setCellValue("Ödənişlər hesabatı: " + period);
            Row head = sheet.createRow(2);
            for (int i = 0; i < HEADERS.length; i++) {
                var c = head.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(header);
                sheet.setColumnWidth(i, (i == 1 || i == 5 ? 30 : 16) * 256);
            }
            int r = 3;
            for (PaymentView p : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(p.paidAt().atZoneSameInstant(BAKU).format(DATE_TIME));
                row.createCell(1).setCellValue(nz(p.partyName()));
                row.createCell(2).setCellValue(ReportService.title(p.debtType()));
                row.createCell(3).setCellValue(methodLabel(p.paymentMethod()));
                var amount = row.createCell(4);
                amount.setCellValue(p.amount().doubleValue());
                amount.setCellStyle(money);
                row.createCell(5).setCellValue(nz(p.description()));
                row.createCell(6).setCellValue(nz(p.recordedByName()));
            }
            r++;
            BigDecimal grand = BigDecimal.ZERO;
            for (Map.Entry<PaymentMethod, BigDecimal> e : totals.entrySet()) {
                Row row = sheet.createRow(r++);
                row.createCell(3).setCellValue(methodLabel(e.getKey()));
                var c = row.createCell(4);
                c.setCellValue(e.getValue().doubleValue());
                c.setCellStyle(money);
                grand = grand.add(e.getValue());
            }
            Row total = sheet.createRow(r);
            total.createCell(3).setCellValue("CƏMİ");
            var c = total.createCell(4);
            c.setCellValue(grand.doubleValue());
            c.setCellStyle(moneyBold);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Excel faylı yaradıla bilmədi");
        }
    }

    private byte[] paymentsPdf(String storeName, List<PaymentView> rows, Map<PaymentMethod, BigDecimal> totals, String period) {
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

            doc.add(new Paragraph(storeName + " — Ödənişlər hesabatı", title));
            doc.add(new Paragraph("Dövr: " + period + "   |   Ödəniş sayı: " + rows.size(), small));
            doc.add(new Paragraph(" "));

            PdfPTable t = new PdfPTable(new float[]{1.3f, 2.2f, 1.6f, 1f, 1.1f, 2.4f, 1.6f});
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            for (String h : HEADERS) {
                PdfPCell c = new PdfPCell(new Phrase(h, head));
                c.setBackgroundColor(NAVY);
                c.setPadding(5);
                t.addCell(c);
            }
            for (PaymentView p : rows) {
                add(t, p.paidAt().atZoneSameInstant(BAKU).format(DATE_TIME), cell, Element.ALIGN_LEFT);
                add(t, nz(p.partyName()), cell, Element.ALIGN_LEFT);
                add(t, ReportService.title(p.debtType()), cell, Element.ALIGN_LEFT);
                add(t, methodLabel(p.paymentMethod()), cell, Element.ALIGN_LEFT);
                add(t, p.amount().toPlainString(), cell, Element.ALIGN_RIGHT);
                add(t, nz(p.description()), cell, Element.ALIGN_LEFT);
                add(t, nz(p.recordedByName()), cell, Element.ALIGN_LEFT);
            }
            doc.add(t);

            PdfPTable sum = new PdfPTable(new float[]{2f, 1f});
            sum.setWidthPercentage(35);
            sum.setHorizontalAlignment(Element.ALIGN_RIGHT);
            sum.setSpacingBefore(12);
            BigDecimal grand = BigDecimal.ZERO;
            for (Map.Entry<PaymentMethod, BigDecimal> e : totals.entrySet()) {
                add(sum, methodLabel(e.getKey()), cell, Element.ALIGN_LEFT);
                add(sum, Money.of(e.getValue()).toPlainString(), cell, Element.ALIGN_RIGHT);
                grand = grand.add(e.getValue());
            }
            add(sum, "CƏMİ", bold, Element.ALIGN_LEFT);
            add(sum, Money.of(grand).toPlainString(), bold, Element.ALIGN_RIGHT);
            doc.add(sum);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "PDF faylı yaradıla bilmədi");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void row(PdfPTable t, String l, String v, Font lf, Font vf) {
        PdfPCell a = new PdfPCell(new Phrase(l, lf));
        PdfPCell b = new PdfPCell(new Phrase(v, vf));
        for (PdfPCell c : new PdfPCell[]{a, b}) {
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(new Color(0xE2, 0xE8, 0xF0));
            c.setPadding(5);
        }
        b.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(a);
        t.addCell(b);
    }

    private static void add(PdfPTable t, String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        t.addCell(c);
    }

    private static String money(BigDecimal v) {
        return Money.of(v).toPlainString() + " AZN";
    }

    private static String join(String a, String b) {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{a, b}) {
            if (s != null && !s.isBlank()) {
                sb.append(sb.length() > 0 ? " · " : "").append(s);
            }
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
