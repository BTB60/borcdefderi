package az.borcdefteri.delivery;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Çatdırılma və kuryer. Sales staff create a delivery (from a sale or by hand) and give it to a courier: an employee
 * with job title COURIER, or an outside courier by name and phone. Status: NEW -> ASSIGNED -> ON_THE_WAY -> DELIVERED,
 * or CANCELLED. A courier employee without the SALES permission sees and moves only their own deliveries.
 * The customer follows the delivery by a public link (track token).
 */
@Service
public class DeliveryService {

    static final String PERM_SALES = "SALES";

    public record Delivery(UUID id, String number, String status, UUID saleId, String saleNumber, BigDecimal saleTotal,
                           UUID customerId, String recipientName, String recipientPhone, String address, String note,
                           UUID courierUserId, String courierName, String courierPhone, BigDecimal fee,
                           BigDecimal collectAmount, BigDecimal collectedAmount, OffsetDateTime scheduledFor,
                           OffsetDateTime createdAt, OffsetDateTime assignedAt, OffsetDateTime pickedUpAt,
                           OffsetDateTime deliveredAt, OffsetDateTime cancelledAt, String cancelReason,
                           String trackToken, String createdByName,
                           /** When the courier handed the collected cash in (null = still with the courier). */
                           OffsetDateTime handedOverAt) {}

    /** Cash a courier still holds: delivered, collected at the door, not handed in yet. */
    public record CourierCash(UUID courierUserId, String courierName, long deliveries, BigDecimal amount) {}

    /** Kuryer pulu təhvil verdi: one courier (an employee by id, or an outside one by name). */
    public record HandoverRequest(UUID courierUserId, @jakarta.validation.constraints.Size(max = 150) String courierName) {}

    public record HandoverResult(BigDecimal amount, long deliveries, UUID cashTransactionId) {}

    public record CreateRequest(
            UUID saleId,
            UUID customerId,
            @jakarta.validation.constraints.Size(max = 150) String recipientName,
            @jakarta.validation.constraints.Size(max = 30) String recipientPhone,
            @jakarta.validation.constraints.NotBlank(message = "Ünvanı daxil edin")
            @jakarta.validation.constraints.Size(max = 500) String address,
            @jakarta.validation.constraints.Size(max = 500) String note,
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal fee,
            /** Cash the courier takes from the customer at the door. */
            @jakarta.validation.constraints.DecimalMin("0.00")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal collectAmount,
            OffsetDateTime scheduledFor,
            /** Optional: give it to a courier right away (same fields as {@link AssignRequest}). */
            UUID courierUserId,
            @jakarta.validation.constraints.Size(max = 150) String courierName,
            @jakarta.validation.constraints.Size(max = 30) String courierPhone) {}

    public record AssignRequest(UUID courierUserId,
                                @jakarta.validation.constraints.Size(max = 150) String courierName,
                                @jakarta.validation.constraints.Size(max = 30) String courierPhone) {}

