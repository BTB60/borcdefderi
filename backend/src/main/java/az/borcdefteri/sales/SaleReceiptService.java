package az.borcdefteri.sales;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.sales.SalesService.Item;
import az.borcdefteri.sales.SalesService.Sale;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Narrow (80 mm) sale receipt (çek) as PDF, with the store's details and every line. */
@Service
public class SaleReceiptService {

    private final SalesService sales;
    private final JdbcClient jdbc;

    public SaleReceiptService(SalesService sales, JdbcClient jdbc) {
        this.sales = sales;
        this.jdbc = jdbc;
    }

    public ExportFile receipt(UUID saleId) {
        Sale s = sales.get(saleId);
        record Store(String name, String phone, String address) {}
        Store store = jdbc.sql("SELECT st.name, st.phone, st.address FROM stores st JOIN sales s ON s.store_id = st.id WHERE s.id = :id")
                .param("id", saleId).query((rs, n) -> new Store(rs.getString("name"), rs.getString("phone"), rs.getString("address"))).single();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            float height = 360 + s.items().size() * 28f + (s.returnedAmount().signum() > 0 ? 30 : 0);
            Document doc = new Document(new Rectangle(226, height), 12, 12, 12, 12);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = font(false), bold = font(true);
            Font title = new Font(bold, 11), small = new Font(base, 7.5f, Font.NORMAL, Color.DARK_GRAY),
                    cell = new Font(base, 8), cellBold = new Font(bold, 9);
            try {
                Image logo = Image.getInstance(new ClassPathResource("branding/logo.png").getContentAsByteArray());
                logo.scaleToFit(90, 40);
                logo.setAlignment(Element.ALIGN_CENTER);
                doc.add(logo);
            } catch (Exception ignored) {
                // logo is optional
            }
            center(doc, store.name(), title);
            if (store.phone() != null) center(doc, store.phone(), small);
            if (store.address() != null) center(doc, store.address(), small);
            center(doc, "Satış çeki " + s.number(), cellBold);
            center(doc, s.createdAt().atZoneSameInstant(ZoneId.of("Asia/Baku")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    + " · " + s.createdByName(), small);
            if (s.customerName() != null) center(doc, "Müştəri: " + s.customerName() + (s.customerCode() != null ? " (" + s.customerCode() + ")" : ""), small);
            if ("CANCELLED".equals(s.status())) center(doc, "LƏĞV EDİLİB", new Font(bold, 10, Font.NORMAL, Color.RED));
            doc.add(new Paragraph(" ", small));

            PdfPTable t = new PdfPTable(new float[]{3.2f, 1.3f, 1.6f});
            t.setWidthPercentage(100);
            for (Item i : s.items()) {
                add(t, i.name() + "\n" + qty(i.quantity()) + " × " + i.unitPrice().toPlainString()
                        + (i.discount().signum() > 0 ? "  (−" + i.discount().toPlainString() + ")" : ""), cell, Element.ALIGN_LEFT);
                add(t, i.returnedQty().signum() > 0 ? "qayt. " + qty(i.returnedQty()) : "", small, Element.ALIGN_RIGHT);
                add(t, i.lineTotal().toPlainString(), cell, Element.ALIGN_RIGHT);
            }
            doc.add(t);
            doc.add(new Paragraph(" ", small));
            PdfPTable sums = new PdfPTable(new float[]{3f, 2f});
            sums.setWidthPercentage(100);
            line(sums, "Cəmi", s.subtotal(), cell);
            if (s.discount().signum() > 0) line(sums, "Endirim", s.discount().negate(), cell);
            line(sums, "YEKUN", s.total(), cellBold);
            if (s.paidCash().signum() > 0) line(sums, "Nağd", s.paidCash(), cell);
            if (s.paidCard().signum() > 0) line(sums, "Kart", s.paidCard(), cell);
            if (s.creditAmount().signum() > 0) line(sums, "Borca yazıldı", s.creditAmount(), cellBold);
            if (s.returnedAmount().signum() > 0) line(sums, "Qaytarılıb", s.returnedAmount().negate(), cell);
            doc.add(sums);
            doc.add(new Paragraph(" ", small));
            center(doc, "Təşəkkür edirik! · HesabPro", small);
            doc.close();
            return new ExportFile(out.toByteArray(), "application/pdf", "cek-" + s.number() + ".pdf");
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Çek yaradıla bilmədi");
        }
    }

    private static BaseFont font(boolean bold) throws Exception {
        String file = bold ? "fonts/DejaVuSans-Bold.ttf" : "fonts/DejaVuSans.ttf";
        return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                new ClassPathResource(file).getContentAsByteArray(), null);
    }

    private static void center(Document doc, String text, Font f) throws Exception {
        Paragraph p = new Paragraph(text, f);
        p.setAlignment(Element.ALIGN_CENTER);
        doc.add(p);
    }

    private static void add(PdfPTable t, String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(new Color(0xE2, 0xE8, 0xF0));
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        t.addCell(c);
    }

    private static void line(PdfPTable t, String label, BigDecimal v, Font f) {
        PdfPCell a = new PdfPCell(new Phrase(label, f));
        a.setBorder(Rectangle.NO_BORDER);
        PdfPCell b = new PdfPCell(new Phrase(v.toPlainString() + " ₼", f));
        b.setBorder(Rectangle.NO_BORDER);
        b.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(a);
        t.addCell(b);
    }

    private static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }
}
