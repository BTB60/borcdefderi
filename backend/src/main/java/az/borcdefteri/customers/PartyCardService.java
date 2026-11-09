package az.borcdefteri.customers;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.customers.PartyService.Party;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer card extras: combined history, internal notes and "last contact" (WhatsApp / call). */
@Service
public class PartyCardService {

    /**
     * One step of the party's account, oldest first. {@code balanceAfter} is the party's remaining balance in that
     * section (customer debt, company receivable or company payable) right after the step.
     */
    public record HistoryEntry(UUID id, UUID debtId, DebtType debtType, String type, BigDecimal amount,
                               BigDecimal balanceAfter, UUID paymentId, String paymentMethod, LocalDate entryDate,
                               String receiptNumber, String note, String actorName, OffsetDateTime createdAt) {}

    public record Note(UUID id, String body, String authorName, OffsetDateTime createdAt) {}

    public record NoteRequest(
            @jakarta.validation.constraints.NotBlank(message = "Qeyd boş ola bilməz")
            @jakarta.validation.constraints.Size(max = 1000, message = "Qeyd ən çox 1000 simvol ola bilər") String body) {}

    public record ContactRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "WHATSAPP|CALL", message = "Kanal yalnız WHATSAPP və ya CALL ola bilər")
            String channel) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PartyService parties;
    private final AuditService audit;

    public PartyCardService(JdbcClient jdbc, TenantService tenant, PartyService parties, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.parties = parties;
        this.audit = audit;
    }

    public List<HistoryEntry> history(boolean customer, UUID partyId) {
        CurrentMember m = tenant.requireBusinessMember();
        if (customer) {
            parties.customer(m, partyId);
        } else {
            parties.company(m, partyId);
        }
        return history(m.storeId(), customer, partyId, allowedTypes(m, customer));
    }

    /** History of a party for a known store (also used by the statement PDF and the customer portal). */
    public List<HistoryEntry> history(UUID storeId, boolean customer, UUID partyId, List<String> types) {
        if (types.isEmpty()) {
            return List.of();
        }
        String column = customer ? "customer_id" : "company_id";
        record Row(UUID id, UUID debtId, DebtType debtType, String type, BigDecimal amount, UUID paymentId,
                   String method, LocalDate entryDate, String receipt, String note, String actor, OffsetDateTime at) {}
        List<Row> rows = jdbc.sql("""
                SELECT t.id, t.debt_id, d.debt_type, t.transaction_type, t.amount, t.payment_id, p.payment_method,
                       COALESCE(t.entry_date, CASE WHEN t.transaction_type = 'DEBT_CREATED' THEN d.debt_date END,
                                (p.paid_at AT TIME ZONE 'Asia/Baku')::date, (t.created_at AT TIME ZONE 'Asia/Baku')::date) AS entry_date,
                       t.receipt_number,
                       COALESCE(t.note, CASE WHEN t.transaction_type = 'DEBT_CREATED' THEN d.description END, p.description) AS note,
                       u.full_name AS actor, t.created_at
                FROM financial_transactions t
                JOIN debts d ON d.id = t.debt_id AND d.store_id = t.store_id
                JOIN users u ON u.id = t.actor_id
                LEFT JOIN payments p ON p.id = t.payment_id
                WHERE t.store_id = :s AND d.""" + column + " = :p AND d.debt_type IN (:types) ORDER BY t.created_at, t.id")
                .param("s", storeId).param("p", partyId).param("types", types)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("debt_id", UUID.class),
                        DebtType.valueOf(rs.getString("debt_type")), rs.getString("transaction_type"),
                        Money.of(rs.getBigDecimal("amount")), rs.getObject("payment_id", UUID.class),
                        rs.getString("payment_method"), rs.getObject("entry_date", LocalDate.class),
                        rs.getString("receipt_number"), rs.getString("note"), rs.getString("actor"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        Map<DebtType, BigDecimal> running = new HashMap<>();
        List<HistoryEntry> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            BigDecimal signed = r.type().startsWith("PAYMENT") ? r.amount().negate() : r.amount();
            BigDecimal bal = Money.of(running.getOrDefault(r.debtType(), Money.zero()).add(signed));
            running.put(r.debtType(), bal);
            out.add(new HistoryEntry(r.id(), r.debtId(), r.debtType(), r.type(), r.amount(), bal, r.paymentId(),
                    r.method(), r.entryDate(), r.receipt(), r.note(), r.actor(), r.at()));
        }
        return out;
    }

    public List<Note> notes(UUID customerId) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        parties.customer(m, customerId);
        return jdbc.sql("""
                SELECT n.id, n.body, u.full_name, n.created_at FROM customer_notes n JOIN users u ON u.id = n.author_id
                WHERE n.store_id = :s AND n.customer_id = :c ORDER BY n.created_at DESC LIMIT 200
                """).param("s", m.storeId()).param("c", customerId)
                .query((rs, n) -> new Note(rs.getObject("id", UUID.class), rs.getString("body"), rs.getString("full_name"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public Note addNote(UUID customerId, NoteRequest req) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        parties.customer(m, customerId);
        String body = req.body().trim();
        if (body.isEmpty()) {
            throw ApiException.badRequest("EMPTY_NOTE", "Qeyd boş ola bilməz");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer_notes (id, store_id, customer_id, body, author_id) VALUES (:id, :s, :c, :b, :a)")
                .param("id", id).param("s", m.storeId()).param("c", customerId).param("b", body).param("a", m.userId())
                .update();
        audit.log(m.storeId(), m.userId(), "CUSTOMER_NOTE_ADDED", "CUSTOMER", customerId.toString(), null);
        return notes(customerId).stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    /** Called when staff press WhatsApp or "Zəng et": records the time; it does not mean a message was delivered. */
    @Transactional
    public Party contacted(UUID customerId, ContactRequest req) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        parties.customer(m, customerId);
        jdbc.sql("UPDATE customers SET last_contact_at = now() WHERE id = :c AND store_id = :s")
                .param("c", customerId).param("s", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "CUSTOMER_CONTACT_" + req.channel(), "CUSTOMER", customerId.toString(), null);
        return parties.customer(m, customerId);
    }

    static List<String> allowedTypes(CurrentMember m, boolean customer) {
        List<String> types = new ArrayList<>();
        for (DebtType t : DebtType.values()) {
            if (t.isCustomer() == customer && m.has(t.permission())) {
                types.add(t.name());
            }
        }
        return types;
    }
}
