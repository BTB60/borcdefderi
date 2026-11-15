package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer discount (%) and bonus: earned on the paid part, spent as a discount, given back / taken back on cancel. */
class BonusTest extends IntegrationTestBase {

    private String product(String token) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("name", "Köynək", "purchasePrice", "20", "salePrice", "50", "initialStock", "10"));
        return body(post(token, "/api/products", b).andExpect(status().isCreated())).get("id").asText();
    }

    private double balance(String token, UUID c) throws Exception {
        return body(get(token, "/api/customers/" + c + "/bonus")).get("balance").asDouble();
    }

    @Test
    void bonusIsEarnedSpentAndUndoneOnCancel() throws Exception {
        String owner = register("Bonus Mağaza");
        activateSubscription(owner, "MONTHLY");
        String p = product(owner);
        put(owner, "/api/settings/bonus", Map.of("bonusPercent", 5)).andExpect(status().isOk());
        UUID c = createCustomer(owner, "Daimi Müştəri", "+994501280001");
        put(owner, "/api/customers/" + c, Map.of("name", "Daimi Müştəri", "discountPercent", 10)).andExpect(status().isOk())
                .andExpect(jsonPath("$.discountPercent", is(10.0)));

        // 2 x 50 = 100, paid in cash -> 5 bonus
        Map<String, Object> s1 = new HashMap<>(Map.of("items", List.of(Map.of("productId", p, "quantity", "2")),
                "paidCash", "100.00", "customerId", c.toString()));
        post(owner, "/api/sales", s1).andExpect(status().isCreated()).andExpect(jsonPath("$.bonusEarned", is(5.0)));
        assertEquals(5.0, balance(owner, c));

        // spend 5 bonus on a 50 sale: pays 45, earns 2.25
        Map<String, Object> s2 = new HashMap<>(Map.of("items", List.of(Map.of("productId", p, "quantity", "1")),
                "paidCash", "45.00", "customerId", c.toString(), "bonusUsed", "5.00"));
        post(owner, "/api/sales", s2 ).andExpect(status().isCreated()).andExpect(jsonPath("$.total", is(45.0)));
        post(owner, "/api/sales", s2).andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("BONUS_EXCEEDS")));
        assertEquals(2.25, balance(owner, c));

        // cancelling the first sale takes its 5 back, but never below zero
        JsonNode sales = body(get(owner, "/api/sales?customerId=" + c)).get("items");
        String first = sales.get(sales.size() - 1).get("id").asText();
        post(owner, "/api/sales/" + first + "/cancel", Map.of()).andExpect(status().isOk());
        assertEquals(0.0, balance(owner, c));

        // a hand-made gift from the owner
        post(owner, "/api/customers/" + c + "/bonus", Map.of("amount", "10.00", "note", "Ad günü")).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(10.0)));
        get(owner, "/api/customers").andExpect(jsonPath("$[0].bonusBalance", is(10.0)));
    }
}
