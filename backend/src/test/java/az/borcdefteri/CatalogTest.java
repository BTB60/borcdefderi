package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Onlayn kataloq: closed by default, public page when on, orders reach the store. */
class CatalogTest extends IntegrationTestBase {

    @Test
    void customersOrderFromThePublicCatalog() throws Exception {
        String owner = register("Kataloq Mağaza");
        activateSubscription(owner, "MONTHLY");
        String p = body(post(owner, "/api/products", Map.of("name", "Alma 1 kq", "purchasePrice", "1.00", "salePrice", "2.50", "initialStock", "40"))
                .andExpect(status().isCreated())).get("id").asText();

        JsonNode st = body(put(owner, "/api/catalog/settings", Map.of("enabled", false)).andExpect(status().isOk()));
        String slug = st.get("slug").asText();
        get(null, "/api/public/catalog/" + slug).andExpect(status().isNotFound());

        put(owner, "/api/catalog/settings", Map.of("enabled", true, "note", "Çatdırılma pulsuzdur")).andExpect(status().isOk());
        JsonNode cat = body(get(null, "/api/public/catalog/" + slug).andExpect(status().isOk()));
        assertThat(cat.get("storeName").asText()).isEqualTo("Kataloq Mağaza");
        assertThat(cat.get("products").get(0).get("price").asDouble()).isEqualTo(2.5);
        assertThat(cat.get("products").get(0).has("purchasePrice")).isFalse();

        JsonNode o = body(post(null, "/api/public/catalog/" + slug + "/orders", Map.of("name", "Aysel", "phone", "+994 50 111 22 33",
                "items", List.of(Map.of("productId", p, "quantity", 3)))).andExpect(status().isCreated()));
        assertThat(o.get("total").asDouble()).isEqualTo(7.5);
        post(null, "/api/public/catalog/" + slug + "/orders", Map.of("name", "X", "phone", "abc", "items", List.of()))
                .andExpect(status().isBadRequest());

        JsonNode orders = body(get(owner, "/api/online-orders?status=NEW"));
        assertThat(orders.size()).isEqualTo(1);
        assertThat(orders.get(0).get("items").get(0).get("name").asText()).isEqualTo("Alma 1 kq");
        post(owner, "/api/online-orders/" + orders.get(0).get("id").asText() + "/status", Map.of("status", "DONE")).andExpect(status().isOk());
        assertThat(body(get(owner, "/api/online-orders?status=NEW")).size()).isZero();
        assertThat(body(get(owner, "/api/notifications")).toString()).contains("onlayn sifariş");

        String stranger = register("Başqa Mağaza");
        activateSubscription(stranger, "MONTHLY");
        assertThat(body(get(stranger, "/api/online-orders")).size()).isZero();
    }
}
