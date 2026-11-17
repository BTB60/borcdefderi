package az.borcdefteri.sales;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.FileResponses;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.sales.SalesService.Item;
import az.borcdefteri.sales.SalesService.Sale;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Qaimə-faktura: an A4 invoice of a sale (mostly wholesale to companies) with the store's and the buyer's
 * rekvizitlər, every line, totals and signature places. The store's rekvizitlər (VÖEN, bank) are kept here too.
 */
@Service
@RestController
public class InvoiceService {

    public record Requisites(String taxId, String bankName, String bankAccount, String bankCode, String director) {}

    public record RequisitesUpdate(@Size(max = 20) String taxId, @Size(max = 100) String bankName,
                                   @Size(max = 40) String bankAccount, @Size(max = 20) String bankCode,
                                   @Size(max = 100) String director) {}

    public record CustomerTaxId(@Size(max = 20) String taxId) {}

    private final SalesService sales;
    private final JdbcClient jdbc;
    private final TenantService tenant;

    public InvoiceService(SalesService sales, JdbcClient jdbc, TenantService tenant) {
        this.sales = sales;
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @GetMapping("/api/profile/requisites")
    public Requisites requisites() {
        CurrentMember m = tenant.requireMember();
        return jdbc.sql("SELECT tax_id, bank_name, bank_account, bank_code, director FROM stores WHERE id = :id")
                .param("id", m.storeId())
                .query((rs, n) -> new Requisites(rs.getString("tax_id"), rs.getString("bank_name"), rs.getString("bank_account"),
                        rs.getString("bank_code"), rs.getString("director"))).single();
    }

    @PutMapping("/api/profile/requisites")
    @Transactional
    public Requisites updateRequisites(@Valid @RequestBody RequisitesUpdate in) {
        CurrentMember m = tenant.requireMember();
        if (!m.isOwner()) throw ApiException.forbidden("OWNER_ONLY", "Rekvizitləri yalnız mağaza sahibi dəyişə bilər");
        jdbc.sql("""
                UPDATE stores SET tax_id = :t, bank_name = :bn, bank_account = :ba, bank_code = :bc, director = :d WHERE id = :id
                """).param("t", blank(in.taxId())).param("bn", blank(in.bankName())).param("ba", blank(in.bankAccount()))
                .param("bc", blank(in.bankCode())).param("d", blank(in.director())).param("id", m.storeId()).update();
        return requisites();
    }

    /** The buyer's VÖEN printed on its invoices. */
    @PutMapping("/api/customers/{id}/tax-id")
    @Transactional
    public CustomerTaxId setCustomerTaxId(@PathVariable UUID id, @Valid @RequestBody CustomerTaxId in) {
        CurrentMember m = tenant.requireMember();
        if (!m.has("SALES") && !m.has("CUSTOMER_DEBTS")) {
            throw ApiException.forbidden("FORBIDDEN", "Bu əməliyyat üçün icazəniz yoxdur");
        }
        int n = jdbc.sql("UPDATE customers SET tax_id = :t WHERE id = :id AND store_id = :s")
                .param("t", blank(in.taxId())).param("id", id).param("s", m.storeId()).update();
        if (n == 0) throw ApiException.notFound("Müştəri tapılmadı");
        return new CustomerTaxId(blank(in.taxId()));
    }

    @GetMapping("/api/sales/{id}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable UUID id) {
        ExportFile f = render(sales.get(id));
        return FileResponses.inline(f.content(), f.contentType(), f.fileName());
    }

    private ExportFile render(Sale s) {
        record Party(String name, String phone, String address, String taxId, String bankName, String bankAccount,
                     String bankCode, String director) {}
        Party seller = jdbc.sql("""
                SELECT st.name, st.phone, st.address, st.tax_id, st.bank_name, st.bank_account, st.bank_code, st.director
                FROM stores st JOIN sales s ON s.store_id = st.id WHERE s.id = :id
                """).param("id", s.id())
                .query((rs, n) -> new Party(rs.getString("name"), rs.getString("phone"), rs.getString("address"),
                        rs.getString("tax_id"), rs.getString("bank_name"), rs.getString("bank_account"),
                        rs.getString("bank_code"), rs.getString("director"))).single();
        Party buyer = s.customerId() == null ? null : jdbc.sql("SELECT name, phone, tax_id FROM customers WHERE id = :id")
                .param("id", s.customerId())
                .query((rs, n) -> new Party(rs.getString("name"), rs.getString("phone"), null, rs.getString("tax_id"),
                        null, null, null, null)).optional().orElse(null);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = font(false), bold = font(true);
            Font h1 = new Font(bold, 16), h2 = new Font(bold, 10), text = new Font(base, 9), small = new Font(base, 8, Font.NORMAL, Color.DARK_GRAY),
                    head = new Font(bold, 8.5f, Font.NORMAL, Color.WHITE);

            Paragraph title = new Paragraph("QAİMƏ-FAKTURA № " + s.number(), h1);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);
            Paragraph date = new Paragraph("Tarix: " + s.createdAt().atZoneSameInstant(ZoneId.of("Asia/Baku"))
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), text);
            date.setAlignment(Element.ALIGN_CENTER);
            doc.add(date);
            if ("CANCELLED".equals(s.status())) {
                Paragraph c = new Paragraph("LƏĞV EDİLİB", new Font(bold, 12, Font.NORMAL, Color.RED));
                c.setAlignment(Element.ALIGN_CENTER);
                doc.add(c);
            }
            doc.add(new Paragraph(" ", small));

