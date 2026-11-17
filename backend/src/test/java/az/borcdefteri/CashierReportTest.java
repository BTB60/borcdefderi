package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kassir hesabatı: sales, discounts, cancellations per cashier. */
class CashierReportTest extends IntegrationTestBase {

    @Test
    void salesAndCancellationsAreCountedPerCashier() throws Exception {
        String owner = register("Kassir Hesabat");
        activateSubscription(owner, "MONTHLY");
        String p = body(post(owner, "/api/products", Map.of("name", "Çörək", "purchasePrice", "0.50", "salePrice", "1.00", "initialStock", "100"))
                .andExpect(status().isCreated())).get("id").asText();

        Map<String, Object> sale = new HashMap<>(Map.of("items", List.of(Map.of("productId", p, "quantity", "10")),
                "paidCash", "9.00", "discount", "1.00"));
        post(owner, "/api/sales", sale).andExpect(status().isCreated());
        Map<String, Object> card = new HashMap<>(Map.of("items", List.of(Map.of("productId", p, "quantity", "5")), "paidCard", "5.00"));
        String cancelled = body(post(owner, "/api/sales", card).andExpect(status().isCreated())).get("id").asText();
        post(owner, "/api/sales/" + cancelled + "/cancel", Map.of("reason", "səhv")).andExpect(status().isOk());

        JsonNode r = body(get(owner, "/api/reports/cashiers").andExpect(status().isOk()));
        JsonNode me = r.get("rows").get(0);
        assertThat(me.get("jobTitle").asText()).isEqualTo("OWNER");
        assertThat(me.get("salesCount").asLong()).isEqualTo(1);
        assertThat(me.get("salesTotal").asDouble()).isEqualTo(9.0);
        assertThat(me.get("paidCash").asDouble()).isEqualTo(9.0);
        assertThat(me.get("discount").asDouble()).isEqualTo(1.0);
        assertThat(me.get("cancelledCount").asLong()).isEqualTo(1);
        assertThat(me.get("cancelledTotal").asDouble()).isEqualTo(5.0);

        get(owner, "/api/reports/cashiers?from=2026-12-01&to=2026-11-01").andExpect(status().isBadRequest());
    }
}
