package az.borcdefteri;

import az.borcdefteri.notifications.SmsGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HesabPro Kart: the customer signs in by card code or SMS code and sees their cards from every store, nothing else. */
class CustomerAppTest extends IntegrationTestBase {

    @MockitoBean
    SmsGateway gateway;

    private ResultActions anon(String method, String url, Object body, String token) throws Exception {
        var b = "GET".equals(method) ? MockMvcRequestBuilders.get(url) : MockMvcRequestBuilders.post(url)
                .contentType("application/json").content(json.writeValueAsString(body));
        if (token != null) b = b.header("Authorization", "Bearer " + token);
        return mvc.perform(b);
    }

    @Test
    void aCustomerSeesTheirCardsFromEveryStore() throws Exception {
        // two stores give a card to the same phone, written differently
        String a = register("Ayaqqabı Evi");
        giveCardCredits(activateSubscription(a, "MONTHLY"), 50);
        String b = register("Market 24");
        giveCardCredits(activateSubscription(b, "MONTHLY"), 50);
        put(a, "/api/settings/print-design", Map.of("card", Map.of("title", "VIP kart"))).andExpect(status().isOk());
        JsonNode cardA = body(post(a, "/api/loyalty-cards", Map.of("firstName", "Aynur", "lastName", "Əliyeva", "phone", "050 555 44 33", "percent", "7")));
        body(post(b, "/api/loyalty-cards", Map.of("firstName", "Aynur", "lastName", "Əliyeva", "phone", "+994505554433", "percent", "3")));
        post(b, "/api/customers/" + cardA.get("customerId").asText() + "/bonus", Map.of("amount", "1")).andExpect(status().isNotFound()); // other store

        // wrong card code, then the right one
        anon("POST", "/api/public/customer-app/activate", Map.of("cardNumber", cardA.get("number").asText(), "code", "000000x"), null)
                .andExpect(status().isBadRequest());
        String code = jdbcClient.sql("SELECT activation_code FROM loyalty_cards WHERE id = :id")
                .param("id", java.util.UUID.fromString(cardA.get("id").asText())).query(String.class).single();
        JsonNode session = body(anon("POST", "/api/public/customer-app/activate", Map.of("cardNumber", cardA.get("number").asText(), "code", code), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.name", is("Aynur"))));
        String token = session.get("token").asText();

        JsonNode me = body(anon("GET", "/api/public/customer-app/me", null, token).andExpect(status().isOk()));
        assertEquals(2, me.get("cards").size());
        assertEquals("994505554433", me.get("phone").asText());
        JsonNode first = me.get("cards").get(0);
        assertTrue(List.of("Ayaqqabı Evi", "Market 24").contains(first.get("storeName").asText()));
        JsonNode ayaqqabi = null;
        for (JsonNode c : me.get("cards")) if ("Ayaqqabı Evi".equals(c.get("storeName").asText())) ayaqqabi = c;
        assertEquals("VIP kart", ayaqqabi.get("design").get("title").asText());
        assertEquals(7.0, ayaqqabi.get("percent").asDouble());
        anon("GET", "/api/public/customer-app/cards/" + ayaqqabi.get("id").asText() + "/history", null, token).andExpect(status().isOk());

        // the customer token is no store login; a store token is no customer login
        mvc.perform(MockMvcRequestBuilders.get("/api/customers").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        anon("GET", "/api/public/customer-app/me", null, a).andExpect(status().isUnauthorized());
        anon("GET", "/api/public/customer-app/me", null, null).andExpect(status().isUnauthorized());

        // SMS sign-in: off without a provider, then a code goes out and signs in; wrong codes are counted
        when(gateway.configured()).thenReturn(false);
        anon("GET", "/api/public/customer-app/config", null, null).andExpect(jsonPath("$.smsLogin", is(false)));
        anon("POST", "/api/public/customer-app/code", Map.of("phone", "0505554433"), null).andExpect(status().isServiceUnavailable());
        when(gateway.configured()).thenReturn(true);
        anon("POST", "/api/public/customer-app/code", Map.of("phone", "0505554433"), null).andExpect(status().isOk());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gateway).send(eq("994505554433"), text.capture());
        String sms = text.getValue().replaceAll("\\D", "");
        anon("POST", "/api/public/customer-app/verify", Map.of("phone", "+994505554433", "code", "999999"), null).andExpect(status().isBadRequest());
        anon("POST", "/api/public/customer-app/verify", Map.of("phone", "+994505554433", "code", sms), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.phone", is("994505554433")));
        anon("POST", "/api/public/customer-app/verify", Map.of("phone", "+994505554433", "code", sms), null).andExpect(status().isBadRequest()); // used
        anon("POST", "/api/public/customer-app/code", Map.of("phone", "12"), null).andExpect(status().isBadRequest());

        // a blocked card disappears from the app
        put(a, "/api/loyalty-cards/" + cardA.get("id").asText(), new HashMap<>(Map.of("firstName", "Aynur", "lastName", "Əliyeva",
                "phone", "050 555 44 33", "percent", "7", "active", false))).andExpect(status().isOk());
        anon("GET", "/api/public/customer-app/me", null, token).andExpect(jsonPath("$.cards.length()", is(1)));
        verify(gateway, times(1)).send(anyString(), anyString());
    }

    @Test
    void theNewCardGoesToTheCustomerBySms() throws Exception {
        String a = register("Ətir Dünyası");
        giveCardCredits(activateSubscription(a, "MONTHLY"), 50);
        // no provider: the card is made, nothing is sent, and a resend says SMS is off
        when(gateway.configured()).thenReturn(false);
        JsonNode quiet = body(post(a, "/api/loyalty-cards", Map.of("firstName", "Leyla", "lastName", "Quliyeva", "phone", "055 111 22 33", "percent", "5")));
        post(a, "/api/loyalty-cards/" + quiet.get("id").asText() + "/sms", Map.of()).andExpect(status().isConflict());
        verify(gateway, never()).send(anyString(), anyString());

        when(gateway.configured()).thenReturn(true);
        JsonNode card = body(post(a, "/api/loyalty-cards", Map.of("firstName", "Rəna", "lastName", "Həsənova", "phone", "070 222 33 44", "percent", "4")));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gateway).send(eq("994702223344"), text.capture());
        assertThat(text.getValue(), allOf(containsString(card.get("number").asText()), containsString(card.get("activationCode").asText()),
                containsString("hesabpro.cloud/kart"), containsString("Ətir Dünyası")));

        // the store can send it again, or skip it when making a card
        post(a, "/api/loyalty-cards/" + card.get("id").asText() + "/sms", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.sent", is(true)));
        body(post(a, "/api/loyalty-cards", Map.of("firstName", "Orxan", "lastName", "Məmmədli", "phone", "077 333 44 55", "percent", "2", "sendSms", false)));
        verify(gateway, times(2)).send(anyString(), anyString());

        // the provider refuses (e.g. an empty balance): the app gets a clear message, not "server is waking", and the try is logged
        doThrow(new RuntimeException("insufficient_balance")).when(gateway).send(eq("994709998877"), anyString());
        anon("POST", "/api/public/customer-app/code", Map.of("phone", "070 999 88 77"), null)
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error", is("SMS_FAILED")))
                .andExpect(jsonPath("$.message", containsString("aktivləşdirmə kodu")));
        assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM sms_messages WHERE phone = '994709998877' AND error LIKE '%insufficient_balance%'")
                .query(Long.class).single());
    }
}
