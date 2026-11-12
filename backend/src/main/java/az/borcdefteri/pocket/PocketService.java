package az.borcdefteri.pocket;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * "Cib pulu": the signed-in user's own pocket money. Money added (IN) and spent (OUT) per day; whatever is left
 * carries over to the next day. Private to each user and fully separate from kassa, expenses and profit.
 */
@Service
public class PocketService {

    public record EntryRequest(
            @NotNull(message = "Növ seçilməlidir") @Pattern(regexp = "IN|OUT", message = "Növ düzgün deyil") String kind,
            @NotNull(message = "Məbləğ daxil edilməlidir") @DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
            @Digits(integer = 12, fraction = 2, message = "Məbləğ düzgün deyil") BigDecimal amount,
            @Size(max = 200, message = "Qeyd 200 simvoldan uzun ola bilməz") String note,
            LocalDate date) {}

    public record Entry(UUID id, LocalDate date, String kind, BigDecimal amount, String note, Instant createdAt) {}

    /** One day: what was left from earlier days, what came in and went out that day, and what is left at its end. */
    public record Day(LocalDate date, BigDecimal openingBalance, BigDecimal added, BigDecimal spent, BigDecimal closingBalance,
                      List<Entry> entries) {}

    public record DaySummary(LocalDate date, BigDecimal added, BigDecimal spent, BigDecimal closingBalance) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public PocketService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public Day day(LocalDate date) {
        CurrentMember m = tenant.requireBusinessMember();
        return dayOf(m, date != null ? date : tenant.today());
    }

    /** The last {@code days} days up to today that have entries, newest first, with each day's closing balance. */
    public List<DaySummary> history(int days) {
        CurrentMember m = tenant.requireBusinessMember();
        int n = Math.min(Math.max(days, 1), 92);
        LocalDate today = tenant.today();
        LocalDate first = today.minusDays(n - 1L);
        record Row(LocalDate date, BigDecimal added, BigDecimal spent) {}
        List<Row> rows = jdbc.sql("""
                        SELECT entry_date,
                               COALESCE(SUM(amount) FILTER (WHERE kind = 'IN'), 0)  AS added,
                               COALESCE(SUM(amount) FILTER (WHERE kind = 'OUT'), 0) AS spent
                        FROM pocket_entries
                        WHERE store_id = :s AND user_id = :u AND entry_date BETWEEN :f AND :t
                        GROUP BY entry_date ORDER BY entry_date
                        """)
                .param("s", m.storeId()).param("u", m.userId()).param("f", first).param("t", today)
                .query((rs, i) -> new Row(rs.getObject("entry_date", LocalDate.class), rs.getBigDecimal("added"), rs.getBigDecimal("spent")))
                .list();
        BigDecimal balance = balanceBefore(m, first);
        List<DaySummary> out = new ArrayList<>();
        for (Row r : rows) {
            balance = balance.add(r.added()).subtract(r.spent());
            out.add(new DaySummary(r.date(), Money.of(r.added()), Money.of(r.spent()), Money.of(balance)));
        }
        Collections.reverse(out);
        return out;
    }

    @Transactional
    public Entry add(EntryRequest req) {
        CurrentMember m = tenant.requireBusinessMember();
        LocalDate today = tenant.today();
        LocalDate d = req.date() != null ? req.date() : today;
        if (d.isAfter(today)) throw ApiException.badRequest("INVALID_DATE", "Gələcək tarixə yazmaq olmaz");
        BigDecimal amount = Money.of(req.amount());
        if ("OUT".equals(req.kind())) {
            // Spending cannot exceed what is in the pocket at the end of that day, nor what is left today.
            BigDecimal available = dayOf(m, d).closingBalance().min(dayOf(m, today).closingBalance());
            if (amount.compareTo(available) > 0) {
                throw ApiException.badRequest("POCKET_INSUFFICIENT",
                        "Cibinizdə bu qədər pul yoxdur. Qalıq: " + available.toPlainString() + " ₼");
            }
        }
        UUID id = UUID.randomUUID();
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        jdbc.sql("""
                        INSERT INTO pocket_entries (id, store_id, user_id, entry_date, kind, amount, note)
                        VALUES (:id, :s, :u, :d, :k, :a, :n)
                        """)
                .param("id", id).param("s", m.storeId()).param("u", m.userId()).param("d", d)
                .param("k", req.kind()).param("a", amount).param("n", note)
                .update();
        return new Entry(id, d, req.kind(), amount, note, Instant.now());
    }

    public void delete(UUID id) {
        CurrentMember m = tenant.requireBusinessMember();
        int n = jdbc.sql("DELETE FROM pocket_entries WHERE id = :id AND store_id = :s AND user_id = :u")
                .param("id", id).param("s", m.storeId()).param("u", m.userId()).update();
        if (n == 0) throw ApiException.notFound("Qeyd tapılmadı");
    }

    private Day dayOf(CurrentMember m, LocalDate d) {
        BigDecimal opening = balanceBefore(m, d);
        List<Entry> entries = jdbc.sql("""
                        SELECT id, entry_date, kind, amount, note, created_at FROM pocket_entries
                        WHERE store_id = :s AND user_id = :u AND entry_date = :d
                        ORDER BY created_at DESC, id
                        """)
                .param("s", m.storeId()).param("u", m.userId()).param("d", d)
                .query((rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("entry_date", LocalDate.class),
                        rs.getString("kind"), Money.of(rs.getBigDecimal("amount")), rs.getString("note"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        BigDecimal added = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        for (Entry e : entries) {
            if ("IN".equals(e.kind())) added = added.add(e.amount());
            else spent = spent.add(e.amount());
        }
        return new Day(d, Money.of(opening), Money.of(added), Money.of(spent),
                Money.of(opening.add(added).subtract(spent)), entries);
    }

    private BigDecimal balanceBefore(CurrentMember m, LocalDate d) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN kind = 'IN' THEN amount ELSE -amount END), 0) FROM pocket_entries
                        WHERE store_id = :s AND user_id = :u AND entry_date < :d
                        """)
                .param("s", m.storeId()).param("u", m.userId()).param("d", d)
                .query(BigDecimal.class).single();
    }
}
