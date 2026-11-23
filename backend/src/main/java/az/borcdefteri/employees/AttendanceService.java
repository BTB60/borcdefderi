package az.borcdefteri.employees;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * İşçi davamiyyəti. Every member (the owner too) clocks in when they come ("Gəldim") and out when they leave
 * ("Getdim"); one open record per person. A check-in after the store's work start + grace minutes is marked late.
 * Employees see their own records; the owner sees everyone's, a per-person summary, and may correct a record.
 */
@Service
public class AttendanceService {

    public record Record(UUID id, UUID userId, String fullName, LocalDate workDate, OffsetDateTime checkIn,
                         OffsetDateTime checkOut, long minutes, boolean late, String note, boolean edited, boolean open) {}

    public record Note(@jakarta.validation.constraints.Size(max = 300) String note) {}

    public record Edit(@jakarta.validation.constraints.NotNull OffsetDateTime checkIn, OffsetDateTime checkOut,
                       @jakarta.validation.constraints.Size(max = 300) String note) {}

    public record PersonSummary(UUID userId, String fullName, long days, long lateDays, long minutes, boolean presentNow) {}

    /** {@code workStart} "HH:mm" or null (lateness is then not tracked). */
    public record Settings(String workStart, int graceMinutes) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final Clock clock;

    public AttendanceService(JdbcClient jdbc, TenantService tenant, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.clock = clock;
    }

    /** The caller's open record (checked in, not yet out), or null. */
    public Record current() {
        CurrentMember m = tenant.requireBusinessMember();
        return jdbc.sql(SELECT + " WHERE a.store_id = :s AND a.user_id = :u AND a.check_out IS NULL")
                .param("s", m.storeId()).param("u", m.userId()).query((rs, n) -> map(rs)).optional().orElse(null);
    }

