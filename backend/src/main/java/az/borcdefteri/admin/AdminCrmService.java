package az.borcdefteri.admin;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.TenantService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The platform admin's own work tools: notes about a store with follow-up dates ("Bu gün" list), tags,
 * and the Excel export of stores, payments and monthly income.
 */
@Service
public class AdminCrmService {

    public record Note(UUID id, UUID storeId, String storeName, String storePhone, String ownerPhone, String body,
                       LocalDate followUpOn, OffsetDateTime doneAt, OffsetDateTime createdAt, String createdByName) {}

    public record ExcelFile(byte[] content, String fileName) {}

    private static final String SELECT_NOTE = """
            SELECT n.*, s.name AS store_name, s.phone AS store_phone, u.full_name AS created_by_name,
                   (SELECT ou.phone FROM store_members m JOIN users ou ON ou.id = m.user_id
                     WHERE m.store_id = n.store_id AND m.role = 'STORE_OWNER' LIMIT 1) AS owner_phone
            FROM admin_store_notes n
            JOIN stores s ON s.id = n.store_id
            LEFT JOIN users u ON u.id = n.created_by
            """;

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AdminService admin;

    public AdminCrmService(JdbcClient jdbc, TenantService tenant, AdminService admin) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.admin = admin;
    }

    public List<Note> notes(UUID storeId) {
        tenant.requirePlatformAdmin();
        return jdbc.sql(SELECT_NOTE + " WHERE n.store_id = :s ORDER BY n.created_at DESC LIMIT 200")
                .param("s", storeId).query(this::map).list();
    }

    /** Open follow-ups due today or earlier: the admin's "Bu gün" list. */
    public List<Note> dueFollowUps() {
        tenant.requirePlatformAdmin();
        return jdbc.sql(SELECT_NOTE + """
                 WHERE n.done_at IS NULL AND n.follow_up_on IS NOT NULL AND n.follow_up_on <= :today
                 ORDER BY n.follow_up_on, n.created_at LIMIT 100
                """).param("today", tenant.today()).query(this::map).list();
    }

    @Transactional
    public Note addNote(UUID storeId, String body, LocalDate followUpOn) {
        tenant.requirePlatformAdmin();
        requireStore(storeId);
        String text = body == null ? "" : body.trim();
        if (text.isEmpty() || text.length() > 1000) {
            throw ApiException.badRequest("INVALID_NOTE", "Qeyd 1-1000 simvol olmalıdır");
        }
        if (followUpOn != null && (followUpOn.isBefore(tenant.today().minusDays(1)) || followUpOn.isAfter(tenant.today().plusYears(1)))) {
            throw ApiException.badRequest("INVALID_DATE", "Xatırlatma tarixi düzgün deyil");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO admin_store_notes (id, store_id, body, follow_up_on, created_by)
                VALUES (:id, :s, :b, :f, :by)
                """).param("id", id).param("s", storeId).param("b", text).param("f", followUpOn)
                .param("by", tenant.currentUserId()).update();
        return get(id);
    }

    @Transactional
    public Note setDone(UUID noteId, boolean done) {
        tenant.requirePlatformAdmin();
        get(noteId);
        jdbc.sql("UPDATE admin_store_notes SET done_at = CASE WHEN :d THEN now() ELSE NULL END WHERE id = :id")
                .param("d", done).param("id", noteId).update();
        return get(noteId);
    }

    @Transactional
    public void deleteNote(UUID noteId) {
        tenant.requirePlatformAdmin();
        get(noteId);
        jdbc.sql("DELETE FROM admin_store_notes WHERE id = :id").param("id", noteId).update();
    }

    /** Free tags, e.g. VIP / Problemli / Maraqlanmır: at most 8 tags of up to 30 characters. */
    @Transactional
    public List<String> setTags(UUID storeId, List<String> tags) {
        tenant.requirePlatformAdmin();
        requireStore(storeId);
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String t : tags == null ? List.<String>of() : tags) {
            String v = t == null ? "" : t.replace(",", " ").trim();
            if (v.isEmpty()) continue;
            if (v.length() > 30) throw ApiException.badRequest("INVALID_TAG", "Etiket 30 simvoldan uzun ola bilməz");
            clean.add(v);
        }
        if (clean.size() > 8) throw ApiException.badRequest("INVALID_TAG", "Ən çox 8 etiket");
        jdbc.sql("UPDATE stores SET admin_tags = :t WHERE id = :id").param("t", String.join(",", clean)).param("id", storeId).update();
        return List.copyOf(clean);
    }

    static List<String> parseTags(String raw) {
        return raw == null || raw.isBlank() ? List.of() : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** One workbook: every store with its package and activity, every approved payment, income by month. */
    public ExcelFile export() {
        tenant.requireSuperAdmin();
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);

            Sheet stores = wb.createSheet("Mağazalar");
            String[] cols = {"Mağaza", "Sahib", "E-poçt", "Telefon", "Paket", "Abunə vəziyyəti", "Bitmə tarixi", "Qalan gün",
                    "Sınaq", "Son giriş", "Bu ay satış", "Bu ay satış cəmi", "İstifadə etmir", "Etiketlər", "Status", "Qeydiyyat"};
            header(stores, head, cols);
            int r = 1;
            for (Map<String, Object> s : admin.stores(null)) {
                Row row = stores.createRow(r++);
                Object[] v = {s.get("name"), s.get("ownerName"), s.get("ownerEmail"),
                        s.get("ownerPhone") != null ? s.get("ownerPhone") : s.get("phone"), s.get("activePlan"),
                        switch (String.valueOf(s.get("subscriptionState"))) { case "ACTIVE" -> "Aktiv"; case "EXPIRED" -> "Bitib"; default -> "Abunə yoxdur"; },
                        s.get("subscriptionEnd"), s.get("daysRemaining"), s.get("onTrial"), s.get("lastLoginAt"),
                        s.get("salesThisMonth"), s.get("salesTotalThisMonth"), s.get("idle"),
                        String.join(", ", castList(s.get("tags"))),
                        "ACTIVE".equals(s.get("status")) ? "Aktiv" : "Dayandırılıb", s.get("createdAt")};
                for (int i = 0; i < v.length; i++) cell(row, i, v[i]);
            }

            sqlSheet(wb, head, "Ödənişlər", """
                    SELECT s.reviewed_at AS "Təsdiq tarixi", st.name AS "Mağaza", p.name AS "Paket",
                           COALESCE(s.period_code, '') AS "Müddət", s.amount AS "Məbləğ", s.discount AS "Endirim",
                           pc.code AS "Promo-kod", s.payment_method AS "Üsul", s.start_date AS "Başlama", s.end_date AS "Bitmə",
                           s.status AS "Status"
                    FROM subscriptions s JOIN stores st ON st.id = s.store_id JOIN subscription_plans p ON p.id = s.plan_id
                    LEFT JOIN promo_codes pc ON pc.id = s.promo_code_id
                    WHERE s.status IN ('ACTIVE', 'EXPIRED') AND NOT s.trial AND s.reviewed_at IS NOT NULL
                    ORDER BY s.reviewed_at DESC""");

            Sheet months = wb.createSheet("Aylıq gəlir");
            header(months, head, new String[]{"Ay", "Gəlir", "Ödəyən mağaza", "Yeni", "Ayrılan", "Ayrılma %"});
            r = 1;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> monthly = (List<Map<String, Object>>) admin.revenue().get("monthly");
            for (Map<String, Object> m : monthly) {
                Row row = months.createRow(r++);
                Object[] v = {m.get("month"), m.get("revenue"), m.get("payingStores"), m.get("newPayingStores"),
                        m.get("churnedStores"), m.get("churnRate")};
                for (int i = 0; i < v.length; i++) cell(row, i, v[i]);
            }

            wb.write(out);
            wb.dispose();
            return new ExcelFile(out.toByteArray(), "hesabpro-platforma-" + tenant.today() + ".xlsx");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static void header(Sheet sh, CellStyle head, String[] cols) {
        Row h = sh.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            var c = h.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(head);
            sh.setColumnWidth(i, 18 * 256);
        }
    }

    private static void cell(Row row, int i, Object v) {
        var c = row.createCell(i);
        if (v == null) return;
        if (v instanceof BigDecimal b) c.setCellValue(b.doubleValue());
        else if (v instanceof Number n) c.setCellValue(n.doubleValue());
        else if (v instanceof Boolean b) c.setCellValue(b ? "Bəli" : "");
        else if (v instanceof OffsetDateTime t) c.setCellValue(t.atZoneSameInstant(java.time.ZoneId.of("Asia/Baku")).toLocalDateTime().toString().replace('T', ' ').substring(0, 16));
        else if (v instanceof java.sql.Timestamp t) c.setCellValue(t.toLocalDateTime().toString().replace('T', ' '));
        else if (v instanceof TemporalAccessor) c.setCellValue(v.toString().replace('T', ' '));
        else c.setCellValue(v.toString());
    }

    private void sqlSheet(SXSSFWorkbook wb, CellStyle head, String name, String sql) {
        Sheet sh = wb.createSheet(name);
        int[] r = {0};
        jdbc.sql(sql).query(rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            if (r[0] == 0) {
                String[] labels = new String[cols];
                for (int i = 1; i <= cols; i++) labels[i - 1] = md.getColumnLabel(i);
                header(sh, head, labels);
                r[0] = 1;
            }
            Row row = sh.createRow(r[0]++);
            for (int i = 1; i <= cols; i++) cell(row, i - 1, rs.getObject(i));
        });
        if (r[0] == 0) sh.createRow(0).createCell(0).setCellValue("Məlumat yoxdur");
    }

    private void requireStore(UUID storeId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM stores WHERE id = :id").param("id", storeId).query(Long.class).single() > 0;
        if (!exists) throw ApiException.notFound("Mağaza tapılmadı");
    }

    private Note get(UUID id) {
        return jdbc.sql(SELECT_NOTE + " WHERE n.id = :id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Qeyd tapılmadı"));
    }

    private Note map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Note(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class), rs.getString("store_name"),
                rs.getString("store_phone"), rs.getString("owner_phone"), rs.getString("body"),
                rs.getObject("follow_up_on", LocalDate.class), rs.getObject("done_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by_name"));
    }

    static Map<String, Object> tagsResponse(List<String> tags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tags", tags);
        return m;
    }
}
