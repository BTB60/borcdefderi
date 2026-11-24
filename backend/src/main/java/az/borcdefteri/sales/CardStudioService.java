package az.borcdefteri.sales;

import az.borcdefteri.assistant.CardDesignAi;
import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bonus Kart Design Studio: the store's saved card designs (each a JSON document the studio draws), new designs
 * from the AI designer, and the print PDF (bank-card size with bleed, front and back, RGB or CMYK).
 */
@Service
@RestController
@RequestMapping("/api/card-studio")
public class CardStudioService {

    public record DesignRequest(@NotBlank(message = "Dizayna ad verin") @Size(max = 80) String name,
                                @Size(max = 30) String category,
                                @NotNull(message = "Dizayn boşdur") JsonNode spec) {}

    public record Design(UUID id, String name, String category, JsonNode spec, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record GenerateRequest(@Size(max = 30) String category, @Size(max = 200, message = "İstək ən çox 200 simvol") String idea) {}

    public record Generated(CardDesignAi.AiCard card, int usedToday, int dailyLimit) {}

    public record Status(boolean ai, int usedToday, int dailyLimit) {}

    /** The two sides as PNG / JPEG data URLs, already drawn at print size by the browser. */
    public record PdfRequest(@NotBlank String front, @NotBlank String back, Boolean cmyk, @Size(max = 80) String name) {}

    static final int MAX_DESIGNS = 60;
    static final int MAX_SPEC = 200_000;
    /** 85.60 × 53.98 mm with 3 mm bleed on every side. */
    static final float BLEED_MM = 3f, CARD_W_MM = 85.60f, CARD_H_MM = 53.98f;

    private static final List<String> TWISTS = List.of(
            "a diagonal composition with the character peeking in from the right edge",
            "a bold pop-art look with thick outlines and halftone dots",
            "a soft pastel palette with a sleepy, cute character",
            "a night-time neon look with glowing accents",
            "a sunny summer mood with warm oranges and a jumping character",
            "a winter mood with cool blues and a character in a scarf",
            "a luxurious black and gold look with an elegant, smug character",
            "a retro 80s palette with stripes and a character giving a thumbs up",
            "a playful kids' style with candy colours and a giggling character",
            "a calm minimalist style with lots of space and a tiny character",
            "a fresh green market feel with a character holding fruit",
            "a cosy café feel with browns and creams and a character holding a cup",
            "a festive look with confetti and a character holding a present",
            "a sporty dynamic look with speed lines and a running character");

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final CardDesignAi ai;
    private final ObjectMapper json;
    private final int dailyLimit;

    public CardStudioService(JdbcClient jdbc, TenantService tenant, AuditService audit, CardDesignAi ai, ObjectMapper json,
                             @Value("${app.card-studio.ai-daily-limit:20}") int dailyLimit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.ai = ai;
        this.json = json;
        this.dailyLimit = dailyLimit;
    }

    private CurrentMember requireAccess() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.isOwner() && !m.has("SALES") && !m.has("TILL_SETTINGS")) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return m;
    }

    // ---------- saved designs (each store sees only its own) ----------

    @GetMapping("/designs")
    public List<Design> list() {
        CurrentMember m = requireAccess();
        return jdbc.sql("SELECT * FROM card_studio_designs WHERE store_id = :s ORDER BY updated_at DESC")
                .param("s", m.storeId()).query((rs, n) -> map(rs)).list();
    }

    @PostMapping("/designs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Design create(@Valid @RequestBody DesignRequest req) {
        CurrentMember m = requireAccess();
        String spec = spec(req.spec());
        long count = jdbc.sql("SELECT COUNT(*) FROM card_studio_designs WHERE store_id = :s").param("s", m.storeId()).query(Long.class).single();
        if (count >= MAX_DESIGNS) throw ApiException.conflict("DESIGN_LIMIT", "Ən çox " + MAX_DESIGNS + " dizayn saxlamaq olar. Köhnələrini silin");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO card_studio_designs (id, store_id, name, category, spec, created_by)
                VALUES (:id, :s, :n, :c, CAST(:spec AS jsonb), :by)""")
                .param("id", id).param("s", m.storeId()).param("n", req.name().trim()).param("c", blank(req.category()))
                .param("spec", spec).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "CARD_DESIGN_SAVED", "CARD_DESIGN", id.toString(), req.name().trim());
        return get(m, id);
    }

    @PutMapping("/designs/{id}")
    @Transactional
    public Design update(@PathVariable UUID id, @Valid @RequestBody DesignRequest req) {
        CurrentMember m = requireAccess();
        get(m, id);
        jdbc.sql("""
                UPDATE card_studio_designs SET name = :n, category = :c, spec = CAST(:spec AS jsonb), updated_at = now()
                WHERE id = :id AND store_id = :s""")
                .param("n", req.name().trim()).param("c", blank(req.category())).param("spec", spec(req.spec()))
                .param("id", id).param("s", m.storeId()).update();
        return get(m, id);
    }

    @DeleteMapping("/designs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        CurrentMember m = requireAccess();
        Design d = get(m, id);
        jdbc.sql("DELETE FROM card_studio_designs WHERE id = :id AND store_id = :s").param("id", id).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "CARD_DESIGN_DELETED", "CARD_DESIGN", id.toString(), d.name());
    }

    // ---------- the AI designer ----------

    @GetMapping("/status")
    public Status status() {
        CurrentMember m = requireAccess();
        return new Status(ai.configured(), usedToday(m), dailyLimit);
    }

    @PostMapping("/generate")
    public Generated generate(@Valid @RequestBody GenerateRequest req) {
        CurrentMember m = requireAccess();
        if (!ai.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_OFF", "AI dizayner qoşulmayıb — daxili generator işləyir");
        }
        int used = usedToday(m);
        if (used >= dailyLimit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_LIMIT", "Bu gün üçün AI dizayn limiti (" + dailyLimit + ") bitib. Daxili generatorla davam edin");
        }
        String category = req.category() == null || req.category().isBlank() ? "any shop" : req.category().trim();
        String twist = TWISTS.get(ThreadLocalRandom.current().nextInt(TWISTS.size()));
        CardDesignAi.Result r = ai.design(category, req.idea(), twist);
        jdbc.sql("""
                INSERT INTO card_studio_ai_usage (store_id, day, designs, input_tokens, output_tokens) VALUES (:s, :d, 1, :i, :o)
                ON CONFLICT (store_id, day) DO UPDATE SET designs = card_studio_ai_usage.designs + 1,
                    input_tokens = card_studio_ai_usage.input_tokens + :i, output_tokens = card_studio_ai_usage.output_tokens + :o""")
                .param("s", m.storeId()).param("d", tenant.today()).param("i", r.inputTokens()).param("o", r.outputTokens()).update();
        return new Generated(r.card(), used + 1, dailyLimit);
    }

    private int usedToday(CurrentMember m) {
        return jdbc.sql("SELECT designs FROM card_studio_ai_usage WHERE store_id = :s AND day = :d")
                .param("s", m.storeId()).param("d", tenant.today()).query(Integer.class).optional().orElse(0);
    }

    // ---------- print PDF ----------

    /**
     * Two pages (front, back), each 91.6 × 59.98 mm: the card with 3 mm bleed, the trim box set to the card itself.
     * CMYK converts the picture to DeviceCMYK (a plain conversion without an ICC profile — ask the print shop for proof).
     */
    @PostMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@Valid @RequestBody PdfRequest req) {
        requireAccess();
        float mm = 72f / 25.4f;
        Rectangle page = new Rectangle((CARD_W_MM + 2 * BLEED_MM) * mm, (CARD_H_MM + 2 * BLEED_MM) * mm);
        Rectangle trim = new Rectangle(BLEED_MM * mm, BLEED_MM * mm, (BLEED_MM + CARD_W_MM) * mm, (BLEED_MM + CARD_H_MM) * mm);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(page, 0, 0, 0, 0);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setBoxSize("trim", trim);
            writer.setBoxSize("bleed", page);
            doc.addTitle(req.name() == null || req.name().isBlank() ? "Bonus kart" : req.name().trim());
            doc.addCreator("HesabPro Bonus Kart Studio");
            doc.open();
            for (String side : new String[]{req.front(), req.back()}) {
                Image img = image(side, Boolean.TRUE.equals(req.cmyk()));
                img.scaleAbsolute(page.getWidth(), page.getHeight());
                img.setAbsolutePosition(0, 0);
                doc.add(img);
                if (side == req.front()) doc.newPage();
            }
            doc.close();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(CardStudioService.class).warn("Card PDF failed", e);
            throw ApiException.badRequest("PDF_FAILED", "PDF hazırlanmadı");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bonus-kart.pdf\"").body(out.toByteArray());
    }

    private static Image image(String dataUrl, boolean cmyk) throws Exception {
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:image/") || comma < 0) throw ApiException.badRequest("INVALID_IMAGE", "Şəkil düzgün deyil");
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        if (bytes.length > 12_000_000) throw ApiException.badRequest("IMAGE_TOO_BIG", "Şəkil çox böyükdür");
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("INVALID_IMAGE", "Şəkil oxunmadı");
        }
        if (src == null) throw ApiException.badRequest("INVALID_IMAGE", "Şəkil oxunmadı");
        if (src.getWidth() > 4000 || src.getHeight() > 4000) throw ApiException.badRequest("IMAGE_TOO_BIG", "Şəkil çox böyükdür");
        if (!cmyk) return Image.getInstance(bytes);
        int w = src.getWidth(), h = src.getHeight();
        byte[] data = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
                double k = 1 - Math.max(r, Math.max(g, b));
                double c = k >= 1 ? 0 : (1 - r - k) / (1 - k), mg = k >= 1 ? 0 : (1 - g - k) / (1 - k), ye = k >= 1 ? 0 : (1 - b - k) / (1 - k);
                data[i++] = (byte) Math.round(c * 255);
                data[i++] = (byte) Math.round(mg * 255);
                data[i++] = (byte) Math.round(ye * 255);
                data[i++] = (byte) Math.round(k * 255);
            }
        }
        return Image.getInstance(w, h, 4, 8, data);
    }

    // ---------- helpers ----------

    private String spec(JsonNode spec) {
        if (spec == null || !spec.isObject()) throw ApiException.badRequest("INVALID_DESIGN", "Dizayn düzgün deyil");
        try {
            String s = json.writeValueAsString(spec);
            if (s.length() > MAX_SPEC) throw ApiException.badRequest("DESIGN_TOO_BIG", "Dizayn çox böyükdür");
            return s;
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("INVALID_DESIGN", "Dizayn düzgün deyil");
        }
    }

    private Design get(CurrentMember m, UUID id) {
        return jdbc.sql("SELECT * FROM card_studio_designs WHERE id = :id AND store_id = :s").param("id", id).param("s", m.storeId())
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("Dizayn tapılmadı"));
    }

    private Design map(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode spec;
        try {
            spec = json.readTree(rs.getString("spec"));
        } catch (JsonProcessingException e) {
            spec = json.createObjectNode();
        }
        return new Design(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"), spec,
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
