package az.borcdefteri;

import az.borcdefteri.notifications.SmsGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No card at hand: the till finds it by phone; spending its bonus needs the SMS code sent to that phone. */
class BonusByPhoneTest extends IntegrationTestBase {

    @MockitoBean
    SmsGateway gateway;

    @Test
    void theTillFindsTheCardByPhoneAndTheBonusNeedsTheCode() throws Exception {
        String a = register("Telefon Mağaza");
        giveCardCredits(activateSubscription(a, "MONTHLY"), 10);
        String b = register("Başqa");
        giveCardCredits(activateSubscription(b, "MONTHLY"), 10);
        JsonNode card = body(post(a, "/api/loyalty-cards", Map.of("firstName", "Rəşad", "lastName", "Həsənli", "phone", "+994 55 321 65 87",
                "percent", "5", "sendSms", false)).andExpect(status().isCreated()));

        // any way of writing the number finds it; other stores do not see it; a short number is refused
        get(a, "/api/loyalty-cards/lookup?phone=0553216587").andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].number", is(card.get("number").asText())))
                .andExpect(jsonPath("$[0].fullName", is("Həsənli Rəşad")));
        get(a, "/api/loyalty-cards/lookup?phone=55 321 65 87").andExpect(jsonPath("$.length()", is(1)));
        get(b, "/api/loyalty-cards/lookup?phone=0553216587").andExpect(jsonPath("$.length()", is(0)));
        get(a, "/api/loyalty-cards/lookup?phone=321").andExpect(status().isBadRequest());

        String id = card.get("id").asText();
        // without SMS the bonus opens without a code (and the journal says so)
        when(gateway.configured()).thenReturn(false);
        post(a, "/api/loyalty-cards/" + id + "/bonus-code", Map.of()).andExpect(jsonPath("$.smsOn", is(false)));

        // with SMS: a 4-digit code goes to the card's phone; a wrong one is refused, the right one works once
        when(gateway.configured()).thenReturn(true);
        post(a, "/api/loyalty-cards/" + id + "/bonus-code", Map.of()).andExpect(jsonPath("$.sent", is(true)))
                .andExpect(jsonPath("$.phone", is("***6587")));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gateway).send(eq("994553216587"), text.capture());
        String code = text.getValue().replaceAll(".*kodu (\\d{4}).*", "$1");
        post(a, "/api/loyalty-cards/" + id + "/bonus-code/verify", Map.of("code", code.equals("0000") ? "1111" : "0000"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("WRONG_CODE")));
        post(b, "/api/loyalty-cards/" + id + "/bonus-code/verify", Map.of("code", code)).andExpect(status().isNotFound());
        post(a, "/api/loyalty-cards/" + id + "/bonus-code/verify", Map.of("code", code)).andExpect(jsonPath("$.ok", is(true)));
        post(a, "/api/loyalty-cards/" + id + "/bonus-code/verify", Map.of("code", code)).andExpect(status().isBadRequest());
        verify(gateway, times(1)).send(anyString(), anyString());
    }

    @Test
    void aCashierWithoutTheCustomerListGetsTheCustomerFromTheCard() throws Exception {
        String a = register("Kassir Mağaza");
        giveCardCredits(activateSubscription(a, "MONTHLY"), 10);
        JsonNode card = body(post(a, "/api/loyalty-cards", Map.of("firstName", "Aysel", "lastName", "Quliyeva", "phone", "050 444 22 11",
                "percent", "5", "sendSms", false)).andExpect(status().isCreated()));
        String email = uniqueEmail("kassir");
        post(a, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Kassir", "permissions", perms("SALES")))
                .andExpect(status().isCreated());
        String cashier = login(email, EMPLOYEE_PASSWORD);

        // the cashier may not list customers, but the card itself names the customer for the till
        get(cashier, "/api/customers").andExpect(status().isForbidden());
        get(cashier, "/api/loyalty-cards/lookup?phone=0504442211").andExpect(jsonPath("$[0].customerId", is(card.get("customerId").asText())))
                .andExpect(jsonPath("$[0].discountPercent", is(0.0)));
        // a scanned card number, however it is cased; an unknown one is 404
        String number = card.get("number").asText();
        get(cashier, "/api/loyalty-cards/by-number/" + number.toLowerCase()).andExpect(jsonPath("$.fullName", is("Quliyeva Aysel")))
                .andExpect(jsonPath("$.active", is(true)));
        get(cashier, "/api/loyalty-cards/by-number/HP-999999").andExpect(status().isNotFound());
        String other = register("Başqa Mağaza");
        activateSubscription(other, "MONTHLY");
        get(other, "/api/loyalty-cards/by-number/" + number).andExpect(status().isNotFound());
    }
}
