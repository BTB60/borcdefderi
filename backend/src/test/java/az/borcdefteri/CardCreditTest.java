package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Paid bonus cards: no balance, no card; a package is paid, the admin approves it, each card uses one. */
class CardCreditTest extends IntegrationTestBase {

    private Map<String, Object> card(String phone) {
        return Map.of("firstName", "Aysel", "lastName", "Quliyeva", "phone", phone, "percent", "5");
    }

    @Test
    void cardsArePaidPerPackage() throws Exception {
        String owner = register("Pullu Kart");
        UUID sub = activateSubscription(owner, "MONTHLY");
        UUID storeId = jdbcClient.sql("SELECT store_id FROM subscriptions WHERE id = :id").param("id", sub).query(UUID.class).single();

        // no balance: the card is not made and says why
        get(owner, "/api/card-credits").andExpect(jsonPath("$.credits", is(0))).andExpect(jsonPath("$.pricePerCard", is(0.85)));
        post(owner, "/api/loyalty-cards", card("050 111 11 11")).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("NO_CARD_CREDITS")));
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM customers WHERE store_id = :s").param("s", storeId).query(Long.class).single());

        // the owner pays for 20 cards (17 AZN) and sends the receipt; too few is refused
        mvc.perform(multipart("/api/card-credits/requests").param("quantity", "5").header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
        JsonNode req = body(mvc.perform(multipart("/api/card-credits/requests")
                        .file(new MockMultipartFile("receipt", "qebz.png", "image/png", pngBytes()))
                        .param("quantity", "20").param("paymentMethod", "m10").param("payerReference", "M10-55")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount", is(17.0))).andExpect(jsonPath("$.status", is("PENDING"))));
        String id = req.get("id").asText();
        get(owner, "/api/card-credits").andExpect(jsonPath("$.pending.id", is(id))).andExpect(jsonPath("$.credits", is(0)));
        get(owner, "/api/card-credits/requests/" + id + "/receipt").andExpect(status().isOk());

        // only the platform admin approves; other stores cannot see it
        String other = register("Başqası");
        post(other, "/api/admin/card-credit-requests/" + id + "/approve", Map.of()).andExpect(status().isForbidden());
        get(other, "/api/card-credits/requests/" + id + "/receipt").andExpect(status().isNotFound());
        get(adminToken(), "/api/admin/card-credit-requests").andExpect(status().isOk());
        post(adminToken(), "/api/admin/card-credit-requests/" + id + "/approve", Map.of()).andExpect(jsonPath("$.status", is("APPROVED")));
        post(adminToken(), "/api/admin/card-credit-requests/" + id + "/approve", Map.of()).andExpect(status().isConflict());
        get(owner, "/api/card-credits").andExpect(jsonPath("$.credits", is(20)));

        // each card uses one; a card refused for another reason gives it back
        post(owner, "/api/loyalty-cards", card("050 111 11 11")).andExpect(status().isCreated());
        post(owner, "/api/loyalty-cards", card("050 111 11 11")).andExpect(status().isConflict()); // same phone
        get(owner, "/api/card-credits").andExpect(jsonPath("$.credits", is(19)));

        // the admin can add cards by hand; a rejected request adds nothing
        post(adminToken(), "/api/admin/stores/" + storeId + "/card-credits", Map.of("quantity", 5, "note", "hədiyyə")).andExpect(status().isOk());
        get(owner, "/api/card-credits").andExpect(jsonPath("$.credits", is(24)));
        JsonNode second = body(mvc.perform(multipart("/api/card-credits/requests").param("quantity", "50")
                .header("Authorization", "Bearer " + owner)).andExpect(status().isOk()));
        post(adminToken(), "/api/admin/card-credit-requests/" + second.get("id").asText() + "/reject", Map.of("reason", "ödəniş gəlməyib"))
                .andExpect(jsonPath("$.status", is("REJECTED")));
        get(owner, "/api/card-credits").andExpect(jsonPath("$.credits", is(24))).andExpect(jsonPath("$.history.length()", is(3)));
        mvc.perform(MockMvcRequestBuilders.get("/api/card-credits")).andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminSetsThePriceOfACard() throws Exception {
        String owner = register("Qiymət Mağaza");
        activateSubscription(owner, "MONTHLY");
        get(adminToken(), "/api/admin/card-credits/price").andExpect(jsonPath("$.price", is(0.85)));
        put(owner, "/api/admin/card-credits/price", Map.of("price", "0.10")).andExpect(status().isForbidden());
        put(adminToken(), "/api/admin/card-credits/price", Map.of("price", "0")).andExpect(status().isBadRequest());
        put(adminToken(), "/api/admin/card-credits/price", Map.of("price", "1.20")).andExpect(jsonPath("$.price", is(1.2)));

        // the store sees the new price, and a new request is counted with it
        get(owner, "/api/card-credits").andExpect(jsonPath("$.pricePerCard", is(1.2)));
        mvc.perform(multipart("/api/card-credits/requests").param("quantity", "20").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount", is(24.0)));
        put(adminToken(), "/api/admin/card-credits/price", Map.of("price", "0.85")).andExpect(status().isOk());
    }
}
