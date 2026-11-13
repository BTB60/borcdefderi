package az.borcdefteri.audit;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Store owner: who did what (audit log) and per-employee totals of debts written and payments received. */
@Service
public class ActivityService {

    public record Entry(long id, OffsetDateTime at, UUID actorId, String actorName, String action, String entityType,
                        String entityId, String details) {}

    public record PerEmployee(UUID userId, String fullName, String role, long debtEntries, BigDecimal debtAmount,
                              long paymentsReceived, BigDecimal paymentsAmount, long actions) {}

    public record Activity(LocalDate from, LocalDate to, List<PerEmployee> employees, List<Entry> entries) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public ActivityService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public Activity activity(LocalDate from, LocalDate to, UUID actorId, int limit) {
        CurrentMember m = tenant.requireOwner();
        LocalDate today = tenant.today();
        LocalDate lo = from != null ? from : today;
        LocalDate hi = to != null ? to : today;
        if (lo.isAfter(hi)) {
            throw ApiException.badRequest("INVALID_RANGE", "Başlanğıc tarix son tarixdən sonra ola bilməz");
        }
        List<PerEmployee> employees = jdbc.sql("""
                SELECT u.id, u.full_name, sm.role,
                  (SELECT COUNT(*) FROM financial_transactions t WHERE t.store_id = :s AND t.actor_id = u.id
                     AND t.transaction_type IN ('DEBT_CREATED','DEBT_INCREASED')
                     AND (t.created_at AT TIME ZONE 'Asia/Baku')::date BETWEEN :lo AND :hi) AS debt_entries,
                  (SELECT COALESCE(SUM(t.amount),0) FROM financial_transactions t WHERE t.store_id = :s AND t.actor_id = u.id
                     AND t.transaction_type IN ('DEBT_CREATED','DEBT_INCREASED')
                     AND (t.created_at AT TIME ZONE 'Asia/Baku')::date BETWEEN :lo AND :hi) AS debt_amount,
                  (SELECT COUNT(*) FROM payments p WHERE p.store_id = :s AND p.recorded_by = u.id AND p.voided_at IS NULL
                     AND (p.paid_at AT TIME ZONE 'Asia/Baku')::date BETWEEN :lo AND :hi) AS payments,
                  (SELECT COALESCE(SUM(p.amount),0) FROM payments p WHERE p.store_id = :s AND p.recorded_by = u.id AND p.voided_at IS NULL
                     AND (p.paid_at AT TIME ZONE 'Asia/Baku')::date BETWEEN :lo AND :hi) AS payments_amount,
                  (SELECT COUNT(*) FROM audit_logs a WHERE a.store_id = :s AND a.actor_id = u.id
                     AND (a.created_at AT TIME ZONE 'Asia/Baku')::date BETWEEN :lo AND :hi) AS actions
                FROM store_members sm JOIN users u ON u.id = sm.user_id
                WHERE sm.store_id = :s ORDER BY sm.role DESC, u.full_name
                """).param("s", m.storeId()).param("lo", lo).param("hi", hi)
                .query((rs, n) -> new PerEmployee(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("role"), rs.getLong("debt_entries"), Money.of(rs.getBigDecimal("debt_amount")),
                        rs.getLong("payments"), Money.of(rs.getBigDecimal("payments_amount")), rs.getLong("actions")))
                .list();
        int safe = Math.min(Math.max(limit, 1), 500);
        String actorFilter = actorId != null ? " AND a.actor_id = :actor" : "";
        var spec = jdbc.sql("""
                SELECT a.id, a.created_at, a.actor_id, u.full_name, a.action, a.entity_type, a.entity_id, a.details
                FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_id
                WHERE a.store_id = :s AND (a.created_at AT TIME ZONE 'Asia/Baku')::date BETWEEN :lo AND :hi""" + actorFilter
                + " ORDER BY a.created_at DESC, a.id DESC LIMIT " + safe)
                .param("s", m.storeId()).param("lo", lo).param("hi", hi);
        if (actorId != null) {
            spec = spec.param("actor", actorId);
        }
        List<Entry> entries = spec.query((rs, n) -> new Entry(rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("actor_id", UUID.class), rs.getString("full_name"), rs.getString("action"),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("details"))).list();
        return new Activity(lo, hi, employees, entries);
    }
}
