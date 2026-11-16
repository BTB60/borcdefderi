package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Toplu qiymət dəyişikliyi: by %, rounded, only the chosen category. */
class BulkPriceTest extends IntegrationTestBase {

    @Test
    void pricesOfACategoryGoUpByAPercentRounded() throws Exception {
        String owner = register("Toplu Qiymət");
        activateSubscription(owner, "MONTHLY");
        String cat = body(post(owner, "/api/product-categories", Map.of("name", "Ərzaq"))).get("id").asText();
        String a = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Un", "purchasePrice", "1", "salePrice", "1.90", "categoryId", cat)))).get("id").asText();
        String b = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Sabun", "purchasePrice", "1", "salePrice", "3.00")))).get("id").asText();

        post(owner, "/api/products/bulk-price", Map.of("field", "SALE", "mode", "PERCENT", "value", "10", "roundTo", "0.05", "categoryId", cat))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed", is(1)));
        get(owner, "/api/products/" + a).andExpect(jsonPath("$.salePrice", is(2.1)));   // 1.90 * 1.1 = 2.09 -> 2.10
        get(owner, "/api/products/" + b).andExpect(jsonPath("$.salePrice", is(3.0)));

        post(owner, "/api/products/bulk-price", Map.of("field", "WHOLESALE", "mode", "AMOUNT", "value", "-0.50", "productIds", List.of(b)))
                .andExpect(status().isOk());
        get(owner, "/api/products/" + b).andExpect(jsonPath("$.wholesalePrice", is(2.5)));
    }
}
