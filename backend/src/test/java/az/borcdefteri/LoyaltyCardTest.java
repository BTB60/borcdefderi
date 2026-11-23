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

/** Bonus kartları: each card has its own percent; a blocked card earns and spends nothing. */
class LoyaltyCardTest extends IntegrationTestBase {

    @Test
    void eachCardEarnsAtItsOwnPercent() throws Exception {
        String owner = register("Kart Mağaza");
        activateSubscription(owner, "MONTHLY");
        put(owner, "/api/settings/bonus", Map.of("bonusPercent", "2")).andExpect(status().isOk());
        String p = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Su", "purchasePrice", "0.5", "salePrice", "10",
                "initialStock", "100")))).get("id").asText();

        // an existing customer with the same phone gets the card; a new phone makes a customer
        UUID existing = createCustomer(owner, "Köhnə Müştəri", "050 111 22 33");
        JsonNode gold = body(post(owner, "/api/loyalty-cards", Map.of("firstName", "Aynur", "lastName", "Əliyeva", "patronymic", "Rauf qızı",
                "phone", "+994501112233", "percent", "10")).andExpect(status().isCreated()));
        assertEquals(existing.toString(), gold.get("customerId").asText());
        assertEquals("Əliyeva Aynur Rauf qızı", gold.get("fullName").asText());
        post(owner, "/api/loyalty-cards", Map.of("firstName", "X", "lastName", "Y", "phone", "0501112233", "percent", "5"))
                .andExpect(status().isConflict());
        JsonNode plain = body(post(owner, "/api/loyalty-cards", Map.of("firstName", "Vüsal", "lastName", "Məmmədov",
                "phone", "0559998877", "percent", "3")).andExpect(status().isCreated()));
        post(owner, "/api/loyalty-cards", Map.of("firstName", "", "lastName", "Y", "phone", "0551", "percent", "60"))
                .andExpect(status().isBadRequest());

        // 100 AZN each: 10% card → 10, 3% card → 3, no card → store 2% → 2
        UUID noCard = createCustomer(owner, "Kartsız", "+994701234567");
        for (String c : new String[]{gold.get("customerId").asText(), plain.get("customerId").asText(), noCard.toString()}) {
            post(owner, "/api/sales", Map.of("customerId", c, "items", List.of(Map.of("productId", p, "quantity", "10")), "paidCash", "100.00"))
                    .andExpect(status().isCreated());
        }
        get(owner, "/api/loyalty-cards/" + gold.get("id").asText()).andExpect(jsonPath("$.card.balance", is(10.0)))
                .andExpect(jsonPath("$.entries.length()", is(1)));
        get(owner, "/api/loyalty-cards/" + plain.get("id").asText()).andExpect(jsonPath("$.card.balance", is(3.0)));
        get(owner, "/api/customers/" + noCard + "/bonus").andExpect(jsonPath("$.balance", is(2.0)));
        get(owner, "/api/customers/" + gold.get("customerId").asText() + "/bonus").andExpect(jsonPath("$.storePercent", is(10.0)));
        get(owner, "/api/loyalty-cards?q=aynur").andExpect(jsonPath("$.length()", is(1)));
        get(owner, "/api/loyalty-cards?q=9998877").andExpect(jsonPath("$.length()", is(1)));
        get(owner, "/api/loyalty-cards?q=" + gold.get("number").asText()).andExpect(jsonPath("$[0].percent", is(10.0)));

        // the store's card design reaches the customer's kabinet, with the card's own percent
        put(owner, "/api/settings/print-design", Map.of("card", Map.of("background", "#123456", "textColor", "#ffffff", "title", "VIP kart")))
                .andExpect(status().isOk());
        String url = body(post(owner, "/api/customers/" + gold.get("customerId").asText() + "/portal-links", Map.of("days", 30))).get("url").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/portal/" + url.substring(url.lastIndexOf('/') + 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cardDesign.title", is("VIP kart"))).andExpect(jsonPath("$.bonusPercent", is(10.0)));

        // change the percent, then block: nothing earned, spending refused
        put(owner, "/api/loyalty-cards/" + plain.get("id").asText(), Map.of("firstName", "Vüsal", "lastName", "Məmmədov",
                "phone", "0559998877", "percent", "5", "active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.percent", is(5.0))).andExpect(jsonPath("$.active", is(false)));
        post(owner, "/api/sales", Map.of("customerId", plain.get("customerId").asText(), "items", List.of(Map.of("productId", p, "quantity", "10")),
                "paidCash", "100.00")).andExpect(status().isCreated()).andExpect(jsonPath("$.bonusEarned", is(0.0)));
        post(owner, "/api/sales", Map.of("customerId", plain.get("customerId").asText(), "items", List.of(Map.of("productId", p, "quantity", "1")),
                "paidCash", "8.00", "bonusUsed", "2.00")).andExpect(status().isConflict());
    }
}
