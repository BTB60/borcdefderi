package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Son istifadə tarixi: batches with an expiry date, what is left of them on the shelf, and the bell warning. */
class ExpiryTest extends IntegrationTestBase {

    private void receive(String owner, UUID supplier, String product, String qty, LocalDate expiry) throws Exception {
        Map<String, Object> item = new HashMap<>(Map.of("productId", product, "quantity", qty, "unitCost", "2.00"));
        if (expiry != null) item.put("expiryDate", expiry.toString());
        post(owner, "/api/purchases", Map.of("companyId", supplier.toString(), "items", List.of(item)))
                .andExpect(status().isCreated());
    }

    @Test
    void theOldestBatchIsSoldFirstAndWarnedAbout() throws Exception {
        String owner = register("Srok Mağaza");
        activateSubscription(owner, "MONTHLY");
        String milk = body(post(owner, "/api/products", Map.of("name", "Süd", "purchasePrice", "2.00", "salePrice", "3.00"))
                .andExpect(status().isCreated())).get("id").asText();
        UUID supplier = createCompany(owner, "Süd MMC");
        LocalDate today = LocalDate.now(clock);

        receive(owner, supplier, milk, "10", today.plusDays(3));   // old batch, expires soon
        receive(owner, supplier, milk, "10", today.plusDays(60));  // new batch

        JsonNode soon = body(get(owner, "/api/inventory/expiring?days=30").andExpect(status().isOk()));
        assertThat(soon.size()).isEqualTo(1);
        assertThat(soon.get(0).get("quantityLeft").asDouble()).isEqualTo(10.0);
        assertThat(soon.get(0).get("daysLeft").asLong()).isEqualTo(3);
        assertThat(soon.get(0).get("costValue").asDouble()).isEqualTo(20.0);
        assertThat(body(get(owner, "/api/notifications")).toString()).contains("son istifadə tarixi");

        // 14 sold: the old batch is gone, 6 of the new one are left (it is not within 30 days)
        post(owner, "/api/inventory/movements", Map.of("productId", milk, "kind", "ISSUE", "quantity", "14"))
                .andExpect(status().isCreated());
        assertThat(body(get(owner, "/api/inventory/expiring?days=30")).size()).isZero();
        JsonNode all = body(get(owner, "/api/inventory/expiring?days=90"));
        assertThat(all.size()).isEqualTo(1);
        assertThat(all.get(0).get("quantityLeft").asDouble()).isEqualTo(6.0);

        // the receipt shows the date
        String newest = body(get(owner, "/api/purchases")).get(0).get("id").asText();
        assertThat(body(get(owner, "/api/purchases/" + newest)).get("items").get(0).get("expiryDate").asText())
                .isEqualTo(today.plusDays(60).toString());
        get(owner, "/api/inventory/expiring?days=400").andExpect(status().isBadRequest());
    }
}
