package az.borcdefteri.settings;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Çek və barkod etiketi dizaynı of the store: {"receipt": {...}, "label": {...}}. The apps own the shape of each part
 * (paper, header lines, logo, label size, ...); the server keeps it so every till and browser prints the same way.
 * Everyone in the store reads it; the owner or a member with TILL_SETTINGS changes it.
 */
@RestController
public class PrintDesignService {

    /** The logo is a small data URL; this keeps one design well under a few hundred KB. */
    static final int MAX_BYTES = 600_000;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final ObjectMapper json;

    public PrintDesignService(JdbcClient jdbc, TenantService tenant, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.json = json;
    }

    @GetMapping("/api/settings/print-design")
    public JsonNode get() {
        CurrentMember m = tenant.requireBusinessMember();
        return load(m);
    }

    /** Replaces the parts sent ("receipt" and / or "label"); a part sent as null is reset to the default. */
    @PutMapping("/api/settings/print-design")
    public JsonNode update(@RequestBody JsonNode body) {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.isOwner() && !m.has("TILL_SETTINGS")) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Çek və etiket dizaynını yalnız mağaza sahibi və ya kassa parametrləri icazəsi olan dəyişə bilər");
        }
        if (body == null || !body.isObject()) throw ApiException.badRequest("INVALID_DESIGN", "Dizayn düzgün deyil");
        ObjectNode next = (ObjectNode) load(m);
        for (String part : new String[]{"receipt", "label"}) {
            if (!body.has(part)) continue;
            JsonNode v = body.get(part);
            if (v.isNull()) next.remove(part);
            else if (v.isObject()) next.set(part, v);
            else throw ApiException.badRequest("INVALID_DESIGN", "Dizayn düzgün deyil: " + part);
        }
        String text;
        try {
            text = json.writeValueAsString(next);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("INVALID_DESIGN", "Dizayn düzgün deyil");
        }
        if (text.length() > MAX_BYTES) throw ApiException.badRequest("DESIGN_TOO_BIG", "Dizayn çox böyükdür — daha kiçik loqo seçin");
        jdbc.sql("UPDATE stores SET print_design = CAST(:d AS jsonb) WHERE id = :s").param("d", text).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "PRINT_DESIGN_UPDATED", "STORE", m.storeId().toString(),
                String.join(",", (Iterable<String>) () -> body.fieldNames()));
        return next;
    }

    private JsonNode load(CurrentMember m) {
        String raw = jdbc.sql("SELECT print_design::text FROM stores WHERE id = :s").param("s", m.storeId())
                .query(String.class).optional().orElse(null);
        if (raw == null) return json.createObjectNode();
        try {
            JsonNode n = json.readTree(raw);
            return n.isObject() ? n : json.createObjectNode();
        } catch (JsonProcessingException e) {
            return json.createObjectNode();
        }
    }
}
