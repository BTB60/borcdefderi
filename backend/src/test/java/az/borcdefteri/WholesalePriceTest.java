package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Top 1 (topdan) and Top 2 (pərakəndə) prices: the till sells at retail unless the cashier picks wholesale. */
class WholesalePriceTest extends IntegrationTestBase {

    @Test
    void retailByDefaultWholesaleWhenChosen() throws Exception {
        String owner = register("Topdan Mağaza");
        activateSubscription(owner, "MONTHLY");
        Map<String, Object> b = new HashMap<>(Map.of("name", "Un 50 kq", "purchasePrice", "30", "salePrice", "45",
                "wholesalePrice", "38", "initialStock", "20"));
        String id = body(post(owner, "/api/products", b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.wholesalePrice", is(38.0)))).get("id").asText();

        Map<String, Object> retail = new HashMap<>(Map.of("items", List.of(Map.of("productId", id, "quantity", "2")), "paidCash", "90.00"));
        post(owner, "/api/sales", retail).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total", is(90.0))).andExpect(jsonPath("$.priceLevel", is("RETAIL")));

        Map<String, Object> wholesale = new HashMap<>(Map.of("items", List.of(Map.of("productId", id, "quantity", "2")),
                "paidCash", "76.00", "priceLevel", "WHOLESALE"));
        post(owner, "/api/sales", wholesale).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total", is(76.0))).andExpect(jsonPath("$.priceLevel", is("WHOLESALE")));

        // 0 removes the wholesale price: a wholesale sale then uses the retail price
        Map<String, Object> upd = new HashMap<>(Map.of("name", "Un 50 kq", "purchasePrice", "30", "salePrice", "45",
                "wholesalePrice", "0", "version", body(get(owner, "/api/products/" + id)).get("version").asLong()));
        put(owner, "/api/products/" + id, upd).andExpect(status().isOk()).andExpect(jsonPath("$.wholesalePrice").doesNotExist());
    }
}
