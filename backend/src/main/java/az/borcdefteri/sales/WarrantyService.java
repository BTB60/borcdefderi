package az.borcdefteri.sales;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Zəmanət yoxla: a customer comes back with a phone — type or scan its IMEI / serial and see when, to whom and
 * for how much it was sold, and whether the warranty still holds.
 */
@Service
@RestController
public class WarrantyService {

    public record Hit(String serial, String productName, UUID saleId, String saleNumber, LocalDate soldOn, String customerName,
                      String customerPhone, BigDecimal price, Integer warrantyMonths, LocalDate warrantyUntil, boolean inWarranty,
                      boolean returned, boolean cancelled, String soldBy) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;

    public WarrantyService(JdbcClient jdbc, TenantService tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @GetMapping("/api/warranty")
    public List<Hit> find(@RequestParam String q) {
        CurrentMember m = tenant.requireBusinessMember();
        if (!m.has("SALES") && !m.has("INVENTORY")) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Bu modul üçün icazəniz yoxdur");
        }
        String s = q == null ? "" : q.trim();
        if (s.length() < 4) throw ApiException.badRequest("QUERY_TOO_SHORT", "Ən azı 4 rəqəm yazın");
        LocalDate today = tenant.today();
        return jdbc.sql("""
                SELECT x.serial, si.name AS product_name, sa.id AS sale_id, sa.number, sa.sold_on, c.name AS customer_name,
                       c.phone AS customer_phone, si.unit_price, p.warranty_months, sa.status, si.returned_qty, si.quantity,
                       u.full_name AS sold_by
                FROM sale_item_serials x
                JOIN sale_items si ON si.id = x.sale_item_id
                JOIN sales sa ON sa.id = x.sale_id
                LEFT JOIN customers c ON c.id = sa.customer_id
                LEFT JOIN products p ON p.id = x.product_id
                JOIN users u ON u.id = sa.created_by
                WHERE x.store_id = :st AND (x.serial = :exact OR x.serial LIKE :tail)
                ORDER BY sa.sold_on DESC LIMIT 20
                """).param("st", m.storeId()).param("exact", s).param("tail", "%" + s.replace("%", "").replace("_", "") )
                .query((rs, n) -> {
                    Integer months = (Integer) rs.getObject("warranty_months", Integer.class);
                    LocalDate sold = rs.getObject("sold_on", LocalDate.class);
                    LocalDate until = months == null ? null : sold.plusMonths(months);
                    boolean cancelled = !"COMPLETED".equals(rs.getString("status"));
                    boolean returned = rs.getBigDecimal("returned_qty").compareTo(rs.getBigDecimal("quantity")) >= 0;
                    return new Hit(rs.getString("serial"), rs.getString("product_name"), rs.getObject("sale_id", UUID.class),
                            rs.getString("number"), sold, rs.getString("customer_name"), rs.getString("customer_phone"),
                            Money.of(rs.getBigDecimal("unit_price")), months, until,
                            until != null && !today.isAfter(until) && !cancelled && !returned, returned, cancelled, rs.getString("sold_by"));
                }).list();
    }
}
