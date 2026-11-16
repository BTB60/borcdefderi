package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kassir növbəsi: open with the drawer cash, sell, close with the counted cash, Z-report shows the difference. */
class ShiftTest extends IntegrationTestBase {

    @Test
    void aShiftCountsTheCashiersSalesAndTheDifference() throws Exception {
        String owner = register("Növbə Mağaza");
        activateSubscription(owner, "MONTHLY");
        String p = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Su", "purchasePrice", "0.5",
                "salePrice", "1.00", "initialStock", "100")))).get("id").asText();

        get(owner, "/api/shifts/current").andExpect(status().isNoContent());
        String id = body(post(owner, "/api/shifts/open", Map.of("openingCash", "50.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.open", is(true)))).get("id").asText();
        post(owner, "/api/shifts/open", Map.of("openingCash", "0")).andExpect(status().isConflict());

        post(owner, "/api/sales", Map.of("items", List.of(Map.of("productId", p, "quantity", "10")), "paidCash", "10.00"))
                .andExpect(status().isCreated());
        post(owner, "/api/sales", Map.of("items", List.of(Map.of("productId", p, "quantity", "5")), "paidCard", "5.00"))
                .andExpect(status().isCreated());

        get(owner, "/api/shifts/current").andExpect(jsonPath("$.salesCount", is(2))).andExpect(jsonPath("$.salesTotal", is(15.0)))
                .andExpect(jsonPath("$.paidCash", is(10.0))).andExpect(jsonPath("$.paidCard", is(5.0)))
                .andExpect(jsonPath("$.expectedCash", is(60.0)));

        JsonNode z = body(post(owner, "/api/shifts/" + id + "/close", Map.of("countedCash", "58.00", "note", "2 manat əskik"))
                .andExpect(status().isOk()));
        assertEquals(false, z.get("open").asBoolean());
        assertEquals(-2.0, z.get("difference").asDouble());
        post(owner, "/api/shifts/" + id + "/close", Map.of("countedCash", "1")).andExpect(status().isConflict());
        get(owner, "/api/shifts").andExpect(jsonPath("$[0].countedCash", is(58.0)));
    }
}
