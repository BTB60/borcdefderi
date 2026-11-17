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
        JsonNode sold = body(post(owner, "/api/sales", sale).andExpect(status().isCreated()));
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

        // gün sonu: the same day in a few numbers
        JsonNode day = body(get(owner, "/api/reports/day-summary").andExpect(status().isOk()));
        assertThat(day.get("salesCount").asLong()).isEqualTo(1);
        assertThat(day.get("salesTotal").asDouble()).isEqualTo(9.0);
        assertThat(day.get("grossProfit").asDouble()).isEqualTo(9.0 - sold.get("costTotal").asDouble());
        assertThat(day.get("topProducts").get(0).get("name").asText()).isEqualTo("Çörək");
        assertThat(day.get("storeName").asText()).isEqualTo("Kassir Hesabat");
    }

    @Test
    void aCreditSaleOverTheCustomersLimitNeedsTheOwner() throws Exception {
        String owner = register("Limit Mağaza");
        activateSubscription(owner, "MONTHLY");
        String p = body(post(owner, "/api/products", Map.of("name", "Ət", "purchasePrice", "10.00", "salePrice", "20.00", "initialStock", "100"))
                .andExpect(status().isCreated())).get("id").asText();
        java.util.UUID c = createCustomer(owner, "Nisyəçi", "+994501239999");
        put(owner, "/api/customers/" + c, Map.of("name", "Nisyəçi", "phone", "+994501239999", "creditLimit", "50.00"))
                .andExpect(status().isOk());
        JsonNode list = body(get(owner, "/api/customers"));
        assertThat(list.toString()).contains("\"creditLimit\":50.0");

        Map<String, Object> s2 = new HashMap<>(Map.of("items", List.of(Map.of("productId", p, "quantity", "2")), "customerId", c.toString()));
        post(owner, "/api/sales", s2).andExpect(status().isCreated());          // owes 40
        JsonNode err = body(post(owner, "/api/sales", s2).andExpect(status().isConflict()));  // would be 80
        assertThat(err.get("error").asText()).isEqualTo("CREDIT_LIMIT");
        assertThat(err.get("fields").get("after").asText()).isEqualTo("80.00");

        Map<String, Object> allowed = new HashMap<>(s2);
        allowed.put("allowOverLimit", true);
        post(owner, "/api/sales", allowed).andExpect(status().isCreated());     // the owner lets it through

        put(owner, "/api/customers/" + c, Map.of("name", "Nisyəçi", "noCreditLimit", true)).andExpect(status().isOk());
        post(owner, "/api/sales", s2).andExpect(status().isCreated());
    }
}
