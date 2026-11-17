package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Goods sold by weight (kq) next to goods sold by piece: the sale keeps each line's unit for the receipt. */
class WeighedSaleTest extends IntegrationTestBase {

    @Test
    void aSaleLineCarriesTheProductUnit() throws Exception {
        String owner = register("Tərəzi Mağazası");
        activateSubscription(owner, "MONTHLY");
        String tomato = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Pomidor", "unit", "kq",
                "purchasePrice", "2.00", "salePrice", "3.00", "initialStock", "10"))).andExpect(status().isCreated())).get("id").asText();
        String bread = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Çörək",
                "purchasePrice", "0.50", "salePrice", "1.00", "initialStock", "20"))).andExpect(status().isCreated())).get("id").asText();

        Map<String, Object> sale = new HashMap<>();
        sale.put("items", List.of(Map.of("productId", tomato, "quantity", "1.25"), Map.of("productId", bread, "quantity", "2")));
        sale.put("paidCash", "5.75");
        post(owner, "/api/sales", sale).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total", is(5.75)))
                .andExpect(jsonPath("$.items[?(@.name == 'Pomidor')].unit", contains("kq")))
                .andExpect(jsonPath("$.items[?(@.name == 'Pomidor')].quantity", contains(1.25)))
                .andExpect(jsonPath("$.items[?(@.name == 'Çörək')].unit", contains("ədəd")));
        get(owner, "/api/products/" + tomato).andExpect(jsonPath("$.stockQty", is(8.75)));
    }
}
