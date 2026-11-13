package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.inventory.InventoryService;
import az.borcdefteri.inventory.InventoryService.Product;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import com.lowagie.text.Document;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.Barcode;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.BarcodeEAN;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Barcode labels for label (thermal) printers as a PDF: one label per page, the page exactly the label size, so a
 * label printer prints it through its normal driver / the phone's print service. Three designs: small 40×25 mm,
 * medium 58×40 mm, large 100×50 mm. A valid EAN-13 is printed as EAN-13, any other barcode (or, without a barcode,
 * the product code) as Code 128.
 */
@Service
public class ProductLabelService {

    private static final float MM = 72f / 25.4f;
    private static final int MAX_LABELS = 3000;

    public enum Size {
        SMALL(40, 25), MEDIUM(58, 40), LARGE(100, 50);

        final float w;
        final float h;

        Size(float wMm, float hMm) {
            this.w = wMm * MM;
            this.h = hMm * MM;
        }
    }

    private final InventoryService inventory;

    public ProductLabelService(InventoryService inventory) {
        this.inventory = inventory;
    }

    /**
     * @param ids      products to print; empty = every active product
     * @param copies   labels per product (1–500)
     * @param perStock true = as many labels as the product has in stock (at least one)
     */
    public ExportFile labels(List<UUID> ids, String size, int copies, boolean perStock) {
        CurrentMember m = inventory.requireInventory();
        Size s;
        try {
            s = Size.valueOf((size == null ? "medium" : size).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_SIZE", "Ölçü small, medium və ya large ola bilər");
        }
        if (copies < 1 || copies > 500) throw ApiException.badRequest("INVALID_COPIES", "Nüsxə sayı 1–500 olmalıdır");
        List<Product> products = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            products.addAll(inventory.products(null, null, null, true));
        } else {
            for (UUID id : ids) products.add(inventory.load(m.storeId(), id));
        }
        if (products.isEmpty()) throw ApiException.badRequest("NO_PRODUCTS", "Çap üçün məhsul yoxdur");
        long total = 0;
        for (Product p : products) total += count(p, copies, perStock);
        if (total > MAX_LABELS) {
            throw ApiException.badRequest("TOO_MANY_LABELS", "Bir dəfəyə ən çox " + MAX_LABELS + " etiket çap etmək olar (" + total + " seçilib)");
        }
        String name = products.size() == 1 ? "barkod-" + products.get(0).code() : "barkodlar";
        return new ExportFile(pdf(m.storeName(), products, s, copies, perStock), "application/pdf",
                name + "-" + s.name().toLowerCase(Locale.ROOT) + ".pdf");
    }

    private static int count(Product p, int copies, boolean perStock) {
        if (!perStock) return copies;
        return Math.max(1, p.stockQty().setScale(0, RoundingMode.CEILING).intValue());
    }

