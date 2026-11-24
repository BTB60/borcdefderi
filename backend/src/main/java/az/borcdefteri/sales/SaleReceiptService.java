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

/**
 * Narrow sale receipt (çek) as PDF, with the store's details and every line, in the store's çek dizaynı
 * (Parametrlər → Çek dizaynı: paper width, logo, header lines, text size, what to show, last lines).
 */
@Service
public class SaleReceiptService {

    private final SalesService sales;
    private final JdbcClient jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public SaleReceiptService(SalesService sales, JdbcClient jdbc, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.sales = sales;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The parts of the store's receipt design the PDF uses; defaults when the store never saved one. */
    record Design(boolean narrow, String header, String footer, byte[] logo, float scale, boolean left, boolean showStoreName,
                  boolean showCashier, boolean showCustomer, boolean boldTotal) {}

    Design design(UUID saleId) {
        String raw = jdbc.sql("SELECT st.print_design -> 'receipt' FROM stores st JOIN sales s ON s.store_id = st.id WHERE s.id = :id")
                .param("id", saleId).query(String.class).optional().orElse(null);
        com.fasterxml.jackson.databind.JsonNode d;
        try {
            d = raw == null ? json.createObjectNode() : json.readTree(raw);
        } catch (Exception e) {
            d = json.createObjectNode();
        }
        byte[] logo = null;
        String l = d.path("logo").asText("");
        int comma = l.indexOf(',');
        if (l.startsWith("data:image/") && comma > 0) {
            try {
                logo = java.util.Base64.getDecoder().decode(l.substring(comma + 1));
            } catch (IllegalArgumentException ignored) {
                // a broken picture: no logo
            }
        }
        float scale = switch (d.path("fontSize").asText("m")) {
            case "s" -> 0.9f;
            case "l" -> 1.12f;
            default -> 1f;
        };
        return new Design("58".equals(d.path("paper").asText("80")), d.path("header").asText("").trim(),
                d.has("footer") ? d.path("footer").asText("").trim() : "Təşəkkür edirik!", logo, scale,
                "left".equals(d.path("align").asText("center")), d.path("showStoreName").asBoolean(true),
                d.path("showCashier").asBoolean(true), d.path("showCustomer").asBoolean(true), d.path("boldTotal").asBoolean(true));
    }

    public ExportFile receipt(UUID saleId) {
        return render(sales.get(saleId));
    }

    /** For the public link sent to the customer (the link's token was checked by the caller). */
    public ExportFile sharedReceipt(UUID saleId) {
        UUID storeId = jdbc.sql("SELECT store_id FROM sales WHERE id = :id").param("id", saleId).query(UUID.class).optional()
                .orElseThrow(() -> az.borcdefteri.common.ApiException.notFound("Çek tapılmadı"));
        return render(sales.load(storeId, saleId));
    }

    private ExportFile render(Sale s) {
        UUID saleId = s.id();
        record Store(String name, String phone, String address) {}
        Store store = jdbc.sql("SELECT st.name, st.phone, st.address FROM stores st JOIN sales s ON s.store_id = st.id WHERE s.id = :id")
                .param("id", saleId).query((rs, n) -> new Store(rs.getString("name"), rs.getString("phone"), rs.getString("address"))).single();
        Design d = design(saleId);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int headerLines = d.header().isEmpty() ? 0 : d.header().split("\\R").length;
            float height = (360 + s.items().size() * 28f + (s.returnedAmount().signum() > 0 ? 30 : 0) + headerLines * 11) * d.scale();
            Document doc = new Document(new Rectangle(d.narrow() ? 164 : 226, height), d.narrow() ? 8 : 12, d.narrow() ? 8 : 12, 12, 12);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = font(false), bold = font(true);
            float k = d.scale() * (d.narrow() ? 0.9f : 1f);
            Font title = new Font(bold, 11 * k), small = new Font(base, 7.5f * k, Font.NORMAL, Color.DARK_GRAY),
                    cell = new Font(base, 8 * k), cellBold = new Font(bold, 9 * k);
            int align = d.left() ? Element.ALIGN_LEFT : Element.ALIGN_CENTER;
            try {
                Image logo = Image.getInstance(d.logo() != null ? d.logo() : new ClassPathResource("branding/logo.png").getContentAsByteArray());
                logo.scaleToFit(d.narrow() ? 70 : 90, 40);
                logo.setAlignment(align);
                doc.add(logo);
            } catch (Exception ignored) {
                // logo is optional
            }
            if (d.showStoreName()) para(doc, store.name(), title, align);
            if (!d.header().isEmpty()) {
                for (String line : d.header().split("\\R")) if (!line.isBlank()) para(doc, line.trim(), small, align);
            } else {
                if (store.phone() != null) para(doc, store.phone(), small, align);
                if (store.address() != null) para(doc, store.address(), small, align);
            }
            center(doc, ("WHOLESALE".equals(s.priceLevel()) ? "Topdan satış çeki " : "Satış çeki ") + s.number(), cellBold);
            center(doc, s.createdAt().atZoneSameInstant(ZoneId.of("Asia/Baku")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    + (d.showCashier() ? " · " + s.createdByName() : ""), small);
            if (d.showCustomer() && s.customerName() != null) center(doc, "Müştəri: " + s.customerName() + (s.customerCode() != null ? " (" + s.customerCode() + ")" : ""), small);
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
            java.math.BigDecimal bonusUsed = s.bonusUsed() == null ? java.math.BigDecimal.ZERO : s.bonusUsed();
            java.math.BigDecimal plainDiscount = s.discount().subtract(bonusUsed);
            if (plainDiscount.signum() > 0) line(sums, "Endirim", plainDiscount.negate(), cell);
            if (bonusUsed.signum() > 0) line(sums, "Bonus kartı ilə", bonusUsed.negate(), cell);
            line(sums, "YEKUN", s.total(), d.boldTotal() ? new Font(bold, 11 * k) : cell);
            if (s.paidCash().signum() > 0) line(sums, "Nağd", s.paidCash(), cell);
            if (s.paidCard().signum() > 0) line(sums, "Kart", s.paidCard(), cell);
            if (s.creditAmount().signum() > 0) line(sums, "Borca yazıldı", s.creditAmount(), cellBold);
            if (s.returnedAmount().signum() > 0) line(sums, "Qaytarılıb", s.returnedAmount().negate(), cell);
            doc.add(sums);
            doc.add(new Paragraph(" ", small));
            for (String line : d.footer().split("\\R")) if (!line.isBlank()) center(doc, line.trim(), small);
            center(doc, "HesabPro", new Font(base, 6 * k, Font.NORMAL, Color.GRAY));
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
        para(doc, text, f, Element.ALIGN_CENTER);
    }

    private static void para(Document doc, String text, Font f, int align) throws Exception {
        Paragraph p = new Paragraph(text, f);
        p.setAlignment(align);
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
