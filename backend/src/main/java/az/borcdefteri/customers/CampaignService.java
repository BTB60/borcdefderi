package az.borcdefteri.customers;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Toplu WhatsApp mesajı and ad günü: who to write to (all customers with a phone, those without debt, those with
 * debt, those who have not bought for a while) and whose birthday is coming. The messages themselves are opened in
 * WhatsApp one by one on the phone / computer — nothing is sent from the server.
 */
@Service
@RestController
public class CampaignService {

    public record Recipient(UUID id, String name, String phone, BigDecimal openBalance, LocalDate lastPurchase,
                            LocalDate birthday, BigDecimal bonusBalance) {}

    public record Birthday(UUID id, String name, String phone, LocalDate birthday, long daysUntil) {}

    public record BirthdayUpdate(LocalDate birthday) {}

    public record SentLog(@Min(1) @Max(5000) int count, @NotBlank @Size(max = 1000) String text) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public CampaignService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    private CurrentMember requireCustomers() {
        CurrentMember m = tenant.requireMember();
        if (!m.has("CUSTOMER_DEBTS") && !m.has("SALES")) {
            throw ApiException.forbidden("FORBIDDEN", "Bu bölmə üçün icazəniz yoxdur");
        }
        return m;
    }

    /** filter: ALL, NO_DEBT, HAS_DEBT, INACTIVE (no purchase for {@code days} days), BONUS (has bonus). */
    @GetMapping("/api/campaign/recipients")
    public List<Recipient> recipients(@RequestParam(defaultValue = "ALL") String filter, @RequestParam(defaultValue = "60") int days) {
        CurrentMember m = requireCustomers();
        if (days < 7 || days > 730) throw ApiException.badRequest("INVALID_DAYS", "Gün sayı 7-730 olmalıdır");
        LocalDate since = tenant.today().minusDays(days);
        List<Recipient> all = jdbc.sql("""
                SELECT c.id, c.name, c.phone, c.birthday,
                       (SELECT COALESCE(SUM(d.remaining_amount), 0) FROM debts d WHERE d.customer_id = c.id AND d.status <> 'PAID') AS owes,
                       (SELECT MAX(s.sold_on) FROM sales s WHERE s.customer_id = c.id AND s.status = 'COMPLETED') AS last_sale,
                       (SELECT COALESCE(SUM(b.amount), 0) FROM customer_bonus_entries b WHERE b.customer_id = c.id) AS bonus
                FROM customers c WHERE c.store_id = :s AND c.phone IS NOT NULL AND c.phone <> ''
                ORDER BY c.name
                """).param("s", m.storeId())
                .query((rs, n) -> new Recipient(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone"),
                        Money.of(rs.getBigDecimal("owes")), rs.getObject("last_sale", LocalDate.class),
                        rs.getObject("birthday", LocalDate.class), Money.of(rs.getBigDecimal("bonus")))).list();
        return switch (filter) {
            case "ALL" -> all;
            case "NO_DEBT" -> all.stream().filter(r -> r.openBalance().signum() == 0).toList();
            case "HAS_DEBT" -> all.stream().filter(r -> r.openBalance().signum() > 0).toList();
            case "INACTIVE" -> all.stream().filter(r -> r.lastPurchase() == null || r.lastPurchase().isBefore(since)).toList();
            case "BONUS" -> all.stream().filter(r -> r.bonusBalance().signum() > 0).toList();
            default -> throw ApiException.badRequest("INVALID_FILTER", "Filtr: ALL, NO_DEBT, HAS_DEBT, INACTIVE, BONUS");
        };
    }

    /** Birthdays today and within the next {@code days} days. */
    @GetMapping("/api/campaign/birthdays")
    public List<Birthday> birthdays(@RequestParam(defaultValue = "7") int days) {
        CurrentMember m = requireCustomers();
        return upcoming(m.storeId(), Math.max(0, Math.min(days, 60)));
    }

    public List<Birthday> upcoming(UUID storeId, int days) {
        LocalDate today = tenant.today();
        return jdbc.sql("SELECT id, name, phone, birthday FROM customers WHERE store_id = :s AND birthday IS NOT NULL")
                .param("s", storeId)
                .query((rs, n) -> {
                    LocalDate b = rs.getObject("birthday", LocalDate.class);
                    MonthDay md = MonthDay.from(b);
                    LocalDate next = md.isValidYear(today.getYear()) ? md.atYear(today.getYear()) : LocalDate.of(today.getYear(), 3, 1);
                    if (next.isBefore(today)) next = md.isValidYear(today.getYear() + 1) ? md.atYear(today.getYear() + 1) : LocalDate.of(today.getYear() + 1, 3, 1);
                    return new Birthday(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone"), b,
                            ChronoUnit.DAYS.between(today, next));
                }).list().stream()
                .filter(b -> b.daysUntil() <= days)
                .sorted(Comparator.comparingLong(Birthday::daysUntil))
                .toList();
    }

    @PutMapping("/api/customers/{id}/birthday")
    @Transactional
    public BirthdayUpdate setBirthday(@PathVariable UUID id, @RequestBody BirthdayUpdate in) {
        CurrentMember m = requireCustomers();
        if (in.birthday() != null && (in.birthday().isAfter(tenant.today()) || in.birthday().getYear() < 1900)) {
            throw ApiException.badRequest("INVALID_DATE", "Doğum tarixi düzgün deyil");
        }
        int n = jdbc.sql("UPDATE customers SET birthday = :b WHERE id = :id AND store_id = :s")
                .param("b", in.birthday()).param("id", id).param("s", m.storeId()).update();
        if (n == 0) throw ApiException.notFound("Müştəri tapılmadı");
        return in;
    }

    /** Remembers that a campaign was sent (for the activity log). */
    @PostMapping("/api/campaign/sent")
    public void sent(@Valid @RequestBody SentLog in) {
        CurrentMember m = requireCustomers();
        audit.log(m.storeId(), m.userId(), "CAMPAIGN_SENT", "CUSTOMER", "whatsapp",
                in.count() + " müştəriyə WhatsApp mesajı: " + (in.text().length() > 200 ? in.text().substring(0, 200) + "…" : in.text()));
    }
}
