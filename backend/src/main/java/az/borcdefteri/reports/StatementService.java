package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.customers.CustomerPortalService;
import az.borcdefteri.customers.PartyCardService;
import az.borcdefteri.customers.PartyCardService.HistoryEntry;
import az.borcdefteri.customers.PartyService;
import az.borcdefteri.customers.PartyService.Party;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Per-party PDF statement ("borc çıxarışı") with the HesabPro logo, store and party details and every step. */
@Service
public class StatementService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Color NAVY = new Color(0x10, 0x1C, 0x35);

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PartyService parties;
    private final PartyCardService cards;
    private final CustomerPortalService portal;
    private final Clock clock;

    public StatementService(JdbcClient jdbc, TenantService tenant, PartyService parties, PartyCardService cards,
                            CustomerPortalService portal, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.parties = parties;
        this.cards = cards;
        this.portal = portal;
        this.clock = clock;
    }

    public ExportFile forStaff(boolean customer, UUID partyId) {
        CurrentMember m = tenant.requireBusinessMember();
        Party p = customer ? parties.customer(m, partyId) : parties.company(m, partyId);
        List<String> types = new ArrayList<>();
        for (DebtType t : DebtType.values()) {
            if (t.isCustomer() == customer && m.has(t.permission())) {
                types.add(t.name());
            }
        }
        return build(m.storeId(), customer, p.id(), p.name(), p.phone(), p.code(), types);
    }

    /** From the customer portal link (already validated by the token). */
    public ExportFile forPortal(String token) {
        CustomerPortalService.Resolved r = portal.resolve(token);
        record Head(String name, String phone, String code) {}
        Head h = jdbc.sql("SELECT name, phone, code FROM customers WHERE id = :c AND store_id = :s")
                .param("c", r.customerId()).param("s", r.storeId())
                .query((rs, n) -> new Head(rs.getString("name"), rs.getString("phone"), rs.getString("code")))
                .optional().orElseThrow(() -> ApiException.notFound("Tapılmadı"));
        return build(r.storeId(), true, r.customerId(), h.name(), h.phone(), h.code(), List.of("CUSTOMER_DEBT"));
    }

    private ExportFile build(UUID storeId, boolean customer, UUID partyId, String name, String phone, String code,
                             List<String> types) {
        record Store(String name, String phone, String address) {}
        Store store = jdbc.sql("SELECT name, phone, address FROM stores WHERE id = :s").param("s", storeId)
                .query((rs, n) -> new Store(rs.getString("name"), rs.getString("phone"), rs.getString("address"))).single();
        String column = customer ? "customer_id" : "company_id";
        BigDecimal[] totals = types.isEmpty() ? new BigDecimal[]{Money.zero(), Money.zero(), Money.zero()} :
                jdbc.sql("SELECT COALESCE(SUM(amount),0) a, COALESCE(SUM(paid_amount),0) p, COALESCE(SUM(remaining_amount),0) r "
                                + "FROM debts WHERE store_id = :s AND " + column + " = :p AND debt_type IN (:types)")
                        .param("s", storeId).param("p", partyId).param("types", types)
                        .query((rs, n) -> new BigDecimal[]{Money.of(rs.getBigDecimal("a")), Money.of(rs.getBigDecimal("p")),
                                Money.of(rs.getBigDecimal("r"))}).single();
        List<HistoryEntry> history = cards.history(storeId, customer, partyId, types);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Baku")));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont base = ReportService.baseFont(false);
            BaseFont bold = ReportService.baseFont(true);
            Font title = new Font(bold, 16, Font.NORMAL, NAVY);
            Font small = new Font(base, 9, Font.NORMAL, Color.DARK_GRAY);
            Font cell = new Font(base, 8.5f, Font.NORMAL, Color.BLACK);
            Font head = new Font(bold, 8.5f, Font.NORMAL, Color.WHITE);
            Font label = new Font(base, 8, Font.NORMAL, Color.GRAY);
            Font value = new Font(bold, 12, Font.NORMAL, NAVY);

            PdfPTable top = new PdfPTable(new float[]{1.2f, 2f});
            top.setWidthPercentage(100);
            PdfPCell logoCell = new PdfPCell();
            logoCell.setBorder(0);
            try {
                Image logo = Image.getInstance(new ClassPathResource("branding/logo.png").getContentAsByteArray());
                logo.scaleToFit(130, 60);
                logoCell.addElement(logo);
            } catch (Exception ignored) {
                logoCell.addElement(new Paragraph("HesabPro", title));
            }
            top.addCell(logoCell);
            PdfPCell storeCell = new PdfPCell();
            storeCell.setBorder(0);
            storeCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph sp = new Paragraph(nz(store.name()), new Font(bold, 11, Font.NORMAL, NAVY));
            sp.setAlignment(Element.ALIGN_RIGHT);
            storeCell.addElement(sp);
            for (String line : new String[]{store.phone(), store.address()}) {
                if (line != null && !line.isBlank()) {
                    Paragraph lp = new Paragraph(line, small);
                    lp.setAlignment(Element.ALIGN_RIGHT);
                    storeCell.addElement(lp);
                }
            }
            top.addCell(storeCell);
            doc.add(top);

            Paragraph t = new Paragraph("Borc çıxarışı", title);
            t.setSpacingBefore(10);
            doc.add(t);
            doc.add(new Paragraph((customer ? "Müştəri: " : "Şirkət: ") + nz(name)
                    + (code != null ? "   |   Kod: " + code : "")
                    + (phone != null && !phone.isBlank() ? "   |   Tel: " + phone : ""), small));
            doc.add(new Paragraph("Hesabat tarixi: " + today.format(DATE), small));
            doc.add(new Paragraph(" "));

            PdfPTable sums = new PdfPTable(3);
            sums.setWidthPercentage(100);
            String[][] items = {{"Ümumi borc", money(totals[0])}, {"Ödənilib", money(totals[1])}, {"Qalıq borc", money(totals[2])}};
            for (String[] it : items) {
                PdfPCell c = new PdfPCell();
                c.setPadding(8);
                c.setBorderColor(new Color(0xE2, 0xE8, 0xF0));
                c.addElement(new Paragraph(it[0], label));
                c.addElement(new Paragraph(it[1], value));
                sums.addCell(c);
            }
            doc.add(sums);
            doc.add(new Paragraph(" "));

            String[] headers = customer
                    ? new String[]{"Tarix", "Əməliyyat", "Çek", "Qeyd", "Məbləğ", "Qalıq"}
                    : new String[]{"Tarix", "İstiqamət", "Əməliyyat", "Çek", "Qeyd", "Məbləğ", "Qalıq"};
            float[] widths = customer ? new float[]{1.1f, 1.8f, 1.1f, 2.6f, 1.2f, 1.2f}
                    : new float[]{1.1f, 1.6f, 1.6f, 1f, 2.1f, 1.2f, 1.2f};
            PdfPTable table = new PdfPTable(widths);
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (String h : headers) {
                PdfPCell c = new PdfPCell(new Phrase(h, head));
                c.setBackgroundColor(NAVY);
                c.setPadding(5);
                table.addCell(c);
            }
            for (HistoryEntry e : history) {
                add(table, e.entryDate() == null ? "" : e.entryDate().format(DATE), cell, Element.ALIGN_LEFT);
                if (!customer) {
                    add(table, e.debtType() == DebtType.COMPANY_PAYABLE ? "Biz borcluyuq" : "Firma borcludur", cell, Element.ALIGN_LEFT);
                }
                add(table, typeLabel(e.type()), cell, Element.ALIGN_LEFT);
                add(table, nz(e.receiptNumber()), cell, Element.ALIGN_LEFT);
                add(table, nz(e.note()), cell, Element.ALIGN_LEFT);
                add(table, (e.type().startsWith("PAYMENT") ? "−" : "+") + e.amount().abs().toPlainString(), cell, Element.ALIGN_RIGHT);
                add(table, e.balanceAfter().toPlainString(), cell, Element.ALIGN_RIGHT);
            }
            if (history.isEmpty()) {
                PdfPCell c = new PdfPCell(new Phrase("Əməliyyat yoxdur", cell));
                c.setColspan(headers.length);
                c.setPadding(6);
                table.addCell(c);
            }
            doc.add(table);
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Bu sənəd HesabPro sistemində avtomatik hazırlanıb. Məbləğlər AZN ilədir.", small));
            doc.close();
            String file = "cixaris-" + (code != null ? code : partyId.toString().substring(0, 8)) + "-"
                    + today.format(DateTimeFormatter.BASIC_ISO_DATE) + ".pdf";
            return new ExportFile(out.toByteArray(), "application/pdf", file);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED",
                    "PDF faylı yaradıla bilmədi");
        }
    }

    static String typeLabel(String type) {
        return switch (type) {
            case "DEBT_CREATED" -> "Borc yazıldı";
            case "DEBT_INCREASED" -> "Borc artırıldı";
            case "DEBT_ADJUSTED" -> "Məbləğ dəyişdirildi";
            case "PAYMENT_CASH" -> "Ödəniş (nağd)";
            case "PAYMENT_CARD" -> "Ödəniş (kart)";
            case "PAYMENT_TRANSFER" -> "Ödəniş (köçürmə)";
            case "PAYMENT_OTHER" -> "Ödəniş (digər)";
            default -> type;
        };
    }

    private static void add(PdfPTable table, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        table.addCell(c);
    }

    private static String money(BigDecimal v) {
        return v.toPlainString() + " AZN";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
