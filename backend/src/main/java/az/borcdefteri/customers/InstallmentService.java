package az.borcdefteri.customers;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nisyə hissə-hissə (taksit qrafiki): a customer's debt split into monthly parts with dates. Money the customer
 * pays after the plan starts (any "Borc ödə" / payment on their debts) covers the parts in order, so the plan
 * always shows what is paid, what is due and what is late — with no extra step for the cashier.
 */
@Service
@RestController
public class InstallmentService {

    public record Item(int seq, LocalDate dueDate, BigDecimal amount, BigDecimal paid, String status, long daysLate) {}

    /** status: ACTIVE, COMPLETED. */
    public record Plan(UUID id, UUID customerId, String customerName, String customerPhone, BigDecimal total, BigDecimal paid,
                       BigDecimal left, BigDecimal overdue, String status, OffsetDateTime startedAt, String note,
                       Item next, List<Item> items) {}

    public record PlanRequest(BigDecimal total, Integer count, LocalDate firstDate, Integer everyMonths, String note) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;

    public InstallmentService(JdbcClient jdbc, TenantService tenant, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    private CurrentMember member() {
        return tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
    }

    @GetMapping("/api/customers/{id}/installments")
    public Map<String, Object> forCustomer(@PathVariable UUID id) {
        CurrentMember m = member();
        UUID planId = jdbc.sql("SELECT id FROM installment_plans WHERE store_id = :s AND customer_id = :c AND closed_at IS NULL")
                .param("s", m.storeId()).param("c", id).query(UUID.class).optional().orElse(null);
        java.util.HashMap<String, Object> out = new java.util.HashMap<>();
        out.put("plan", planId == null ? null : load(m.storeId(), planId, tenant.today()));
        out.put("openBalance", openBalance(m.storeId(), id));
        return out;
    }

    /** Makes the schedule (an open plan of the customer is replaced). */
    @PostMapping("/api/customers/{id}/installments")
    @Transactional
    public Plan create(@PathVariable UUID id, @RequestBody PlanRequest req) {
        CurrentMember m = member();
        jdbc.sql("SELECT id FROM customers WHERE id = :c AND store_id = :s FOR UPDATE").param("c", id).param("s", m.storeId())
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Müştəri tapılmadı"));
        BigDecimal open = openBalance(m.storeId(), id);
        BigDecimal total = Money.of(req.total() != null ? req.total() : open);
        if (total.signum() <= 0) throw ApiException.badRequest("NOTHING_TO_SPLIT", "Müştərinin borcu yoxdur — bölüləcək məbləğ yazın");
        if (total.compareTo(open) > 0) {
            throw ApiException.badRequest("TOTAL_TOO_BIG", "Məbləğ müştərinin borcundan (" + open + " AZN) çox ola bilməz");
        }
        int count = req.count() == null ? 0 : req.count();
        if (count < 2 || count > 60) throw ApiException.badRequest("INVALID_COUNT", "Hissə sayı 2 ilə 60 arasında olmalıdır");
        int every = req.everyMonths() == null ? 1 : req.everyMonths();
        if (every < 1 || every > 12) throw ApiException.badRequest("INVALID_PERIOD", "Aralıq 1–12 ay olmalıdır");
        LocalDate first = req.firstDate() != null ? req.firstDate() : tenant.today().plusMonths(1);
        if (first.isBefore(tenant.today())) throw ApiException.badRequest("INVALID_DATE", "İlk ödəniş tarixi keçmişdə ola bilməz");

        jdbc.sql("UPDATE installment_plans SET closed_at = now() WHERE store_id = :s AND customer_id = :c AND closed_at IS NULL")
                .param("s", m.storeId()).param("c", id).update();
        UUID planId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO installment_plans (id, store_id, customer_id, total, note, created_by)
                VALUES (:id, :s, :c, :t, :n, :by)
                """).param("id", planId).param("s", m.storeId()).param("c", id).param("t", total)
                .param("n", req.note() == null || req.note().isBlank() ? null : req.note().trim().substring(0, Math.min(300, req.note().trim().length())))
                .param("by", m.userId()).update();
        // equal parts; the last one takes the qəpik left over
        BigDecimal part = total.divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);
        BigDecimal last = total.subtract(part.multiply(BigDecimal.valueOf(count - 1)));
        for (int i = 1; i <= count; i++) {
            jdbc.sql("INSERT INTO installment_items (plan_id, seq, due_date, amount) VALUES (:p, :i, :d, :a)")
                    .param("p", planId).param("i", i).param("d", first.plusMonths((long) (i - 1) * every))
                    .param("a", i == count ? last : part).update();
        }
        audit.log(m.storeId(), m.userId(), "INSTALLMENT_PLAN_CREATED", "CUSTOMER", id.toString(), total + " AZN, " + count + " hissə");
        return load(m.storeId(), planId, tenant.today());
    }

    @DeleteMapping("/api/customers/{id}/installments")
    public Map<String, Boolean> close(@PathVariable UUID id) {
        CurrentMember m = member();
        int n = jdbc.sql("UPDATE installment_plans SET closed_at = now() WHERE store_id = :s AND customer_id = :c AND closed_at IS NULL")
                .param("s", m.storeId()).param("c", id).update();
        if (n > 0) audit.log(m.storeId(), m.userId(), "INSTALLMENT_PLAN_CLOSED", "CUSTOMER", id.toString(), "Taksit qrafiki bağlandı");
        return Map.of("closed", n > 0);
    }

    /** All open plans of the store, late ones first (Taksitlər page). */
    @GetMapping("/api/installments")
    public List<Plan> all() {
        CurrentMember m = member();
        return plans(m.storeId(), tenant.today());
    }

    public List<Plan> plans(UUID storeId, LocalDate today) {
        List<UUID> ids = jdbc.sql("SELECT id FROM installment_plans WHERE store_id = :s AND closed_at IS NULL")
                .param("s", storeId).query(UUID.class).list();
        List<Plan> out = new ArrayList<>();
        for (UUID id : ids) out.add(load(storeId, id, today));
        out.sort((a, b) -> {
            int c = b.overdue().compareTo(a.overdue());
            if (c != 0) return c;
            LocalDate da = a.next() == null ? LocalDate.MAX : a.next().dueDate();
            LocalDate db = b.next() == null ? LocalDate.MAX : b.next().dueDate();
            return da.compareTo(db);
        });
        return out;
    }

    private BigDecimal openBalance(UUID storeId, UUID customerId) {
        return Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(remaining_amount), 0) FROM debts
                WHERE store_id = :s AND customer_id = :c AND debt_type = 'CUSTOMER_DEBT'
                """).param("s", storeId).param("c", customerId).query(BigDecimal.class).single());
    }

    Plan load(UUID storeId, UUID planId, LocalDate today) {
        record Head(UUID customerId, String name, String phone, BigDecimal total, OffsetDateTime started, String note) {}
        Head h = jdbc.sql("""
                SELECT p.customer_id, c.name, c.phone, p.total, p.started_at, p.note
                FROM installment_plans p JOIN customers c ON c.id = p.customer_id WHERE p.id = :id AND p.store_id = :s
                """).param("id", planId).param("s", storeId)
                .query((rs, n) -> new Head(rs.getObject("customer_id", UUID.class), rs.getString("name"), rs.getString("phone"),
                        rs.getBigDecimal("total"), rs.getObject("started_at", OffsetDateTime.class), rs.getString("note"))).single();
        // money recorded on the customer's debts since the plan started (cancelled payments do not count)
        BigDecimal paidSince = Money.of(jdbc.sql("""
                SELECT COALESCE(SUM(p.amount), 0) FROM payments p JOIN debts d ON d.id = p.debt_id
                WHERE p.store_id = :s AND d.customer_id = :c AND d.debt_type = 'CUSTOMER_DEBT'
                  AND p.voided_at IS NULL AND p.created_at >= :t
                """).param("s", storeId).param("c", h.customerId()).param("t", h.started()).query(BigDecimal.class).single());
        BigDecimal paid = paidSince.min(h.total());
        record Row(int seq, LocalDate due, BigDecimal amount) {}
        List<Row> rows = jdbc.sql("SELECT seq, due_date, amount FROM installment_items WHERE plan_id = :p ORDER BY seq")
                .param("p", planId).query((rs, n) -> new Row(rs.getInt("seq"), rs.getObject("due_date", LocalDate.class),
                        rs.getBigDecimal("amount"))).list();
        List<Item> items = new ArrayList<>();
        BigDecimal before = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        Item next = null;
        for (Row r : rows) {
            BigDecimal covered = paid.subtract(before).max(BigDecimal.ZERO).min(r.amount());
            before = before.add(r.amount());
            String status;
            long late = 0;
            if (covered.compareTo(r.amount()) >= 0) status = "PAID";
            else if (r.due().isBefore(today)) {
                status = "OVERDUE";
                late = ChronoUnit.DAYS.between(r.due(), today);
                overdue = overdue.add(r.amount().subtract(covered));
            } else if (!r.due().isAfter(today.plusDays(3))) status = "DUE_SOON";
            else status = covered.signum() > 0 ? "PARTIAL" : "UPCOMING";
            Item it = new Item(r.seq(), r.due(), Money.of(r.amount()), Money.of(covered), status, late);
            if (next == null && !"PAID".equals(status)) next = it;
            items.add(it);
        }
        BigDecimal left = Money.of(h.total().subtract(paid));
        return new Plan(planId, h.customerId(), h.name(), h.phone(), Money.of(h.total()), Money.of(paid), left, Money.of(overdue),
                left.signum() == 0 ? "COMPLETED" : "ACTIVE", h.started(), h.note(), next, items);
    }
}
