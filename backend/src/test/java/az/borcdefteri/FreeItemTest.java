package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sərbəst məbləğ: goods with no barcode are sold by amount and name; nothing in stock changes, lists stay clean. */
class FreeItemTest extends IntegrationTestBase {

    @Test
    void freeAmountIsSoldWithItsOwnNameAndKeepsNoStock() throws Exception {
        String owner = register("Sərbəst Mağaza");
        activateSubscription(owner, "MONTHLY");
        String free = body(get(owner, "/api/products/free-item").andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Sərbəst məbləğ")))).get("id").asText();
        // the same product next time
        get(owner, "/api/products/free-item").andExpect(jsonPath("$.id", is(free)));
        String bread = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Çörək",
                "purchasePrice", "0.50", "salePrice", "1.00", "initialStock", "5"))).andExpect(status().isCreated())).get("id").asText();

        Map<String, Object> sale = new HashMap<>();
        sale.put("items", List.of(
                Map.of("productId", free, "quantity", "1", "unitPrice", "3.50", "name", "Plastik vedrə"),
                Map.of("productId", free, "quantity", "2", "unitPrice", "0.40", "name", "Paket"),
                Map.of("productId", bread, "quantity", "1")));
        sale.put("paidCash", "5.30");
        String saleId = body(post(owner, "/api/sales", sale).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total", is(5.3)))
                .andExpect(jsonPath("$.items[*].name", hasItems("Plastik vedrə", "Paket", "Çörək")))).get("id").asText();

        // no stock for the free item, the bread went down
        get(owner, "/api/products/" + free).andExpect(jsonPath("$.stockQty", is(0.0)));
        get(owner, "/api/products/" + bread).andExpect(jsonPath("$.stockQty", is(4.0)));
        // never listed
        get(owner, "/api/products").andExpect(jsonPath("$[*].name", not(hasItem("Sərbəst məbləğ"))));
        get(owner, "/api/products?active=false").andExpect(jsonPath("$[*].name", not(hasItem("Sərbəst məbləğ"))));

        // a return of a free line works and touches no stock
        String line = body(get(owner, "/api/sales/" + saleId)).get("items").findValues("id").get(0).asText();
        post(owner, "/api/sales/" + saleId + "/returns", Map.of("items", List.of(Map.of("saleItemId", line, "quantity", "1"))))
                .andExpect(status().is2xxSuccessful());

        // a free line needs an amount
        Map<String, Object> empty = new HashMap<>();
        empty.put("items", List.of(Map.of("productId", free, "quantity", "1")));
        empty.put("paidCash", "0");
        post(owner, "/api/sales", empty).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("FREE_AMOUNT_REQUIRED")));
    }
}
