package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Free trial, promo codes, announcements, the monthly revenue report and store activity. */
class TrialPromoTest extends IntegrationTestBase {

    @Test
    void newStoreGetsTheFreeTrialWhenSwitchedOn() throws Exception {
        put(adminToken(), "/api/admin/trial", Map.of("days", 14)).andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(14));
        String owner = register("Sınaq Mağaza");
        get(owner, "/api/subscriptions/current").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.trial").value(true))
                .andExpect(jsonPath("$.daysRemaining").value(13));
        // the store can work during the trial
        createCustomer(owner, "Müştəri", "+994501234567");

        put(adminToken(), "/api/admin/trial", Map.of("days", 0)).andExpect(status().isOk());
        String other = register("Sınaqsız Mağaza");
        get(other, "/api/subscriptions/current").andExpect(jsonPath("$.status").value("NONE"));
    }

    @Test
    void promoCodeLowersThePriceAndCanBeUsedOncePerStore() throws Exception {
        String code = "YAZ" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        post(adminToken(), "/api/admin/promo-codes", Map.of("code", code.toLowerCase(), "percentOff", 50, "maxUses", 5))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(code));
        post(adminToken(), "/api/admin/promo-codes", Map.of("code", code, "percentOff", 10))
                .andExpect(status().isConflict());
        post(adminToken(), "/api/admin/promo-codes", Map.of("code", "IKISI" + code, "percentOff", 10, "amountOff", 2))
                .andExpect(status().isBadRequest());

        String owner = register("Promo Mağaza");
        post(owner, "/api/subscriptions/promo-check", Map.of("code", code, "planCode", "MONTHLY"))
                .andExpect(status().isOk());
        JsonNode check = body(post(owner, "/api/subscriptions/promo-check", Map.of("code", code, "planCode", "MONTHLY")));
        java.math.BigDecimal half = check.get("price").decimalValue().divide(java.math.BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
        assertThat(check.get("total").decimalValue()).isEqualByComparingTo(check.get("price").decimalValue().subtract(half));
        post(owner, "/api/subscriptions/promo-check", Map.of("code", "YOXDUR123", "planCode", "MONTHLY"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("PROMO_INVALID"));

        Map<String, Object> req = new HashMap<>(Map.of("planCode", "MONTHLY", "promoCode", code));
        JsonNode sub = body(post(owner, "/api/subscriptions/request", req).andExpect(status().isCreated()));
        assertThat(sub.get("amount").decimalValue()).isEqualByComparingTo(check.get("total").decimalValue());
        assertThat(sub.get("promoCode").asText()).isEqualTo(code);
        post(adminToken(), "/api/admin/subscriptions/" + sub.get("id").asText() + "/approve", Map.of())
                .andExpect(status().isOk());

        // used once by this store
        post(owner, "/api/subscriptions/request", req)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("PROMO_ALREADY_USED"));
        JsonNode list = body(get(adminToken(), "/api/admin/promo-codes"));
        for (JsonNode p : list) {
            if (p.get("code").asText().equals(code)) assertThat(p.get("usedCount").asLong()).isEqualTo(1);
        }
        // switched off codes do not work
        String id = body(post(adminToken(), "/api/admin/promo-codes", Map.of("code", "OFF" + code, "amountOff", 5)))
                .get("id").asText();
        put(adminToken(), "/api/admin/promo-codes/" + id, Map.of("amountOff", 5, "active", false)).andExpect(status().isOk());
        String other = register("Promo 2");
        post(other, "/api/subscriptions/promo-check", Map.of("code", "OFF" + code, "planCode", "MONTHLY"))
                .andExpect(status().isBadRequest());

        // stores cannot manage promo codes
        get(owner, "/api/admin/promo-codes").andExpect(status().isForbidden());
    }

    @Test
    void announcementsReachEveryStore() throws Exception {
        String owner = register("Elan Mağaza");
        String title = "Yeniləmə " + UUID.randomUUID().toString().substring(0, 5);
        JsonNode a = body(post(adminToken(), "/api/admin/announcements",
                Map.of("title", title, "body", "Sabah 02:00-da qısa texniki iş olacaq", "level", "WARNING"))
                .andExpect(status().isCreated()));
        JsonNode current = body(get(owner, "/api/announcements").andExpect(status().isOk()));
        assertThat(current.findValuesAsText("title")).contains(title);
        JsonNode notes = body(get(owner, "/api/notifications"));
        assertThat(notes.toString()).contains(title);

        put(adminToken(), "/api/admin/announcements/" + a.get("id").asText(),
                Map.of("title", title, "body", "x", "active", false)).andExpect(status().isOk());
        assertThat(body(get(owner, "/api/announcements")).findValuesAsText("title")).doesNotContain(title);
        post(owner, "/api/admin/announcements", Map.of("title", "t", "body", "b")).andExpect(status().isForbidden());
    }

    @Test
    void revenueReportAndStoreActivity() throws Exception {
        String owner = register("Aktivlik Mağaza");
        activateSubscription(owner, "MONTHLY");
        JsonNode rev = body(get(adminToken(), "/api/admin/revenue").andExpect(status().isOk()));
        JsonNode monthly = rev.get("monthly");
        assertThat(monthly.size()).isEqualTo(12);
        JsonNode last = monthly.get(11);
        assertThat(last.get("revenue").decimalValue()).isPositive();
        assertThat(last.get("payingStores").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(last.has("churnRate")).isTrue();

        JsonNode stores = body(get(adminToken(), "/api/admin/stores?q=Aktivlik Mağaza"));
        JsonNode row = stores.get(0);
        assertThat(row.get("salesThisMonth").asLong()).isZero();
        assertThat(row.get("lastActivityAt").isNull()).isFalse();
        assertThat(row.get("idle").asBoolean()).isFalse(); // new store, active today
        assertThat(row.get("onTrial").asBoolean()).isFalse();
    }
}