    public record DeliverRequest(@jakarta.validation.constraints.DecimalMin("0.00")
                                 @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) BigDecimal collectedAmount) {}

    public record CancelRequest(@jakarta.validation.constraints.Size(max = 300) String reason) {}

    public record Courier(UUID userId, String fullName, String phone, long activeDeliveries) {}

    public record CourierSummary(UUID courierUserId, String courierName, long delivered, long active, long cancelled,
                                 BigDecimal fees, BigDecimal collected) {}

    /** What the customer sees on the tracking page. */
    public record Tracking(String number, String status, String storeName, String storePhone, String courierName,
                           String courierPhone, String address, OffsetDateTime createdAt, OffsetDateTime assignedAt,
                           OffsetDateTime pickedUpAt, OffsetDateTime deliveredAt, OffsetDateTime cancelledAt,
                           BigDecimal collectAmount) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final AuditService audit;
    private final az.borcdefteri.cash.CashService cash;

    public DeliveryService(JdbcClient jdbc, TenantService tenant, AuditService audit, az.borcdefteri.cash.CashService cash) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.audit = audit;
        this.cash = cash;
    }

    /** Sales staff, or a member who has deliveries assigned / is a courier. */
    private CurrentMember requireAccess() {
        CurrentMember m = tenant.requireBusinessMember();
        if (m.lockedModules().contains(PERM_SALES)) {
            throw ApiException.forbidden("PLAN_MODULE", "Bu bölmə paketinizə daxil deyil. Paketi yüksəldin");
        }
        return m;
    }

    private CurrentMember requireManager() {
        return tenant.requirePermission(PERM_SALES);
    }

    // ---------- list ----------

    /** {@code status}: ACTIVE (not finished, the default), ALL, or one status. {@code mine}: only the caller's. */
    public List<Delivery> list(String status, boolean mine, LocalDate from, LocalDate to) {
        CurrentMember m = requireAccess();
        boolean own = mine || !m.has(PERM_SALES);
        String st = status == null || status.isBlank() ? "ACTIVE" : status.toUpperCase(Locale.ROOT);
        StringBuilder where = new StringBuilder(" WHERE d.store_id = :s");
        if ("ACTIVE".equals(st)) where.append(" AND d.status IN ('NEW', 'ASSIGNED', 'ON_THE_WAY')");
        else if (!"ALL".equals(st)) where.append(" AND d.status = :st");
        if (own) where.append(" AND d.courier_user_id = :u");
        if (from != null) where.append(" AND d.created_at >= :f");
        if (to != null) where.append(" AND d.created_at < :t");
        var spec = jdbc.sql(SELECT + where + " ORDER BY CASE d.status WHEN 'ON_THE_WAY' THEN 0 WHEN 'ASSIGNED' THEN 1 WHEN 'NEW' THEN 2 ELSE 3 END,"
                + " COALESCE(d.scheduled_for, d.created_at) DESC LIMIT 500").param("s", m.storeId());
        if (!"ACTIVE".equals(st) && !"ALL".equals(st)) spec = spec.param("st", st);
        if (own) spec = spec.param("u", m.userId());
        ZoneId zone = ZoneId.of("Asia/Baku");
        if (from != null) spec = spec.param("f", from.atStartOfDay(zone).toOffsetDateTime());
        if (to != null) spec = spec.param("t", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        return spec.query((rs, n) -> map(rs)).list();
    }

    public Delivery get(UUID id) {
        CurrentMember m = requireAccess();
        Delivery d = load(m.storeId(), id);
        checkOwn(m, d);
        return d;
    }

    /** Employees with the job title Kuryer, with how many deliveries each has open. */
    public List<Courier> couriers() {
        CurrentMember m = requireManager();
        return jdbc.sql("""
                SELECT u.id, u.full_name, u.phone,
                       (SELECT COUNT(*) FROM deliveries d WHERE d.store_id = :s AND d.courier_user_id = u.id
                          AND d.status IN ('ASSIGNED', 'ON_THE_WAY')) AS active
                FROM store_members sm JOIN users u ON u.id = sm.user_id
                WHERE sm.store_id = :s AND sm.active AND sm.job_title = 'COURIER' AND u.enabled
                ORDER BY u.full_name""").param("s", m.storeId())
                .query((rs, n) -> new Courier(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("phone"),
                        rs.getLong("active"))).list();
    }

    /** Per courier: delivered / active / cancelled, delivery fees and the cash they collected, for a period. */
    public List<CourierSummary> summary(LocalDate from, LocalDate to) {
        CurrentMember m = requireManager();
        LocalDate t = to != null ? to : tenant.today();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        ZoneId zone = ZoneId.of("Asia/Baku");
        return jdbc.sql("""
                SELECT d.courier_user_id, COALESCE(u.full_name, d.courier_name) AS name,
                       COUNT(*) FILTER (WHERE d.status = 'DELIVERED') AS delivered,
                       COUNT(*) FILTER (WHERE d.status IN ('ASSIGNED', 'ON_THE_WAY')) AS active,
                       COUNT(*) FILTER (WHERE d.status = 'CANCELLED') AS cancelled,
                       COALESCE(SUM(d.fee) FILTER (WHERE d.status = 'DELIVERED'), 0) AS fees,
                       COALESCE(SUM(d.collected_amount) FILTER (WHERE d.status = 'DELIVERED'), 0) AS collected
                FROM deliveries d LEFT JOIN users u ON u.id = d.courier_user_id
                WHERE d.store_id = :s AND (d.courier_user_id IS NOT NULL OR d.courier_name IS NOT NULL)
                  AND d.created_at >= :f AND d.created_at < :t
                GROUP BY d.courier_user_id, COALESCE(u.full_name, d.courier_name)
                ORDER BY delivered DESC, name""")
                .param("s", m.storeId()).param("f", f.atStartOfDay(zone).toOffsetDateTime())
                .param("t", t.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
                .query((rs, n) -> new CourierSummary(rs.getObject("courier_user_id", UUID.class), rs.getString("name"),
                        rs.getLong("delivered"), rs.getLong("active"), rs.getLong("cancelled"),
                        Money.of(rs.getBigDecimal("fees")), Money.of(rs.getBigDecimal("collected")))).list();
    }

    // ---------- courier cash ----------

    /** Per courier: the cash taken at the door and not yet handed in (all time, not only this month). */
    public List<CourierCash> courierCash() {
        CurrentMember m = requireManager();
        return jdbc.sql("""
                SELECT d.courier_user_id, COALESCE(u.full_name, d.courier_name) AS name, COUNT(*) AS n,
                       COALESCE(SUM(d.collected_amount), 0) AS amount
                FROM deliveries d LEFT JOIN users u ON u.id = d.courier_user_id
                WHERE d.store_id = :s AND d.status = 'DELIVERED' AND d.handed_over_at IS NULL AND d.collected_amount > 0
                GROUP BY d.courier_user_id, COALESCE(u.full_name, d.courier_name)
                ORDER BY amount DESC""").param("s", m.storeId())
                .query((rs, n) -> new CourierCash(rs.getObject("courier_user_id", UUID.class), rs.getString("name"),
                        rs.getLong("n"), Money.of(rs.getBigDecimal("amount")))).list();
    }

    /**
     * Kuryer pulu təhvil verdi: all the cash a courier still holds goes into the store's cash desk (one IN entry) and
     * those deliveries are marked handed in.
     */
    @Transactional
    public HandoverResult handOver(HandoverRequest req) {
        CurrentMember m = requireManager();
        if (!m.has("CASH")) throw ApiException.forbidden("PERMISSION_DENIED", "Pulu kassaya yazmaq üçün “Kassa” icazəsi lazımdır");
        String name = blank(req.courierName());
        if (req.courierUserId() == null && name == null) throw ApiException.badRequest("COURIER_REQUIRED", "Kuryeri seçin");
        String who = req.courierUserId() != null ? "d.courier_user_id = :c" : "d.courier_user_id IS NULL AND d.courier_name = :c";
        List<UUID> ids = jdbc.sql("SELECT d.id FROM deliveries d WHERE d.store_id = :s AND " + who
                        + " AND d.status = 'DELIVERED' AND d.handed_over_at IS NULL AND d.collected_amount > 0 FOR UPDATE")
                .param("s", m.storeId()).param("c", req.courierUserId() != null ? req.courierUserId() : name).query(UUID.class).list();
        if (ids.isEmpty()) throw ApiException.conflict("NOTHING_TO_HAND_OVER", "Bu kuryerdə təhvil veriləcək pul yoxdur");
        BigDecimal amount = Money.of(jdbc.sql("SELECT COALESCE(SUM(collected_amount), 0) FROM deliveries WHERE id IN (:ids)")
                .param("ids", ids).query(BigDecimal.class).single());
        String courier = req.courierUserId() != null
                ? jdbc.sql("SELECT full_name FROM users WHERE id = :u").param("u", req.courierUserId()).query(String.class).optional().orElse("Kuryer")
                : name;
        UUID account = cash.accountFor(m.storeId(), "CASH", null, m.userId());
        UUID tx = cash.post(m.storeId(), account, "IN", amount, "COURIER_HANDOVER", tenant.today(), null, null,
                "Kuryer pulu: " + courier + " (" + ids.size() + " çatdırılma)", m.userId());
        jdbc.sql("UPDATE deliveries SET handed_over_at = now(), handed_over_by = :by WHERE id IN (:ids)")
                .param("by", m.userId()).param("ids", ids).update();
        audit.log(m.storeId(), m.userId(), "COURIER_CASH_HANDED_OVER", "DELIVERY", null, courier + ": " + amount + " AZN, " + ids.size() + " çatdırılma");
        return new HandoverResult(amount, ids.size(), tx);
    }

    // ---------- create / move ----------

    @Transactional
    public Delivery create(CreateRequest req) {
        CurrentMember m = requireManager();
        String name = blank(req.recipientName());
        String phone = blank(req.recipientPhone());
        UUID customerId = req.customerId();
        if (req.saleId() != null) {
            UUID saleCustomer = jdbc.sql("SELECT customer_id FROM sales WHERE id = :id AND store_id = :s AND status = 'COMPLETED'")
                    .param("id", req.saleId()).param("s", m.storeId())
                    .query((rs, n) -> java.util.Optional.ofNullable(rs.getObject("customer_id", UUID.class))).optional()
                    .orElseThrow(() -> ApiException.notFound("Satış tapılmadı")).orElse(null);
            if (customerId == null) customerId = saleCustomer;
        }
        if (customerId != null) {
            String[] c = jdbc.sql("SELECT name, phone FROM customers WHERE id = :id AND store_id = :s")
                    .param("id", customerId).param("s", m.storeId())
                    .query((rs, n) -> new String[]{rs.getString("name"), rs.getString("phone")}).optional()
                    .orElseThrow(() -> ApiException.notFound("Müştəri tapılmadı"));
            if (name == null) name = c[0];
            if (phone == null) phone = blank(c[1]);
        }
        if (name == null) throw ApiException.badRequest("RECIPIENT_REQUIRED", "Alıcının adını daxil edin");
        if (phone == null) throw ApiException.badRequest("PHONE_REQUIRED", "Alıcının telefonunu daxil edin");
        UUID id = UUID.randomUUID();
        String number = "CT-" + String.format("%06d", jdbc.sql("SELECT nextval('delivery_number_seq')").query(Long.class).single());
        jdbc.sql("""
                INSERT INTO deliveries (id, store_id, number, sale_id, customer_id, recipient_name, recipient_phone, address, note,
                                        fee, collect_amount, scheduled_for, track_token, created_by)
                VALUES (:id, :s, :no, :sale, :c, :n, :p, :a, :note, :fee, :col, :sch, :tok, :by)""")
                .param("id", id).param("s", m.storeId()).param("no", number).param("sale", req.saleId()).param("c", customerId)
                .param("n", name).param("p", phone).param("a", req.address().trim()).param("note", blank(req.note()))
                .param("fee", Money.of(req.fee() == null ? BigDecimal.ZERO : req.fee()))
                .param("col", Money.of(req.collectAmount() == null ? BigDecimal.ZERO : req.collectAmount()))
                .param("sch", req.scheduledFor()).param("tok", token()).param("by", m.userId()).update();
        audit.log(m.storeId(), m.userId(), "DELIVERY_CREATED", "DELIVERY", id.toString(), number + " " + name + ", " + req.address());
        if (req.courierUserId() != null || blank(req.courierName()) != null) {
            return assignInternal(m, id, new AssignRequest(req.courierUserId(), req.courierName(), req.courierPhone()));
        }
        return load(m.storeId(), id);
    }

    @Transactional
    public Delivery assign(UUID id, AssignRequest req) {
        return assignInternal(requireManager(), id, req);
    }

    private Delivery assignInternal(CurrentMember m, UUID id, AssignRequest req) {
        Delivery d = lock(m.storeId(), id);
        if (!List.of("NEW", "ASSIGNED", "ON_THE_WAY").contains(d.status())) {
            throw ApiException.conflict("DELIVERY_CLOSED", "Bu çatdırılma artıq bağlanıb");
        }
        UUID courier = req.courierUserId();
        String name = blank(req.courierName());
        String phone = blank(req.courierPhone());
        if (courier != null) {
            boolean member = jdbc.sql("SELECT COUNT(*) FROM store_members WHERE store_id = :s AND user_id = :u AND active")
                    .param("s", m.storeId()).param("u", courier).query(Long.class).single() > 0;
            if (!member) throw ApiException.notFound("Kuryer tapılmadı");
            name = null;
            phone = null;
        } else if (name == null) {
            throw ApiException.badRequest("COURIER_REQUIRED", "Kuryeri seçin və ya adını yazın");
        }
        jdbc.sql("""
                UPDATE deliveries SET courier_user_id = :u, courier_name = :n, courier_phone = :p,
                       status = CASE WHEN status = 'NEW' THEN 'ASSIGNED' ELSE status END, assigned_at = now()
                WHERE id = :id""").param("u", courier).param("n", name).param("p", phone).param("id", id).update();
        Delivery after = load(m.storeId(), id);
        audit.log(m.storeId(), m.userId(), "DELIVERY_ASSIGNED", "DELIVERY", id.toString(), after.number() + " → " + after.courierName());
        return after;
    }

    /** The courier took the parcel: Yoldadır. */
    @Transactional
    public Delivery pickUp(UUID id) {
        CurrentMember m = requireAccess();
        Delivery d = lock(m.storeId(), id);
        checkOwn(m, d);
        if (!"ASSIGNED".equals(d.status())) {
            throw ApiException.conflict("INVALID_STATUS", "NEW".equals(d.status()) ? "Əvvəlcə kuryer təyin edin" : "Bu çatdırılma artıq yoldadır və ya bağlanıb");
        }
        jdbc.sql("UPDATE deliveries SET status = 'ON_THE_WAY', picked_up_at = now() WHERE id = :id").param("id", id).update();
        audit.log(m.storeId(), m.userId(), "DELIVERY_ON_THE_WAY", "DELIVERY", id.toString(), d.number());
        return load(m.storeId(), id);
    }

    /** Çatdırıldı, with the cash the courier took at the door (defaults to the amount to collect). */
    @Transactional
    public Delivery deliver(UUID id, DeliverRequest req) {
        CurrentMember m = requireAccess();
        Delivery d = lock(m.storeId(), id);
        checkOwn(m, d);
        if (!List.of("ASSIGNED", "ON_THE_WAY").contains(d.status())) {
            throw ApiException.conflict("INVALID_STATUS", "NEW".equals(d.status()) ? "Əvvəlcə kuryer təyin edin" : "Bu çatdırılma artıq bağlanıb");
        }
        BigDecimal collected = Money.of(req == null || req.collectedAmount() == null ? d.collectAmount() : req.collectedAmount());
        jdbc.sql("""
                UPDATE deliveries SET status = 'DELIVERED', delivered_at = now(), picked_up_at = COALESCE(picked_up_at, now()),
                       collected_amount = :c WHERE id = :id""").param("c", collected).param("id", id).update();
        audit.log(m.storeId(), m.userId(), "DELIVERY_DELIVERED", "DELIVERY", id.toString(), d.number() + ", alınan " + collected + " AZN");
        return load(m.storeId(), id);
    }

    @Transactional
    public Delivery cancel(UUID id, CancelRequest req) {
        CurrentMember m = requireManager();
        Delivery d = lock(m.storeId(), id);
        if (List.of("DELIVERED", "CANCELLED").contains(d.status())) {
            throw ApiException.conflict("DELIVERY_CLOSED", "Bu çatdırılma artıq bağlanıb");
        }
        jdbc.sql("UPDATE deliveries SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = :r WHERE id = :id")
                .param("r", req == null ? null : blank(req.reason())).param("id", id).update();
        audit.log(m.storeId(), m.userId(), "DELIVERY_CANCELLED", "DELIVERY", id.toString(), d.number());
        return load(m.storeId(), id);
    }

    // ---------- public tracking ----------

    public Tracking track(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw ApiException.notFound("Çatdırılma tapılmadı");
        return jdbc.sql("""
                SELECT d.*, s.name AS store_name, s.phone AS store_phone, COALESCE(u.full_name, d.courier_name) AS courier,
                       COALESCE(u.phone, d.courier_phone) AS courier_ph
                FROM deliveries d JOIN stores s ON s.id = d.store_id LEFT JOIN users u ON u.id = d.courier_user_id
                WHERE d.track_token = :t""").param("t", token)
                .query((rs, n) -> {
                    String status = rs.getString("status");
                    boolean moving = "ON_THE_WAY".equals(status);
                    return new Tracking(rs.getString("number"), status, rs.getString("store_name"), rs.getString("store_phone"),
                            // an employee by first name only; an outside courier as the store wrote them ("Taksi Elvin")
                            rs.getObject("courier_user_id") != null ? firstName(rs.getString("courier")) : rs.getString("courier"),
                            moving ? rs.getString("courier_ph") : null, rs.getString("address"),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("assigned_at", OffsetDateTime.class),
                            rs.getObject("picked_up_at", OffsetDateTime.class), rs.getObject("delivered_at", OffsetDateTime.class),
                            rs.getObject("cancelled_at", OffsetDateTime.class), Money.of(rs.getBigDecimal("collect_amount")));
                }).optional().orElseThrow(() -> ApiException.notFound("Çatdırılma tapılmadı"));
    }

    // ---------- helpers ----------

    private void checkOwn(CurrentMember m, Delivery d) {
        if (!m.has(PERM_SALES) && !m.userId().equals(d.courierUserId())) throw ApiException.notFound("Çatdırılma tapılmadı");
    }

    private Delivery lock(UUID storeId, UUID id) {
        jdbc.sql("SELECT id FROM deliveries WHERE id = :id AND store_id = :s FOR UPDATE").param("id", id).param("s", storeId)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Çatdırılma tapılmadı"));
        return load(storeId, id);
    }

    private Delivery load(UUID storeId, UUID id) {
        return jdbc.sql(SELECT + " WHERE d.store_id = :s AND d.id = :id").param("s", storeId).param("id", id)
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("Çatdırılma tapılmadı"));
    }

    private static String firstName(String full) {
        if (full == null || full.isBlank()) return null;
        return full.trim().split("\\s+")[0];
    }

    private static String token() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final String SELECT = """
            SELECT d.*, sa.number AS sale_number, sa.total AS sale_total, cu.full_name AS courier_user_name, cu.phone AS courier_user_phone,
                   cb.full_name AS created_by_name
            FROM deliveries d
            LEFT JOIN sales sa ON sa.id = d.sale_id
            LEFT JOIN users cu ON cu.id = d.courier_user_id
            JOIN users cb ON cb.id = d.created_by""";

    private static Delivery map(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID courier = rs.getObject("courier_user_id", UUID.class);
        BigDecimal collected = rs.getBigDecimal("collected_amount");
        BigDecimal saleTotal = rs.getBigDecimal("sale_total");
        return new Delivery(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("status"),
                rs.getObject("sale_id", UUID.class), rs.getString("sale_number"), saleTotal == null ? null : Money.of(saleTotal),
                rs.getObject("customer_id", UUID.class), rs.getString("recipient_name"), rs.getString("recipient_phone"),
                rs.getString("address"), rs.getString("note"), courier,
                courier != null ? rs.getString("courier_user_name") : rs.getString("courier_name"),
                courier != null ? rs.getString("courier_user_phone") : rs.getString("courier_phone"),
                Money.of(rs.getBigDecimal("fee")), Money.of(rs.getBigDecimal("collect_amount")),
                collected == null ? null : Money.of(collected), rs.getObject("scheduled_for", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("assigned_at", OffsetDateTime.class),
                rs.getObject("picked_up_at", OffsetDateTime.class), rs.getObject("delivered_at", OffsetDateTime.class),
                rs.getObject("cancelled_at", OffsetDateTime.class), rs.getString("cancel_reason"), rs.getString("track_token"),
                rs.getString("created_by_name"), rs.getObject("handed_over_at", OffsetDateTime.class));
    }
}
