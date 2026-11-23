package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kuryer pulu təhvili, tədarükçü sifarişi → mal qəbulu, işçi maaşı, bonus kartı in the customer portal. */
class HandoverOrdersPayrollTest extends IntegrationTestBase {

    private double cashBalance(String token) throws Exception {
        double sum = 0;
        for (JsonNode a : body(get(token, "/api/cash/accounts"))) if ("CASH".equals(a.get("kind").asText())) sum += a.get("balance").asDouble();
        return sum;
    }

    private String product(String token, String name) throws Exception {
        return body(post(token, "/api/products", new HashMap<>(Map.of("name", name, "purchasePrice", "10", "salePrice", "15"))))
                .get("id").asText();
    }

    @Test
    void theCouriersCollectedCashGoesIntoTheTillOnce() throws Exception {
        String owner = register("Təhvil Mağaza");
        activateSubscription(owner, "MONTHLY");
        String d1 = body(post(owner, "/api/deliveries", Map.of("recipientName", "A", "recipientPhone", "0501112233", "address", "X",
                "collectAmount", "40.00", "courierName", "Taksi Elvin"))).get("id").asText();
        String d2 = body(post(owner, "/api/deliveries", Map.of("recipientName", "B", "recipientPhone", "0501112234", "address", "Y",
                "collectAmount", "25.00", "courierName", "Taksi Elvin"))).get("id").asText();
        post(owner, "/api/deliveries/" + d1 + "/deliver", Map.of("collectedAmount", "40.00")).andExpect(status().isOk());
        post(owner, "/api/deliveries/" + d2 + "/deliver", Map.of("collectedAmount", "20.00")).andExpect(status().isOk());

        get(owner, "/api/deliveries/courier-cash").andExpect(jsonPath("$[0].courierName", is("Taksi Elvin")))
                .andExpect(jsonPath("$[0].amount", is(60.0))).andExpect(jsonPath("$[0].deliveries", is(2)));
        double before = cashBalance(owner);
        post(owner, "/api/deliveries/handover", Map.of("courierName", "Taksi Elvin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(60.0)));
        assertEquals(before + 60.0, cashBalance(owner), 0.001);
        get(owner, "/api/deliveries/courier-cash").andExpect(jsonPath("$.length()", is(0)));
        post(owner, "/api/deliveries/handover", Map.of("courierName", "Taksi Elvin")).andExpect(status().isConflict());
        get(owner, "/api/deliveries/" + d1).andExpect(jsonPath("$.handedOverAt").exists());
    }

    @Test
    void anOrderIsFilledByItsGoodsReceiptsAndShowsWhatDidNotCome() throws Exception {
        String owner = register("Sifariş Mağaza");
        activateSubscription(owner, "MONTHLY");
        String un = product(owner, "Un");
        String duz = product(owner, "Duz");
        UUID supplier = createCompany(owner, "Topdan MMC");
        JsonNode o = body(post(owner, "/api/purchase-orders", Map.of("companyId", supplier.toString(), "items",
                List.of(Map.of("productId", un, "quantity", "20"), Map.of("productId", duz, "quantity", "5")))).andExpect(status().isCreated()));
        String id = o.get("id").asText();
        assertEquals("OPEN", o.get("status").asText());
        get(owner, "/api/purchase-orders").andExpect(jsonPath("$.length()", is(1)));

        Map<String, Object> rec = new HashMap<>();
        rec.put("companyId", supplier.toString());
        rec.put("purchaseOrderId", id);
        rec.put("paid", "0");
        rec.put("items", List.of(Map.of("productId", un, "quantity", "20", "unitCost", "10")));
        post(owner, "/api/purchases", rec).andExpect(status().isCreated());
        get(owner, "/api/purchase-orders/" + id).andExpect(jsonPath("$.status", is("PARTIAL")))
                .andExpect(jsonPath("$.missingLines", is(1)));
        rec.put("items", List.of(Map.of("productId", duz, "quantity", "5", "unitCost", "10")));
        post(owner, "/api/purchases", rec).andExpect(status().isCreated());
        get(owner, "/api/purchase-orders/" + id).andExpect(jsonPath("$.status", is("RECEIVED")));
        get(owner, "/api/purchase-orders").andExpect(jsonPath("$.length()", is(0)));
        post(owner, "/api/purchases", rec).andExpect(status().isConflict()); // closed
        post(owner, "/api/purchase-orders/" + id + "/cancel", Map.of()).andExpect(status().isConflict());
    }

    @Test
    void payrollTakesHoursLatenessAdvancesAndPayments() throws Exception {
        String owner = register("Maaş Mağaza");
        activateSubscription(owner, "MONTHLY");
        String email = uniqueEmail("seller");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Satıcı Aysel",
                "permissions", perms("SALES"))).andExpect(status().isCreated());
        String seller = login(email, EMPLOYEE_PASSWORD);
        UUID sellerId = jdbcClient.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(UUID.class).single();
        put(owner, "/api/settings/attendance", Map.of("workStart", "09:00", "graceMinutes", 10)).andExpect(status().isOk());

        // one day, 09:00-17:00 on time; one late day, 10:00-14:00
        LocalDate first = YearMonth.now(ZoneId.of("Asia/Baku")).atDay(1);
        ZoneId baku = ZoneId.of("Asia/Baku");
        clock.setDate(first);
        String a1 = body(post(seller, "/api/attendance/check-in", Map.of())).get("id").asText();
        post(seller, "/api/attendance/check-out", Map.of()).andExpect(status().isOk());
        put(owner, "/api/attendance/" + a1, Map.of("checkIn", first.atTime(9, 0).atZone(baku).toOffsetDateTime().toString(),
                "checkOut", first.atTime(17, 0).atZone(baku).toOffsetDateTime().toString())).andExpect(status().isOk());
        String a2 = body(post(seller, "/api/attendance/check-in", Map.of())).get("id").asText();
        post(seller, "/api/attendance/check-out", Map.of()).andExpect(status().isOk());
        LocalDate second = first.plusDays(1);
        put(owner, "/api/attendance/" + a2, Map.of("checkIn", second.atTime(10, 0).atZone(baku).toOffsetDateTime().toString(),
                "checkOut", second.atTime(14, 0).atZone(baku).toOffsetDateTime().toString())).andExpect(status().isOk());
        clock.reset();

        get(seller, "/api/payroll").andExpect(status().isForbidden());
        put(owner, "/api/payroll/" + sellerId + "/settings", Map.of("payType", "HOURLY", "rate", "5.00", "latePenalty", "2.00"))
                .andExpect(status().isOk());
        String month = YearMonth.from(first).toString();
        JsonNode row = body(get(owner, "/api/payroll?month=" + month)).get("rows").get(0);
        assertEquals(12 * 60, row.get("minutes").asLong());
        assertEquals(60.0, row.get("gross").asDouble(), "12 hours × 5");
        assertEquals(1, row.get("lateDays").asLong());
        assertEquals(58.0, row.get("toPay").asDouble());

        // money goes out of the till as an "İşçi maaşı" expense
        String acc = body(post(owner, "/api/cash/accounts", Map.of("name", "Kassa", "kind", "CASH", "isDefault", true))).get("id").asText();
        post(owner, "/api/cash/deposit", Map.of("accountId", acc, "amount", "100.00")).andExpect(status().isCreated());
        post(owner, "/api/payroll/" + sellerId + "/advance", Map.of("amount", "20.00", "month", month)).andExpect(status().isOk());
        JsonNode sheet = body(post(owner, "/api/payroll/" + sellerId + "/pay", Map.of("amount", "38.00", "month", month))
                .andExpect(status().isOk()));
        JsonNode r = sheet.get("rows").get(0);
        assertEquals(20.0, r.get("advances").asDouble());
        assertEquals(38.0, r.get("paid").asDouble());
        assertEquals(0.0, r.get("toPay").asDouble());
        assertEquals(42.0, cashBalance(owner), 0.001);
        get(owner, "/api/payroll/" + sellerId + "/entries?month=" + month).andExpect(jsonPath("$.length()", is(2)));

        put(owner, "/api/payroll/" + sellerId + "/settings", Map.of("payType", "MONTHLY", "rate", "600", "latePenalty", "0"))
                .andExpect(jsonPath("$.rows[0].gross", is(600.0)));
        get(owner, "/api/payroll?month=2026-13").andExpect(status().isBadRequest());
    }

    @Test
    void thePortalShowsTheBonusCard() throws Exception {
        String owner = register("Bonus Mağaza");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Kamran", "+994501231231");
        post(owner, "/api/customers/" + c + "/bonus", Map.of("amount", "7.50", "note", "hədiyyə")).andExpect(status().isOk());
        String url = body(post(owner, "/api/customers/" + c + "/portal-links", Map.of("days", 30))).get("url").asText();
        String token = url.substring(url.lastIndexOf('/') + 1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/portal/" + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bonusBalance", is(7.5)))
                .andExpect(jsonPath("$.customerCode").exists());
    }
}
