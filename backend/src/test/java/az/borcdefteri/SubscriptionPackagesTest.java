package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Abunə ol" flow: monthly / 6-month / yearly packages, admin contact, direct admin activation and countdown data. */
class SubscriptionPackagesTest extends IntegrationTestBase {

    @Test
    void onlyMonthlySemiAnnualAndYearlyPackagesAreSold() throws Exception {
        get(null, "/api/public/plans").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", containsInAnyOrder("MONTHLY", "SEMI_ANNUAL", "YEARLY")))
                .andExpect(jsonPath("$[?(@.code=='SEMI_ANNUAL')].durationMonths", contains(6)));
    }

    @Test
    void contactPhoneIsSetByAdminAndPublic() throws Exception {
        put(adminToken(), "/api/admin/settings/contact", Map.of("phone", "+994 50 123 45 67"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whatsapp", is("994501234567")));
        get(null, "/api/public/contact").andExpect(status().isOk())
                .andExpect(jsonPath("$.phone", is("+994 50 123 45 67")));

        String owner = register("Əlaqə Mağazası");
        put(owner, "/api/admin/settings/contact", Map.of("phone", "+994000")).andExpect(status().isForbidden());
        put(adminToken(), "/api/admin/settings/contact", Map.of("phone", "<script>")).andExpect(status().isBadRequest());
    }

    @Test
    void subscribeButtonCreatesPendingRequestAndChangingPackageUpdatesIt() throws Exception {
        String owner = register("Paket Mağazası");
        JsonNode first = body(post(owner, "/api/subscriptions/request", Map.of("planCode", "MONTHLY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.hasReceipt", is(false))));

        post(owner, "/api/subscriptions/request", Map.of("planCode", "YEARLY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(first.get("id").asText())))
                .andExpect(jsonPath("$.planCode", is("YEARLY")));

        get(owner, "/api/subscriptions/history").andExpect(jsonPath("$", hasSize(1)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired());
        post(owner, "/api/subscriptions/request", Map.of("planCode", "START")).andExpect(status().isBadRequest());
    }

    @Test
    void employeesCannotRequestSubscription() throws Exception {
        String owner = register("İşçi Abunə Mağazası");
        activateSubscription(owner, "MONTHLY");
        String email = uniqueEmail("worker");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "İşçi",
                "permissions", perms("CUSTOMER_DEBTS"))).andExpect(status().isCreated());
        String worker = login(email, EMPLOYEE_PASSWORD);
        post(worker, "/api/subscriptions/request", Map.of("planCode", "MONTHLY")).andExpect(status().isForbidden());
    }

    @Test
    void adminActivatesPendingRequestDirectlyWithChosenPackage() throws Exception {
        String owner = register("Birbaşa Aktiv Mağaza");
        post(owner, "/api/subscriptions/request", Map.of("planCode", "MONTHLY")).andExpect(status().isCreated());
        String storeId = storeIdOf(owner);

        // The admin confirms the receipt and activates the 6-month package.
        post(adminToken(), "/api/admin/stores/" + storeId + "/subscription", Map.of("planCode", "SEMI_ANNUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.planCode", is("SEMI_ANNUAL")))
                .andExpect(jsonPath("$.amount", is(99.0)));

        LocalDate today = LocalDate.now(clock);
        LocalDate end = today.plusMonths(6).minusDays(1);
        get(owner, "/api/subscriptions/current")
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.endDate", is(end.toString())));
        JsonNode cur = body(get(owner, "/api/subscriptions/current"));
        assertEquals(end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Baku")).toInstant(),
                OffsetDateTime.parse(cur.get("expiresAt").asText()).toInstant());
        // The pending request was used, not duplicated.
        get(owner, "/api/subscriptions/history").andExpect(jsonPath("$", hasSize(1)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isOk());
    }

    @Test
    void adminActivationWithoutRequestAndRenewalContinuesFromEnd() throws Exception {
        String owner = register("Yenilənən Paket Mağazası");
        String storeId = storeIdOf(owner);
        post(adminToken(), "/api/admin/stores/" + storeId + "/subscription", Map.of("planCode", "MONTHLY"))
                .andExpect(status().isOk());
        LocalDate firstEnd = LocalDate.parse(body(get(owner, "/api/subscriptions/current")).get("endDate").asText());

        post(adminToken(), "/api/admin/stores/" + storeId + "/subscription", Map.of("planCode", "YEARLY"))
                .andExpect(status().isOk());
        LocalDate secondEnd = LocalDate.parse(body(get(owner, "/api/subscriptions/current")).get("endDate").asText());
        assertEquals(firstEnd.plusDays(1).plusMonths(12).minusDays(1), secondEnd);

        post(owner, "/api/admin/stores/" + storeId + "/subscription", Map.of("planCode", "MONTHLY"))
                .andExpect(status().isForbidden());
        post(adminToken(), "/api/admin/stores/" + UUID.randomUUID() + "/subscription", Map.of("planCode", "MONTHLY"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminStoreListShowsCountdownAndFlagsExpiringAndExpiredStores() throws Exception {
        String fresh = register("Sayğac Təzə " + UUID.randomUUID());
        String expiring = register("Sayğac Az Qalan " + UUID.randomUUID());
        String expired = register("Sayğac Bitən " + UUID.randomUUID());
        String requested = register("Sayğac Sorğu " + UUID.randomUUID());
        for (String t : new String[]{fresh, expiring, expired}) {
            post(adminToken(), "/api/admin/stores/" + storeIdOf(t) + "/subscription", Map.of("planCode", "YEARLY"))
                    .andExpect(status().isOk());
        }
        post(requested, "/api/subscriptions/request", Map.of("planCode", "SEMI_ANNUAL")).andExpect(status().isCreated());
        LocalDate today = LocalDate.now(clock);
        setEndDate(storeIdOf(expiring), today.plusDays(3));
        setEndDate(storeIdOf(expired), today.minusDays(1));

        JsonNode stores = body(get(adminToken(), "/api/admin/stores"));
        JsonNode f = find(stores, storeIdOf(fresh));
        JsonNode s = find(stores, storeIdOf(expiring));
        JsonNode x = find(stores, storeIdOf(expired));
        JsonNode r = find(stores, storeIdOf(requested));

        assertEquals("ACTIVE", f.get("subscriptionState").asText());
        assertFalse(f.get("expiring").asBoolean());
        assertTrue(f.get("daysRemaining").asLong() > 300);

        assertEquals("ACTIVE", s.get("subscriptionState").asText());
        assertEquals(3, s.get("daysRemaining").asLong());
        assertTrue(s.get("expiring").asBoolean());

        assertEquals("EXPIRED", x.get("subscriptionState").asText());
        assertTrue(x.get("expiring").asBoolean());

        assertEquals("NONE", r.get("subscriptionState").asText());
        assertEquals("SEMI_ANNUAL", r.get("pendingPlanCode").asText());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private void setEndDate(String storeId, LocalDate end) {
        jdbc.sql("""
                UPDATE subscriptions SET start_date = LEAST(start_date, :end), end_date = :end
                WHERE store_id = :s AND status = 'ACTIVE'
                """)
                .param("end", end).param("s", UUID.fromString(storeId)).update();
    }

    private static JsonNode find(JsonNode stores, String id) {
        for (JsonNode n : stores) {
            if (n.get("id").asText().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("store not listed: " + id);
    }

    private String storeIdOf(String token) throws Exception {
        return body(get(token, "/api/auth/me")).get("storeId").asText();
    }
}