    private byte[] pdf(String store, List<Product> products, Size s, int copies, boolean perStock) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(new Rectangle(s.w, s.h), 0, 0, 0, 0);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont regular = ReportService.baseFont(false);
            BaseFont bold = ReportService.baseFont(true);
            String currency = bold.charExists(0x20BC) ? " ₼" : " AZN";
            PdfContentByte cb = writer.getDirectContent();
            boolean first = true;
            for (Product p : products) {
                for (int i = 0, n = count(p, copies, perStock); i < n; i++) {
                    if (!first) doc.newPage();
                    first = false;
                    draw(cb, s, store, p, regular, bold, currency);
                }
            }
            doc.close();
            return out.toByteArray();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Etiket PDF-i yaradıla bilmədi");
        }
    }

    private void draw(PdfContentByte cb, Size s, String store, Product p, BaseFont regular, BaseFont bold, String currency) {
        float pad = (s == Size.SMALL ? 1.5f : 2.5f) * MM;
        float w = s.w - 2 * pad;
        float top = s.h - pad;
        String price = Money.of(p.salePrice()).toPlainString().replace('.', ',') + currency;

        switch (s) {
            case SMALL -> {
                top = line(cb, bold, 7, fit(bold, 7, p.name(), w), pad, top, w, false);
                float priceSize = 9;
                float bottom = pad;
                text(cb, bold, priceSize, price, pad + w, bottom, PdfContentByte.ALIGN_RIGHT);
                barcode(cb, p, pad, bottom + priceSize + 1.2f * MM, w, top - (bottom + priceSize + 1.2f * MM) - 0.8f * MM, 6);
            }
            case MEDIUM -> {
                top = line(cb, regular, 6, fit(regular, 6, store, w), pad, top, w, true);
                for (String l : wrap(bold, 9, p.name(), w, 2)) top = line(cb, bold, 9, l, pad, top, w, false);
                float priceSize = 15;
                float bottom = pad;
                text(cb, bold, priceSize, price, pad + w / 2, bottom, PdfContentByte.ALIGN_CENTER);
                barcode(cb, p, pad, bottom + priceSize + 1.5f * MM, w, top - (bottom + priceSize + 1.5f * MM) - 1f * MM, 7);
            }
            case LARGE -> {
                top = line(cb, regular, 8, fit(regular, 8, store, w), pad, top, w, true);
                for (String l : wrap(bold, 12, p.name(), w, 2)) top = line(cb, bold, 12, l, pad, top, w, false);
                top = line(cb, regular, 7.5f, p.code() + " · " + p.unit(), pad, top, w, false);
                // price on the right, the barcode takes the rest of the width
                float priceSize = 24;
                while (priceSize > 12 && bold.getWidthPoint(price, priceSize) > w * 0.42f) priceSize -= 1;
                float priceW = bold.getWidthPoint(price, priceSize);
                float barW = w - priceW - 4 * MM;
                float barBottom = pad;
                barcode(cb, p, pad, barBottom, barW, top - barBottom - 1.2f * MM, 9);
                text(cb, regular, 7.5f, "Qiymət", s.w - pad, pad + priceSize + 1.5f * MM, PdfContentByte.ALIGN_RIGHT);
                text(cb, bold, priceSize, price, s.w - pad, pad + 1f * MM, PdfContentByte.ALIGN_RIGHT);
            }
        }
    }

    /** Barcode scaled into the box (x, y, w, h). */
    private void barcode(PdfContentByte cb, Product p, float x, float y, float w, float h, float fontSize) {
        String value = p.barcode() != null && !p.barcode().isBlank() ? p.barcode() : p.code();
        Barcode code;
        if (isEan13(value)) {
            code = new BarcodeEAN();
            code.setCodeType(Barcode.EAN13);
        } else {
            code = new Barcode128();
            code.setCodeType(Barcode.CODE128);
        }
        code.setCode(value);
        code.setSize(fontSize);
        code.setBaseline(fontSize);
        code.setX(1f);
        code.setBarHeight(Math.max(10, h - fontSize - 2));
        PdfTemplate t = code.createTemplateWithBarcode(cb, Color.BLACK, Color.BLACK);
        float sx = Math.min(w / t.getWidth(), 3f);
        float sy = h / t.getHeight();
        float drawnW = t.getWidth() * sx;
        cb.addTemplate(t, sx, 0, 0, sy, x + (w - drawnW) / 2, y);
    }

    static boolean isEan13(String s) {
        if (s == null || !s.matches("\\d{13}")) return false;
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (s.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return (10 - sum % 10) % 10 == s.charAt(12) - '0';
    }

    /** Writes one line under [top] and returns the new top. */
    private static float line(PdfContentByte cb, BaseFont f, float size, String text, float x, float top, float w, boolean grey) {
        float baseline = top - size;
        cb.beginText();
        cb.setFontAndSize(f, size);
        cb.setColorFill(grey ? Color.DARK_GRAY : Color.BLACK);
        cb.showTextAligned(PdfContentByte.ALIGN_LEFT, text, x, baseline, 0);
        cb.endText();
        cb.setColorFill(Color.BLACK);
        return baseline - size * 0.3f;
    }

    private static void text(PdfContentByte cb, BaseFont f, float size, String text, float x, float baseline, int align) {
        cb.beginText();
        cb.setFontAndSize(f, size);
        cb.setColorFill(Color.BLACK);
        cb.showTextAligned(align, text, x, baseline, 0);
        cb.endText();
    }

    private static String fit(BaseFont f, float size, String s, float w) {
        if (s == null) return "";
        if (f.getWidthPoint(s, size) <= w) return s;
        String t = s;
        while (!t.isEmpty() && f.getWidthPoint(t + "…", size) > w) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private static List<String> wrap(BaseFont f, float size, String s, float w, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split("\\s+")) {
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (f.getWidthPoint(next, size) <= w || cur.isEmpty()) {
                cur = new StringBuilder(next);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        if (lines.size() > maxLines) {
            List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            cut.set(maxLines - 1, fit(f, size, String.join(" ", lines.subList(maxLines - 1, lines.size())), w));
            return cut;
        }
        for (int i = 0; i < lines.size(); i++) lines.set(i, fit(f, size, lines.get(i), w));
        return lines;
    }
}
