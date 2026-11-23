package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Məhsul variantları, işçi davamiyyəti, çatdırılma və kuryer. */
class VariantAttendanceDeliveryTest extends IntegrationTestBase {

    private UUID userIdOf(String email) {
        return jdbcClient.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(UUID.class).single();
    }

    @Test
    void variantsAreProductsOfTheirOwnThatSellAndKeepStock() throws Exception {
        String owner = register("Ayaqqabı Mağaza");
        activateSubscription(owner, "MONTHLY");
        String model = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Krossovka X", "code", "KRX",
                "purchasePrice", "40", "salePrice", "90"))).andExpect(status().isCreated())).get("id").asText();

        JsonNode vs = body(post(owner, "/api/products/" + model + "/variants",
                Map.of("sizes", List.of("40", "41", " 41 "), "colors", List.of("Qara", "Ağ"))).andExpect(status().isCreated()));
        assertEquals(4, vs.size(), "2 sizes × 2 colours, the duplicate size ignored");
        JsonNode first = vs.get(0);
        assertEquals("40", first.get("variantSize").asText());
        assertEquals(model, first.get("parentId").asText());
        assertEquals(90.0, first.get("salePrice").asDouble());
        assertThat(first.get("name").asText(), startsWith("Krossovka X — 40 / "));
        assertThat(first.get("code").asText(), startsWith("KRX-"));

        // an explicit variant with its own barcode, price and stock; existing combinations are skipped
        vs = body(post(owner, "/api/products/" + model + "/variants", Map.of("items", List.of(
                Map.of("size", "42", "color", "Qara", "barcode", "4700000000421", "salePrice", "95", "initialStock", "3"),
                Map.of("size", "40", "color", "qara")))).andExpect(status().isCreated()));
        assertEquals(5, vs.size());
        get(owner, "/api/products/" + model).andExpect(jsonPath("$.variantCount", is(5)));

        JsonNode v42 = body(get(owner, "/api/products/by-code/4700000000421").andExpect(status().isOk()));
        assertEquals(95.0, v42.get("salePrice").asDouble());
        assertEquals(3.0, v42.get("stockQty").asDouble());
        post(owner, "/api/sales", Map.of("items", List.of(Map.of("productId", v42.get("id").asText(), "quantity", "2")), "paidCash", "190.00"))
                .andExpect(status().isCreated());
        get(owner, "/api/products/" + v42.get("id").asText()).andExpect(jsonPath("$.stockQty", is(1.0)));

        // a variant cannot have variants; a model with variants is only deactivated, not deleted
        post(owner, "/api/products/" + v42.get("id").asText() + "/variants", Map.of("sizes", List.of("43")))
                .andExpect(status().isBadRequest());
        post(owner, "/api/products/" + model + "/variants", Map.of()).andExpect(status().isBadRequest());
        org.springframework.test.web.servlet.MvcResult del = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/products/" + model).header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(200, del.getResponse().getStatus());
        assertThat(del.getResponse().getContentAsString(), containsString("\"deactivated\":true"));
    }

    @Test
    void attendanceTracksArrivalLatenessAndHours() throws Exception {
        String owner = register("Davamiyyət Mağaza");
        activateSubscription(owner, "MONTHLY");
        String email = uniqueEmail("seller");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Satıcı Aysel",
                "permissions", perms("SALES"), "jobTitle", "SELLER")).andExpect(status().isCreated());
        String seller = login(email, EMPLOYEE_PASSWORD);

        put(owner, "/api/settings/attendance", Map.of("workStart", "09:00", "graceMinutes", 10))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workStart", is("09:00")));
        put(seller, "/api/settings/attendance", Map.of("workStart", "10:00", "graceMinutes", 0)).andExpect(status().isForbidden());

        LocalDate day = LocalDate.now(clock).minusDays(1);
        clock.setDate(day); // 12:00 Baku: late for a 09:00 start
        get(seller, "/api/attendance/current").andExpect(status().isNoContent());
        String id = body(post(seller, "/api/attendance/check-in", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.late", is(true))).andExpect(jsonPath("$.open", is(true)))).get("id").asText();
        post(seller, "/api/attendance/check-in", Map.of()).andExpect(status().isConflict());
        post(seller, "/api/attendance/check-out", Map.of("note", "Erkən getdim")).andExpect(status().isOk())
                .andExpect(jsonPath("$.open", is(false)));
        post(seller, "/api/attendance/check-out", Map.of()).andExpect(status().isConflict());

        // the owner corrects the record: 09:05 – 18:05 (within the grace minutes, 9 hours)
        ZoneId baku = ZoneId.of("Asia/Baku");
        put(owner, "/api/attendance/" + id, Map.of("checkIn", day.atTime(9, 5).atZone(baku).toOffsetDateTime().toString(),
                "checkOut", day.atTime(18, 5).atZone(baku).toOffsetDateTime().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.late", is(false))).andExpect(jsonPath("$.minutes", is(540)))
                .andExpect(jsonPath("$.edited", is(true)));
        put(seller, "/api/attendance/" + id, Map.of("checkIn", day.atTime(8, 0).atZone(baku).toOffsetDateTime().toString(),
                "checkOut", day.atTime(20, 0).atZone(baku).toOffsetDateTime().toString())).andExpect(status().isForbidden());

        clock.reset();
        post(owner, "/api/attendance/check-in", Map.of()).andExpect(status().isOk());
        String range = "?from=" + day.withDayOfMonth(1).minusMonths(1) + "&to=" + LocalDate.now(clock);
        get(seller, "/api/attendance" + range).andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].fullName", is("Satıcı Aysel")));
        get(owner, "/api/attendance" + range).andExpect(jsonPath("$.length()", is(2)));
        get(seller, "/api/attendance/summary").andExpect(status().isForbidden());
        JsonNode sum = body(get(owner, "/api/attendance/summary" + range).andExpect(status().isOk()));
        JsonNode aysel = null;
        for (JsonNode p : sum) if ("Satıcı Aysel".equals(p.get("fullName").asText())) aysel = p;
        assertNotNull(aysel);
        assertEquals(1, aysel.get("days").asLong());
        assertEquals(0, aysel.get("lateDays").asLong());
        assertEquals(540, aysel.get("minutes").asLong());
        assertFalse(aysel.get("presentNow").asBoolean());
    }

    @Test
    void aDeliveryGoesToTheCourierAndTheCustomerFollowsIt() throws Exception {
        String owner = register("Çatdırılma Mağaza");
        activateSubscription(owner, "MONTHLY");
        String email = uniqueEmail("courier");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Kuryer Murad",
                "permissions", perms(), "jobTitle", "COURIER")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobTitle", is("COURIER")));
        String courier = login(email, EMPLOYEE_PASSWORD);
        UUID courierId = userIdOf(email);

        UUID customer = createCustomer(owner, "Nərmin Həsənova", "+994501234567");
        String p = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Tort", "purchasePrice", "10",
                "salePrice", "25", "initialStock", "5")))).get("id").asText();
        String sale = body(post(owner, "/api/sales", Map.of("customerId", customer.toString(),
                "items", List.of(Map.of("productId", p, "quantity", "2")), "paidCash", "50.00")).andExpect(status().isCreated()))
                .get("id").asText();

        post(owner, "/api/deliveries", Map.of("address", "")).andExpect(status().isBadRequest());
        JsonNode d = body(post(owner, "/api/deliveries", Map.of("saleId", sale, "address", "Nizami küç. 10, mənzil 5",
                "fee", "3.00", "collectAmount", "0")).andExpect(status().isCreated()));
        String id = d.get("id").asText();
        assertEquals("NEW", d.get("status").asText());
        assertEquals("Nərmin Həsənova", d.get("recipientName").asText(), "recipient comes from the sale's customer");
        assertEquals(50.0, d.get("saleTotal").asDouble());
        String token = d.get("trackToken").asText();

        // the courier sees nothing until it is given to them, and cannot create deliveries
        get(courier, "/api/deliveries").andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(0)));
        get(courier, "/api/deliveries/" + id).andExpect(status().isNotFound());
        post(courier, "/api/deliveries", Map.of("recipientName", "X", "recipientPhone", "1", "address", "Y")).andExpect(status().isForbidden());
        post(owner, "/api/deliveries/" + id + "/pick-up", Map.of()).andExpect(status().isConflict());

        get(owner, "/api/deliveries/couriers").andExpect(jsonPath("$[0].fullName", is("Kuryer Murad")));
        post(owner, "/api/deliveries/" + id + "/assign", Map.of("courierUserId", courierId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ASSIGNED")))
                .andExpect(jsonPath("$.courierName", is("Kuryer Murad")));

        get(courier, "/api/deliveries").andExpect(jsonPath("$.length()", is(1)));
        post(courier, "/api/deliveries/" + id + "/pick-up", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ON_THE_WAY")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/deliveries/" + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ON_THE_WAY")))
                .andExpect(jsonPath("$.courierName", is("Kuryer"))).andExpect(jsonPath("$.storeName", is("Çatdırılma Mağaza")));
        post(courier, "/api/deliveries/" + id + "/cancel", Map.of()).andExpect(status().isForbidden());
        post(courier, "/api/deliveries/" + id + "/deliver", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DELIVERED"))).andExpect(jsonPath("$.collectedAmount", is(0.0)));
        post(owner, "/api/deliveries/" + id + "/cancel", Map.of()).andExpect(status().isConflict());

        // an outside courier, cash on delivery, then cancelled
        String d2 = body(post(owner, "/api/deliveries", Map.of("recipientName", "Rauf", "recipientPhone", "0559998877",
                "address", "Gənclik m.", "collectAmount", "40.00", "courierName", "Taksi Elvin", "courierPhone", "0701112233"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("ASSIGNED")))).get("id").asText();
        post(owner, "/api/deliveries/" + d2 + "/deliver", Map.of("collectedAmount", "35.00")).andExpect(status().isOk());

        get(owner, "/api/deliveries?status=ALL").andExpect(jsonPath("$.length()", is(2)));
        get(owner, "/api/deliveries").andExpect(jsonPath("$.length()", is(0)));
        JsonNode sum = body(get(owner, "/api/deliveries/summary").andExpect(status().isOk()));
        assertEquals(2, sum.size());
        double collected = 0;
        for (JsonNode s : sum) collected += s.get("collected").asDouble();
        assertEquals(35.0, collected);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/deliveries/0000"))
                .andExpect(status().isNotFound());
    }
}