            PdfPTable parties = new PdfPTable(2);
            parties.setWidthPercentage(100);
            parties.addCell(partyCell("Satıcı", seller.name(), new String[][]{
                    {"VÖEN", seller.taxId()}, {"Ünvan", seller.address()}, {"Telefon", seller.phone()},
                    {"Bank", seller.bankName()}, {"H/h", seller.bankAccount()}, {"Kod", seller.bankCode()}}, h2, text, small));
            parties.addCell(partyCell("Alıcı", buyer != null ? buyer.name() : "Pərakəndə alıcı", buyer == null ? new String[0][] : new String[][]{
                    {"VÖEN", buyer.taxId()}, {"Telefon", buyer.phone()}}, h2, text, small));
            doc.add(parties);
            doc.add(new Paragraph(" ", small));

            PdfPTable t = new PdfPTable(new float[]{0.5f, 4.5f, 1.2f, 1.3f, 1.4f, 1.6f});
            t.setWidthPercentage(100);
            for (String c : new String[]{"№", "Malın adı", "Miqdar", "Qiymət", "Endirim", "Məbləğ"}) {
                PdfPCell cell = new PdfPCell(new Phrase(c, head));
                cell.setBackgroundColor(new Color(0x14, 0x24, 0x45));
                cell.setPadding(5);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                t.addCell(cell);
            }
            int i = 1;
            for (Item it : s.items()) {
                row(t, String.valueOf(i++), text, Element.ALIGN_CENTER);
                row(t, it.name() + (it.returnedQty().signum() > 0 ? "  (qaytarılıb: " + qty(it.returnedQty()) + ")" : ""), text, Element.ALIGN_LEFT);
                row(t, qty(it.quantity()), text, Element.ALIGN_RIGHT);
                row(t, it.unitPrice().toPlainString(), text, Element.ALIGN_RIGHT);
                row(t, it.discount().signum() > 0 ? it.discount().toPlainString() : "—", text, Element.ALIGN_RIGHT);
                row(t, it.lineTotal().toPlainString(), text, Element.ALIGN_RIGHT);
            }
            doc.add(t);

            PdfPTable sums = new PdfPTable(new float[]{6f, 2f});
            sums.setWidthPercentage(55);
            sums.setHorizontalAlignment(Element.ALIGN_RIGHT);
            sum(sums, "Cəmi", s.subtotal(), text);
            if (s.discount().signum() > 0) sum(sums, "Endirim", s.discount().negate(), text);
            if (s.returnedAmount().signum() > 0) sum(sums, "Qaytarılıb", s.returnedAmount().negate(), text);
            sum(sums, "YEKUN ÖDƏNİLMƏLİ", s.total().subtract(s.returnedAmount()), h2);
            if (s.paidCash().add(s.paidCard()).signum() > 0) sum(sums, "Ödənilib", s.paidCash().add(s.paidCard()), text);
            if (s.creditAmount().signum() > 0) sum(sums, "Borca (nisyə)", s.creditAmount(), h2);
            doc.add(new Paragraph(" ", small));
            doc.add(sums);
            doc.add(new Paragraph(" ", text));
            doc.add(new Paragraph(" ", text));

            PdfPTable sign = new PdfPTable(2);
            sign.setWidthPercentage(100);
            sign.addCell(signCell("Təhvil verdi" + (seller.director() != null ? " (" + seller.director() + ")" : ""), text, small));
            sign.addCell(signCell("Təhvil aldı", text, small));
            doc.add(sign);
            doc.add(new Paragraph(" ", text));
            Paragraph foot = new Paragraph("HesabPro ilə hazırlanıb", small);
            foot.setAlignment(Element.ALIGN_CENTER);
            doc.add(foot);
            doc.close();
            return new ExportFile(out.toByteArray(), "application/pdf", "qaime-" + s.number() + ".pdf");
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Qaimə yaradıla bilmədi");
        }
    }

    private static PdfPCell partyCell(String label, String name, String[][] lines, Font h2, Font text, Font small) {
        PdfPCell c = new PdfPCell();
        c.setPadding(8);
        c.setBorderColor(new Color(0xCB, 0xD5, 0xE1));
        c.addElement(new Paragraph(label.toUpperCase(), small));
        c.addElement(new Paragraph(name, h2));
        for (String[] l : lines) {
            if (l[1] != null && !l[1].isBlank()) c.addElement(new Paragraph(l[0] + ": " + l[1], text));
        }
        return c;
    }

    private static PdfPCell signCell(String label, Font text, Font small) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(6);
        c.addElement(new Paragraph(label + ":", text));
        c.addElement(new Paragraph(" ", text));
        c.addElement(new Paragraph("________________________   imza", small));
        c.addElement(new Paragraph("M.Y.", small));
        return c;
    }

    private static void row(PdfPTable t, String v, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(v, f));
        c.setPadding(4);
        c.setBorderColor(new Color(0xE2, 0xE8, 0xF0));
        c.setHorizontalAlignment(align);
        t.addCell(c);
    }

    private static void sum(PdfPTable t, String label, BigDecimal v, Font f) {
        PdfPCell a = new PdfPCell(new Phrase(label, f));
        a.setBorder(Rectangle.NO_BORDER);
        a.setHorizontalAlignment(Element.ALIGN_RIGHT);
        PdfPCell b = new PdfPCell(new Phrase(v.toPlainString() + " ₼", f));
        b.setBorder(Rectangle.NO_BORDER);
        b.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(a);
        t.addCell(b);
    }

    private static BaseFont font(boolean bold) throws Exception {
        String file = bold ? "fonts/DejaVuSans-Bold.ttf" : "fonts/DejaVuSans.ttf";
        return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                new ClassPathResource(file).getContentAsByteArray(), null);
    }

    private static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
