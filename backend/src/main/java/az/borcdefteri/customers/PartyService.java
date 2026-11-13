package az.borcdefteri.customers;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customers and companies (parties). Always store-scoped via the caller's membership. */
@Service
public class PartyService {

    /** code: "HP-000001" for customers (null for companies); lastActivityAt: latest debt change or payment. */
    public record Party(UUID id, String name, String phone, String note, OffsetDateTime createdAt,
                        BigDecimal openBalance, long openDebtCount, String code, OffsetDateTime lastContactAt,
                        OffsetDateTime lastActivityAt, long overdueDebts) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final az.borcdefteri.subscriptions.PlanService plans;

    public PartyService(JdbcClient jdbc, TenantService tenant, AuditService audit,
                        az.borcdefteri.subscriptions.PlanService plans) {
        this.plans = plans;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
    }

    // ----- customers -----

    public List<Party> customers(String q) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        return list("customers", "customer_id", m, q, "CUSTOMER_DEBT");
    }

    @Transactional
    public Party createCustomer(PartyRequest req) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        UUID id = UUID.randomUUID();
        insert("customers", id, m, req);
        audit.log(m.storeId(), m.userId(), "CUSTOMER_CREATED", "CUSTOMER", id.toString(), req.name());
        return find("customers", "customer_id", m, id, "CUSTOMER_DEBT");
    }

    @Transactional
    public Party updateCustomer(UUID id, PartyRequest req) {
        CurrentMember m = tenant.requirePermission(TenantService.PERM_CUSTOMER_DEBTS);
        update("customers", m, id, req);
        audit.log(m.storeId(), m.userId(), "CUSTOMER_UPDATED", "CUSTOMER", id.toString(), req.name());
        return find("customers", "customer_id", m, id, "CUSTOMER_DEBT");
    }

    /** One customer of the caller's store (another store's customer is "not found"). */
    public Party customer(CurrentMember m, UUID id) {
        if (!m.has(TenantService.PERM_CUSTOMER_DEBTS)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return find("customers", "customer_id", m, id, "CUSTOMER_DEBT");
    }

    /** One company of the caller's store. */
    public Party company(CurrentMember m, UUID id) {
        if (!m.has(TenantService.PERM_COMPANY_RECEIVABLES) && !m.has(TenantService.PERM_COMPANY_PAYABLES)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return find("companies", "company_id", m, id, null);
    }

    // ----- companies (shared by receivables and payables) -----

    public List<Party> companies(String q) {
        CurrentMember m = requireCompanyAccess();
        return list("companies", "company_id", m, q, null);
    }

    @Transactional
    public Party createCompany(PartyRequest req) {
        CurrentMember m = requireCompanyAccess();
        UUID id = UUID.randomUUID();
        insert("companies", id, m, req);
        audit.log(m.storeId(), m.userId(), "COMPANY_CREATED", "COMPANY", id.toString(), req.name());
        return find("companies", "company_id", m, id, null);
    }

    @Transactional
    public Party updateCompany(UUID id, PartyRequest req) {
        CurrentMember m = requireCompanyAccess();
        update("companies", m, id, req);
        audit.log(m.storeId(), m.userId(), "COMPANY_UPDATED", "COMPANY", id.toString(), req.name());
        return find("companies", "company_id", m, id, null);
    }

    private CurrentMember requireCompanyAccess() {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.has(TenantService.PERM_COMPANY_RECEIVABLES) && !m.has(TenantService.PERM_COMPANY_PAYABLES)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        return m;
    }

    // ----- shared helpers -----

    private List<Party> list(String table, String fkColumn, CurrentMember m, String q, String debtType) {
        String like = az.borcdefteri.common.Search.contains(q);
        String typeFilter = debtType == null ? "" : " AND d.debt_type = '" + debtType + "'";
        boolean customers = "customers".equals(table);
        // table, fkColumn and debtType are constants chosen by this class, never user input
        String code = customers ? "p.code" : "NULL";
        String contact = customers ? "p.last_contact_at" : "CAST(NULL AS timestamptz)";
        return jdbc.sql("SELECT p.id, p.name, p.phone, p.note, p.created_at, " + code + " AS code, " + contact
                + " AS last_contact_at, "
                + """
                       COALESCE(SUM(d.remaining_amount) FILTER (WHERE d.status <> 'PAID'), 0) AS open_balance,
                       COUNT(d.id) FILTER (WHERE d.status <> 'PAID') AS open_count,
                       COUNT(d.id) FILTER (WHERE d.remaining_amount > 0 AND d.due_date < :today) AS overdue_debts,
                       GREATEST(MAX(d.updated_at), (SELECT MAX(pp.paid_at) FROM payments pp JOIN debts d2 ON d2.id = pp.debt_id
                                                      WHERE pp.voided_at IS NULL AND d2.""" + fkColumn + " = p.id)) AS last_activity_at "
                + "FROM " + table + " p LEFT JOIN debts d ON d." + fkColumn + " = p.id AND d.store_id = p.store_id" + typeFilter
                + " WHERE p.store_id = :storeId AND (LOWER(p.name) LIKE LOWER(:q) OR COALESCE(p.phone, '') LIKE :q"
                + (customers ? " OR LOWER(p.code) LIKE LOWER(:q)" : "") + ")"
                + " GROUP BY p.id, p.name, p.phone, p.note, p.created_at" + (customers ? ", p.code, p.last_contact_at" : "")
                + " ORDER BY p.name")
                .param("storeId", m.storeId()).param("q", like).param("today", tenant.today())
                .query((rs, n) -> mapParty(rs))
                .list();
    }

    private Party find(String table, String fkColumn, CurrentMember m, UUID id, String debtType) {
        return list(table, fkColumn, m, null, debtType).stream()
                .filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Qeyd tapılmadı"));
    }

    private void insert(String table, UUID id, CurrentMember m, PartyRequest req) {
        if ("customers".equals(table)) {
            Integer maxCustomers = plans.limitsFor(m.storeId()).maxCustomers();
            if (maxCustomers != null) {
                long current = jdbc.sql("SELECT COUNT(*) FROM customers WHERE store_id = :s")
                        .param("s", m.storeId()).query(Long.class).single();
                if (current >= maxCustomers) {
                    throw ApiException.conflict("PLAN_LIMIT_CUSTOMERS",
                            "Paketinizdə ən çox " + maxCustomers + " müştəri ola bilər. Daha çox müştəri üçün paketi yüksəldin.");
                }
            }
        }
        jdbc.sql("INSERT INTO " + table + " (id, store_id, name, phone, note, created_by) VALUES (:id, :storeId, :name, :phone, :note, :by)")
                .param("id", id).param("storeId", m.storeId())
                .param("name", req.name().trim()).param("phone", blankToNull(req.phone()))
                .param("note", blankToNull(req.note())).param("by", m.userId())
                .update();
    }

    private void update(String table, CurrentMember m, UUID id, PartyRequest req) {
        int updated = jdbc.sql("UPDATE " + table + " SET name = :name, phone = :phone, note = :note WHERE id = :id AND store_id = :storeId")
                .param("name", req.name().trim()).param("phone", blankToNull(req.phone()))
                .param("note", blankToNull(req.note())).param("id", id).param("storeId", m.storeId())
                .update();
        if (updated == 0) {
            throw ApiException.notFound("Qeyd tapılmadı");
        }
    }

    private static Party mapParty(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Party(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class),
                Money.of(rs.getBigDecimal("open_balance")),
                rs.getLong("open_count"),
                rs.getString("code"),
                rs.getObject("last_contact_at", OffsetDateTime.class),
                rs.getObject("last_activity_at", OffsetDateTime.class),
                rs.getLong("overdue_debts"));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
