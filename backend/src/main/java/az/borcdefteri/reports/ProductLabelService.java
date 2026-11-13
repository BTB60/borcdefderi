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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Barcode labels for label (thermal) printers as a PDF: one label per page, the page exactly the label size, so a
 * label printer prints it through its normal driver / the phone's print service.
 * <p>
 * Presets small 40×25, medium 58×40, large 100×50 mm, or any custom size. The layout is computed from the size:
 * texts on top, price at the bottom (or on the right of a wide label), the barcode in the space between. The
 * barcode and the texts can be scaled, and each part (store, name, code, price, old price) switched on or off.
 * A product with an old price above its sale price gets the old price struck through next to the new one.
 * A valid EAN-13 prints as EAN-13, any other barcode (or the product code when it has none) as Code 128.
 */
@Service
public class ProductLabelService {

    private static final float MM = 72f / 25.4f;
    private static final int MAX_LABELS = 3000;

    /** What the label shows and how big; sizes in millimetres, scales in percent. */
    public record Design(float widthMm, float heightMm, int barcodePct, int fontPct, Set<String> show) {
        boolean has(String part) {
            return show.contains(part);
        }
    }

    private final InventoryService inventory;

    public ProductLabelService(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** Builds the design from request parameters; [size] picks a preset unless it is "custom". */
    public static Design design(String size, Float w, Float h, Integer barcodePct, Integer fontPct, String show) {
        String s = size == null ? "medium" : size.toLowerCase(Locale.ROOT);
        float wMm, hMm;
        String defaults;
        switch (s) {
            case "small" -> { wMm = 40; hMm = 25; defaults = "name,price,old"; }
            case "medium" -> { wMm = 58; hMm = 40; defaults = "store,name,price,old"; }
            case "large" -> { wMm = 100; hMm = 50; defaults = "store,name,code,price,old"; }
            case "custom" -> {
                if (w == null || h == null) throw ApiException.badRequest("INVALID_SIZE", "Öz ölçü üçün en və hündürlük verin");
                wMm = w;
                hMm = h;
                defaults = "store,name,price,old";
            }
            default -> throw ApiException.badRequest("INVALID_SIZE", "Ölçü small, medium, large və ya custom ola bilər");
        }
        if (wMm < 20 || wMm > 150 || hMm < 12 || hMm > 120) {
            throw ApiException.badRequest("INVALID_SIZE", "Etiket ölçüsü: en 20–150 mm, hündürlük 12–120 mm");
        }
        int bar = barcodePct == null ? 100 : barcodePct;
        int font = fontPct == null ? 100 : fontPct;
        if (bar < 30 || bar > 100) throw ApiException.badRequest("INVALID_SCALE", "Barkod ölçüsü 30–100% olmalıdır");
        if (font < 50 || font > 200) throw ApiException.badRequest("INVALID_SCALE", "Yazı ölçüsü 50–200% olmalıdır");
        Set<String> parts = new HashSet<>(Arrays.asList((show == null ? defaults : show).toLowerCase(Locale.ROOT).split(",")));
        parts.removeIf(String::isBlank);
        return new Design(wMm, hMm, bar, font, parts);
    }

    /**
     * @param ids      products to print; empty = every active product
     * @param copies   labels per product (1–500)
     * @param perStock true = as many labels as the product has in stock (at least one)
     */
    public ExportFile labels(List<UUID> ids, Design design, int copies, boolean perStock) {
        CurrentMember m = inventory.requireInventory();
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
        return new ExportFile(pdf(m.storeName(), products, design, copies, perStock), "application/pdf",
                name + "-" + Math.round(design.widthMm()) + "x" + Math.round(design.heightMm()) + ".pdf");
    }

    private static int count(Product p, int copies, boolean perStock) {
        if (!perStock) return copies;
        return Math.max(1, p.stockQty().setScale(0, RoundingMode.CEILING).intValue());
    }

    private byte[] pdf(String store, List<Product> products, Design d, int copies, boolean perStock) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(new Rectangle(d.widthMm() * MM, d.heightMm() * MM), 0, 0, 0, 0);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();
            Fonts f = new Fonts(ReportService.baseFont(false), ReportService.baseFont(true));
            PdfContentByte cb = writer.getDirectContent();
            boolean first = true;
            for (Product p : products) {
                for (int i = 0, n = count(p, copies, perStock); i < n; i++) {
                    if (!first) doc.newPage();
                    first = false;
                    draw(cb, d, store, p, f);
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

    private record Fonts(BaseFont regular, BaseFont bold) {
        String currency() {
            return bold.charExists(0x20BC) ? " ₼" : " AZN";
        }
    }

    private static String money(BigDecimal v, Fonts f) {
        return Money.of(v).toPlainString().replace('.', ',') + f.currency();
    }

    private void draw(PdfContentByte cb, Design d, String store, Product p, Fonts f) {
        float W = d.widthMm() * MM;
        float H = d.heightMm() * MM;
        float pad = Math.max(1.2f, Math.min(4f, Math.min(d.widthMm(), d.heightMm()) * 0.06f)) * MM;
        float w = W - 2 * pad;
        float scale = d.fontPct() / 100f;
        float base = Math.max(6f, Math.min(14f, d.heightMm() * 0.25f)) * scale;   // product name size
        float priceSize = base * 1.7f;
        boolean discount = d.has("old") && p.oldPrice() != null && p.oldPrice().compareTo(p.salePrice()) > 0;
        String price = money(p.salePrice(), f);
        String old = discount ? money(p.oldPrice(), f) : null;
        float oldSize = priceSize * 0.55f;
        boolean wide = d.has("price") && d.widthMm() / d.heightMm() >= 1.8f;

        // ----- top: texts -----
        float top = H - pad;
        if (d.has("store") && store != null) top = line(cb, f.regular(), base * 0.7f, fit(f.regular(), base * 0.7f, store, w), pad, top, true);
        if (d.has("name")) {
            int lines = d.heightMm() >= 35 ? 2 : 1;
            for (String l : wrap(f.bold(), base, p.name(), w, lines)) top = line(cb, f.bold(), base, l, pad, top, false);
        }
        if (d.has("code")) top = line(cb, f.regular(), base * 0.75f, fit(f.regular(), base * 0.75f, p.code() + " · " + p.unit(), w), pad, top, false);

        // ----- price and the barcode box -----
        float barX = pad, barY = pad, barW = w, barH;
        if (d.has("price") && wide) {
            // price column on the right
            while (priceSize > 8 && f.bold().getWidthPoint(price, priceSize) > w * 0.42f) priceSize -= 1;
            oldSize = priceSize * 0.55f;
            float colW = Math.max(f.bold().getWidthPoint(price, priceSize), discount ? f.regular().getWidthPoint(old, oldSize) : 0);
            text(cb, f.bold(), priceSize, price, W - pad, pad + 0.5f * MM, PdfContentByte.ALIGN_RIGHT);
            float above = pad + priceSize + 1.2f * MM;
            if (discount) {
                struck(cb, f.regular(), oldSize, old, W - pad, above, PdfContentByte.ALIGN_RIGHT);
                above += oldSize + 1f * MM;
                text(cb, f.bold(), oldSize * 0.8f, "ENDİRİM", W - pad, above, PdfContentByte.ALIGN_RIGHT);
            } else {
                text(cb, f.regular(), base * 0.7f, "Qiymət", W - pad, above, PdfContentByte.ALIGN_RIGHT);
            }
            barW = w - colW - 3 * MM;
            barH = top - barY - 1f * MM;
        } else if (d.has("price")) {
            // price row at the bottom: old struck on the left, new on the right (or centred when there is no discount)
            while (priceSize > 7 && f.bold().getWidthPoint(price, priceSize)
                    + (discount ? f.regular().getWidthPoint(old, oldSize) + 2 * MM : 0) > w) {
                priceSize -= 1;
                oldSize = priceSize * 0.55f;
            }
            if (discount) {
                struck(cb, f.regular(), oldSize, old, pad, pad + 0.3f * MM, PdfContentByte.ALIGN_LEFT);
                text(cb, f.bold(), priceSize, price, W - pad, pad, PdfContentByte.ALIGN_RIGHT);
            } else {
                text(cb, f.bold(), priceSize, price, W / 2, pad, PdfContentByte.ALIGN_CENTER);
            }
            barY = pad + priceSize + 1.2f * MM;
            barH = top - barY - 0.8f * MM;
        } else {
            barH = top - barY - 0.8f * MM;
        }

        // ----- barcode, scaled down around its centre when asked -----
        float k = d.barcodePct() / 100f;
        float bw = barW * k, bh = barH * k;
        barcode(cb, p, barX + (barW - bw) / 2, barY + (barH - bh) / 2, bw, bh, Math.max(5f, base * 0.8f));
    }

    /** Barcode scaled into the box (x, y, w, h). */
    private void barcode(PdfContentByte cb, Product p, float x, float y, float w, float h, float fontSize) {
        if (w <= 5 || h <= 5) return;
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
        code.setBarHeight(Math.max(8, h - fontSize - 2));
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
    private static float line(PdfContentByte cb, BaseFont f, float size, String text, float x, float top, boolean grey) {
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

    /** Text with a line through it (the price before the discount). */
    private static void struck(PdfContentByte cb, BaseFont f, float size, String s, float x, float baseline, int align) {
        float tw = f.getWidthPoint(s, size);
        float left = align == PdfContentByte.ALIGN_RIGHT ? x - tw : align == PdfContentByte.ALIGN_CENTER ? x - tw / 2 : x;
        cb.beginText();
        cb.setFontAndSize(f, size);
        cb.setColorFill(Color.DARK_GRAY);
        cb.showTextAligned(PdfContentByte.ALIGN_LEFT, s, left, baseline, 0);
        cb.endText();
        cb.setColorFill(Color.BLACK);
        cb.setLineWidth(Math.max(0.6f, size * 0.08f));
        cb.moveTo(left - 0.5f, baseline + size * 0.32f);
        cb.lineTo(left + tw + 0.5f, baseline + size * 0.32f);
        cb.stroke();
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
