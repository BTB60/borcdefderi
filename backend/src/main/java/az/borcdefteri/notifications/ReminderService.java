package az.borcdefteri.notifications;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Avtomatik borc xatırlatması. Every morning (and whenever the list is opened) each store gets a reminder for every
 * customer whose debt is due within {@code remind_days_before} days, or is overdue (again every
 * {@code remind_repeat_days} days until paid). The reminder carries a ready message; the store sends it with one tap.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public record Reminder(UUID id, UUID customerId, String customerName, String customerPhone, String kind, BigDecimal amount,
                           LocalDate dueDate, LocalDate remindOn, String status, String channel, OffsetDateTime handledAt,
                           String message) {}

    public record Settings(boolean enabled, int daysBefore, int repeatDays) {}

    public record Handle(String channel) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final NotificationService notifications;
    private final AuditService audit;

    public ReminderService(JdbcClient jdbc, TenantService tenant, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ----- generation -----

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Baku")
    public void daily() {
        List<UUID> stores = jdbc.sql("SELECT id FROM stores WHERE status = 'ACTIVE' AND reminders_enabled").query(UUID.class).list();
        for (UUID s : stores) {
            try {
                int n = generate(s, tenant.today());
                if (n > 0) {
                    notifications.create(s, "DEBT_REMINDERS", "Borc xatırlatmaları",
                            n + " müştəriyə borc xatırlatması göndərilməlidir. “Xatırlatmalar” bölməsindən bir toxunuşla göndərin.");
                }
            } catch (RuntimeException e) {
                log.warn("Reminders for store {} failed: {}", s, e.getMessage());
            }
        }
    }

    /** Creates today's reminders for one store; returns how many are new. Safe to run many times a day. */
    @Transactional
    public int generate(UUID storeId, LocalDate today) {
        Settings st = settingsOf(storeId);
        if (!st.enabled()) return 0;
        record Due(UUID customerId, BigDecimal overdue, LocalDate firstOverdue, BigDecimal soon, LocalDate firstSoon) {}
        List<Due> dues = jdbc.sql("""
                SELECT d.customer_id,
                       COALESCE(SUM(d.remaining_amount) FILTER (WHERE d.due_date < :today), 0) AS overdue,
                       MIN(d.due_date) FILTER (WHERE d.due_date < :today) AS first_overdue,
                       COALESCE(SUM(d.remaining_amount) FILTER (WHERE d.due_date BETWEEN :today AND :soon), 0) AS soon,
                       MIN(d.due_date) FILTER (WHERE d.due_date BETWEEN :today AND :soon) AS first_soon
                FROM debts d
                WHERE d.store_id = :s AND d.debt_type = 'CUSTOMER_DEBT' AND d.remaining_amount > 0 AND d.due_date IS NOT NULL
                  AND d.cancelled_at IS NULL
                GROUP BY d.customer_id
                """).param("s", storeId).param("today", today).param("soon", today.plusDays(st.daysBefore()))
                .query((rs, n) -> new Due(rs.getObject("customer_id", UUID.class), rs.getBigDecimal("overdue"),
                        rs.getObject("first_overdue", LocalDate.class), rs.getBigDecimal("soon"),
                        rs.getObject("first_soon", LocalDate.class))).list();
        int created = 0;
        for (Due d : dues) {
            boolean overdue = d.overdue().signum() > 0;
            if (!overdue && d.soon().signum() <= 0) continue;
            // one reminder per customer per repeat window (an overdue one), or per due date (a "due soon" one)
            LocalDate since = overdue ? today.minusDays(st.repeatDays() - 1L) : d.firstSoon().minusDays(st.daysBefore());
            String kind = overdue ? "OVERDUE" : "DUE_SOON";
            boolean recent = jdbc.sql("""
                    SELECT COUNT(*) FROM debt_reminders WHERE customer_id = :c AND kind = :k AND remind_on >= :since""")
                    .param("c", d.customerId()).param("k", kind).param("since", since).query(Long.class).single() > 0;
            if (recent) continue;
            BigDecimal amount = Money.of(overdue ? d.overdue().add(d.soon()) : d.soon());
            created += jdbc.sql("""
                    INSERT INTO debt_reminders (id, store_id, customer_id, kind, amount, due_date, remind_on)
                    VALUES (:id, :s, :c, :k, :a, :due, :day) ON CONFLICT (customer_id, remind_on) DO NOTHING
                    """).param("id", UUID.randomUUID()).param("s", storeId).param("c", d.customerId()).param("k", kind)
                    .param("a", amount).param("due", overdue ? d.firstOverdue() : d.firstSoon()).param("day", today).update();
        }
        return created;
    }

    // ----- list / handle -----

    public List<Reminder> list(String status) {
        CurrentMember m = requireAccess();
        generate(m.storeId(), tenant.today());
        String st = status == null || status.isBlank() ? "PENDING" : status.toUpperCase(Locale.ROOT);
        String[] store = jdbc.sql("SELECT name, COALESCE(phone, '') FROM stores WHERE id = :s").param("s", m.storeId())
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        LocalDate today = tenant.today();
        return jdbc.sql("""
                SELECT r.*, c.name AS customer_name, c.phone AS customer_phone,
                       (SELECT COALESCE(SUM(d.remaining_amount), 0) FROM debts d
                        WHERE d.customer_id = r.customer_id AND d.debt_type = 'CUSTOMER_DEBT') AS open_now
                FROM debt_reminders r JOIN customers c ON c.id = r.customer_id
                WHERE r.store_id = :s AND (:all OR r.status = :st)
                ORDER BY r.remind_on DESC, r.amount DESC LIMIT 300
                """).param("s", m.storeId()).param("all", "ALL".equals(st)).param("st", st)
                .query((rs, n) -> {
                    String kind = rs.getString("kind");
                    LocalDate due = rs.getObject("due_date", LocalDate.class);
                    BigDecimal amount = Money.of(rs.getBigDecimal("amount"));
                    BigDecimal open = Money.of(rs.getBigDecimal("open_now"));
                    return new Reminder(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                            rs.getString("customer_name"), rs.getString("customer_phone"), kind, amount, due,
                            rs.getObject("remind_on", LocalDate.class), rs.getString("status"), rs.getString("channel"),
                            rs.getObject("handled_at", OffsetDateTime.class),
                            message(rs.getString("customer_name"), store[0], store[1], kind, open.signum() > 0 ? open : amount, due, today));
                }).list();
    }

    static String message(String customer, String store, String storePhone, String kind, BigDecimal amount, LocalDate due,
                          LocalDate today) {
        String sum = amount.stripTrailingZeros().toPlainString() + " AZN";
        String body = "OVERDUE".equals(kind)
                ? "“" + store + "” mağazasına " + sum + " borcunuzun son ödəmə tarixi (" + DAY.format(due) + ") keçib."
                : "“" + store + "” mağazasına " + sum + " borcunuzun son ödəmə tarixi "
                + (due.equals(today) ? "bu gündür" : DAY.format(due) + "-dir") + ".";
        return "Hörmətli " + customer + ", " + body + " Zəhmət olmasa ödənişi edin."
                + (storePhone.isBlank() ? "" : " Əlaqə: " + storePhone) + " Təşəkkür edirik!";
    }

    @Transactional
    public void markSent(UUID id, Handle req) {
        String channel = req == null || req.channel() == null ? "OTHER" : req.channel().toUpperCase(Locale.ROOT);
        if (!List.of("WHATSAPP", "SMS", "CALL", "OTHER").contains(channel)) channel = "OTHER";
        handle(id, "SENT", channel);
    }

    @Transactional
    public void skip(UUID id) {
        handle(id, "SKIPPED", null);
    }

    private void handle(UUID id, String status, String channel) {
        CurrentMember m = requireAccess();
        UUID customer = jdbc.sql("SELECT customer_id FROM debt_reminders WHERE id = :id AND store_id = :s")
                .param("id", id).param("s", m.storeId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Xatırlatma tapılmadı"));
        jdbc.sql("UPDATE debt_reminders SET status = :st, channel = :ch, handled_by = :by, handled_at = now() WHERE id = :id")
                .param("st", status).param("ch", channel).param("by", m.userId()).param("id", id).update();
        if ("SENT".equals(status)) {
            jdbc.sql("UPDATE customers SET last_contact_at = now() WHERE id = :c").param("c", customer).update();
            audit.log(m.storeId(), m.userId(), "REMINDER_SENT", "CUSTOMER", customer.toString(), channel);
        }
    }

    // ----- settings -----

    public Settings settings() {
        return settingsOf(requireAccess().storeId());
    }

    @Transactional
    public Settings updateSettings(Settings s) {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.isOwner()) throw ApiException.forbidden("OWNER_ONLY", "Xatırlatma parametrlərini yalnız mağaza sahibi dəyişə bilər");
        if (s.daysBefore() < 0 || s.daysBefore() > 30) throw ApiException.badRequest("INVALID_DAYS", "Neçə gün əvvəl: 0-30");
        if (s.repeatDays() < 1 || s.repeatDays() > 60) throw ApiException.badRequest("INVALID_DAYS", "Təkrar: 1-60 gün");
        jdbc.sql("UPDATE stores SET reminders_enabled = :e, remind_days_before = :b, remind_repeat_days = :r WHERE id = :s")
                .param("e", s.enabled()).param("b", s.daysBefore()).param("r", s.repeatDays()).param("s", m.storeId()).update();
        return settingsOf(m.storeId());
    }

    private Settings settingsOf(UUID storeId) {
        return jdbc.sql("SELECT reminders_enabled, remind_days_before, remind_repeat_days FROM stores WHERE id = :s")
                .param("s", storeId)
                .query((rs, n) -> new Settings(rs.getBoolean(1), rs.getInt(2), rs.getInt(3))).single();
    }

    private CurrentMember requireAccess() {
        return tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
    }
}