    @Transactional
    public Record checkIn(Note req) {
        CurrentMember m = tenant.requireBusinessMember();
        if (current() != null) throw ApiException.conflict("ALREADY_IN", "Siz artıq “Gəldim” qeyd etmisiniz");
        ZonedDateTime now = ZonedDateTime.now(clock);
        Settings st = settingsOf(m.storeId());
        boolean late = st.workStart() != null
                && now.toLocalTime().isAfter(LocalTime.parse(st.workStart()).plusMinutes(st.graceMinutes()));
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO attendance (id, store_id, user_id, work_date, check_in, late, note)
                VALUES (:id, :s, :u, :d, :t, :late, :note)""")
                .param("id", id).param("s", m.storeId()).param("u", m.userId()).param("d", now.toLocalDate())
                .param("t", now.toOffsetDateTime()).param("late", late).param("note", note(req)).update();
        audit.log(m.storeId(), m.userId(), "ATTENDANCE_IN", "ATTENDANCE", id.toString(), late ? "gecikmə" : null);
        return load(m.storeId(), id);
    }

    @Transactional
    public Record checkOut(Note req) {
        CurrentMember m = tenant.requireBusinessMember();
        Record open = current();
        if (open == null) throw ApiException.conflict("NOT_IN", "Əvvəlcə “Gəldim” qeyd edin");
        String note = note(req);
        jdbc.sql("UPDATE attendance SET check_out = :t, note = COALESCE(:note, note) WHERE id = :id")
                .param("t", OffsetDateTime.now(clock)).param("note", note).param("id", open.id()).update();
        audit.log(m.storeId(), m.userId(), "ATTENDANCE_OUT", "ATTENDANCE", open.id().toString(), null);
        return load(m.storeId(), open.id());
    }

    /** Records between two days (inclusive). The owner may filter by person; an employee gets only their own. */
    public List<Record> list(LocalDate from, LocalDate to, UUID userId) {
        CurrentMember m = tenant.requireBusinessMember();
        LocalDate[] range = range(from, to);
        UUID who = m.isOwner() ? userId : m.userId();
        var spec = jdbc.sql(SELECT + " WHERE a.store_id = :s AND a.work_date BETWEEN :f AND :t"
                        + (who != null ? " AND a.user_id = :u" : "") + " ORDER BY a.check_in DESC LIMIT 1000")
                .param("s", m.storeId()).param("f", range[0]).param("t", range[1]);
        if (who != null) spec = spec.param("u", who);
        return spec.query((rs, n) -> map(rs)).list();
    }

    /** Owner: one line per active member — days present, late days, hours, and whether they are at work now. */
    public List<PersonSummary> summary(LocalDate from, LocalDate to) {
        CurrentMember m = tenant.requireOwner();
        LocalDate[] range = range(from, to);
        return jdbc.sql("""
                SELECT u.id, u.full_name,
                       COUNT(DISTINCT a.work_date) AS days,
                       COUNT(DISTINCT a.work_date) FILTER (WHERE a.late) AS late_days,
                       COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(a.check_out, now()) - a.check_in)) / 60), 0)::bigint AS minutes,
                       EXISTS (SELECT 1 FROM attendance o WHERE o.store_id = :s AND o.user_id = u.id AND o.check_out IS NULL) AS present
                FROM store_members sm JOIN users u ON u.id = sm.user_id
                LEFT JOIN attendance a ON a.user_id = u.id AND a.store_id = sm.store_id AND a.work_date BETWEEN :f AND :t
                WHERE sm.store_id = :s AND sm.active
                GROUP BY u.id, u.full_name ORDER BY u.full_name""")
                .param("s", m.storeId()).param("f", range[0]).param("t", range[1])
                .query((rs, n) -> new PersonSummary(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getLong("days"), rs.getLong("late_days"), rs.getLong("minutes"), rs.getBoolean("present")))
                .list();
    }

    /** Owner corrects a record (forgot to clock out, wrong time). */
    @Transactional
    public Record edit(UUID id, Edit req) {
        CurrentMember m = tenant.requireOwner();
        Record cur = load(m.storeId(), id);
        if (req.checkOut() != null && req.checkOut().isBefore(req.checkIn())) {
            throw ApiException.badRequest("INVALID_TIME", "Çıxış vaxtı gəliş vaxtından əvvəl ola bilməz");
        }
        if (req.checkOut() == null && !cur.open()) {
            throw ApiException.badRequest("INVALID_TIME", "Çıxış vaxtını daxil edin");
        }
        Settings st = settingsOf(m.storeId());
        ZonedDateTime in = req.checkIn().atZoneSameInstant(clock.getZone());
        boolean late = st.workStart() != null
                && in.toLocalTime().isAfter(LocalTime.parse(st.workStart()).plusMinutes(st.graceMinutes()));
        jdbc.sql("""
                UPDATE attendance SET check_in = :i, check_out = :o, work_date = :d, late = :late, note = :note, edited_by = :by
                WHERE id = :id AND store_id = :s""")
                .param("i", req.checkIn()).param("o", req.checkOut()).param("d", in.toLocalDate()).param("late", late)
                .param("note", req.note() == null || req.note().isBlank() ? null : req.note().trim())
                .param("by", m.userId()).param("id", id).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "ATTENDANCE_EDITED", "ATTENDANCE", id.toString(),
                cur.fullName() + ": " + cur.checkIn() + " / " + cur.checkOut() + " -> " + req.checkIn() + " / " + req.checkOut());
        return load(m.storeId(), id);
    }

    public Settings settings() {
        return settingsOf(tenant.requireBusinessMember().storeId());
    }

    @Transactional
    public Settings updateSettings(Settings req) {
        CurrentMember m = tenant.requireOwner();
        LocalTime start = null;
        if (req.workStart() != null && !req.workStart().isBlank()) {
            try {
                start = LocalTime.parse(req.workStart().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest("INVALID_TIME", "İş başlama saatı SS:dd formatında olmalıdır (məs. 09:00)");
            }
        }
        if (req.graceMinutes() < 0 || req.graceMinutes() > 180) {
            throw ApiException.badRequest("INVALID_GRACE", "Güzəşt 0–180 dəqiqə olmalıdır");
        }
        jdbc.sql("UPDATE stores SET work_start_time = :w, late_grace_minutes = :g WHERE id = :s")
                .param("w", start).param("g", req.graceMinutes()).param("s", m.storeId()).update();
        return settingsOf(m.storeId());
    }

    private Settings settingsOf(UUID storeId) {
        return jdbc.sql("SELECT work_start_time, late_grace_minutes FROM stores WHERE id = :s").param("s", storeId)
                .query((rs, n) -> {
                    LocalTime t = rs.getObject("work_start_time", LocalTime.class);
                    return new Settings(t == null ? null : t.toString(), rs.getInt("late_grace_minutes"));
                }).single();
    }

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : tenant.today();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        if (f.isAfter(t)) throw ApiException.badRequest("INVALID_RANGE", "Başlanğıc tarixi sondan sonra ola bilməz");
        if (f.plusDays(370).isBefore(t)) throw ApiException.badRequest("INVALID_RANGE", "Ən çox 1 illik dövr seçin");
        return new LocalDate[]{f, t};
    }

    private Record load(UUID storeId, UUID id) {
        return jdbc.sql(SELECT + " WHERE a.store_id = :s AND a.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("Qeyd tapılmadı"));
    }

    private static String note(Note req) {
        return req == null || req.note() == null || req.note().isBlank() ? null : req.note().trim();
    }

    private static final String SELECT = """
            SELECT a.*, u.full_name FROM attendance a JOIN users u ON u.id = a.user_id""";

    private Record map(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime in = rs.getObject("check_in", OffsetDateTime.class);
        OffsetDateTime out = rs.getObject("check_out", OffsetDateTime.class);
        long minutes = Duration.between(in, out != null ? out : OffsetDateTime.now(clock)).toMinutes();
        return new Record(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"),
                rs.getObject("work_date", LocalDate.class), in, out, Math.max(minutes, 0), rs.getBoolean("late"),
                rs.getString("note"), rs.getObject("edited_by") != null, out == null);
    }
}
