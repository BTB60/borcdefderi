package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mağaza reklamları: a store's promo posts reach only the customers holding that store's card, while they are live. */
class StorePromoTest extends IntegrationTestBase {

    @Test
    void customersSeeTheLivePromosOfTheirStoresOnly() throws Exception {
        String a = register("Geyim Evi");
        giveCardCredits(activateSubscription(a, "MONTHLY"), 50);
        String b = register("Başqa Mağaza");
        giveCardCredits(activateSubscription(b, "MONTHLY"), 50);
        JsonNode card = body(post(a, "/api/loyalty-cards", Map.of("firstName", "Leyla", "lastName", "Əliyeva", "phone", "050 444 33 22", "percent", "5")));

        JsonNode promo = body(post(a, "/api/promos", Map.of("title", "Qış kolleksiyası", "subtitle", "Bütün paltolar", "badge", "-30%",
                "oldPrice", "100", "newPrice", "70", "color", "#7c3aed")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.live", is(true))));
        post(a, "/api/promos", Map.of("title", "Söndürülüb", "active", false)).andExpect(status().isCreated());
        post(a, "/api/promos", Map.of("title", "Bitib", "endsOn", LocalDate.now().minusDays(2).toString())).andExpect(status().isCreated());
        post(b, "/api/promos", Map.of("title", "Başqa mağazanın endirimi")).andExpect(status().isCreated());
        post(a, "/api/promos", Map.of("title", "x", "color", "qırmızı")).andExpect(status().isBadRequest());

        mvc.perform(multipart("/api/promos/" + promo.get("id").asText() + "/image")
                        .file(new MockMultipartFile("file", "p.png", "image/png", pngBytes()))
                        .header("Authorization", "Bearer " + a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasImage", is(true)));
        mvc.perform(MockMvcRequestBuilders.get("/api/public/promos/" + promo.get("id").asText() + "/image")).andExpect(status().isOk());
        get(b, "/api/promos").andExpect(jsonPath("$.length()", is(1)));                          // stores see only their own
        put(b, "/api/promos/" + promo.get("id").asText(), Map.of("title", "oğurluq")).andExpect(status().isNotFound());

        // the customer signs in with the card and sees the one live post of that store
        String code = jdbcClient.sql("SELECT activation_code FROM loyalty_cards WHERE id = :id")
                .param("id", java.util.UUID.fromString(card.get("id").asText())).query(String.class).single();
        String token = body(post(null, "/api/public/customer-app/activate", Map.of("cardNumber", card.get("number").asText(), "code", code)))
                .get("token").asText();
        JsonNode feed = body(mvc.perform(MockMvcRequestBuilders.get("/api/public/customer-app/promos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        assertEquals(1, feed.size());
        assertEquals("Qış kolleksiyası", feed.get(0).get("title").asText());
        assertEquals("Geyim Evi", feed.get(0).get("storeName").asText());
        assertEquals(70.0, feed.get(0).get("newPrice").asDouble());
        assertTrue(feed.get(0).get("hasImage").asBoolean());
        mvc.perform(MockMvcRequestBuilders.get("/api/public/customer-app/promos")).andExpect(status().isUnauthorized());

        // switched off, it disappears; deleted, it is gone
        Map<String, Object> off = new HashMap<>(Map.of("title", "Qış kolleksiyası", "active", false));
        put(a, "/api/promos/" + promo.get("id").asText(), off).andExpect(jsonPath("$.live", is(false)));
        mvc.perform(MockMvcRequestBuilders.get("/api/public/customer-app/promos").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()", is(0)));
        mvc.perform(MockMvcRequestBuilders.delete("/api/promos/" + promo.get("id").asText()).header("Authorization", "Bearer " + a))
                .andExpect(status().isNoContent());
        get(a, "/api/promos").andExpect(jsonPath("$.length()", is(2)));
    }
}
