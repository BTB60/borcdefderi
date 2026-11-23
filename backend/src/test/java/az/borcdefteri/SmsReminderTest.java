package az.borcdefteri;

import az.borcdefteri.notifications.ReminderService;
import az.borcdefteri.notifications.SmsGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Avtomatik SMS: with a provider and the store's switch on, the morning reminders go out by SMS by themselves. */
class SmsReminderTest extends IntegrationTestBase {

    @MockitoBean
    SmsGateway gateway;

    @Autowired
    ReminderService reminders;

    private UUID storeOf(String token) throws Exception {
        return UUID.fromString(body(get(token, "/api/auth/me")).get("storeId").asText());
    }

    @Test
    void remindersGoOutBySmsOnlyWhenTheStoreSwitchesItOn() throws Exception {
        String owner = register("SMS Mağaza");
        activateSubscription(owner, "MONTHLY");
        LocalDate today = LocalDate.now(clock);
        UUID late = createCustomer(owner, "Gecikən Əli", "050 129 00 01");
        UUID paid = createCustomer(owner, "Ödəyən Səbinə", "+994501290002");
        for (UUID c : new UUID[]{late, paid}) {
            post(owner, "/api/customers/" + c + "/charge", new HashMap<>(Map.of("amount", "80.00",
                    "date", today.minusDays(20).toString(), "dueDate", today.minusDays(3).toString()))).andExpect(status().isOk());
        }

        // no provider: the switch cannot be turned on, nothing is sent
        when(gateway.configured()).thenReturn(false);
        get(owner, "/api/settings/sms").andExpect(jsonPath("$.configured", is(false)));
        put(owner, "/api/settings/sms", Map.of("autoReminders", true)).andExpect(status().isConflict());
        post(owner, "/api/reminders/send-sms", Map.of()).andExpect(status().isConflict());

        when(gateway.configured()).thenReturn(true);
        UUID store = storeOf(owner);
        reminders.daily(); // switch still off: reminders are made, no SMS
        verify(gateway, never()).send(anyString(), anyString());
        get(owner, "/api/reminders").andExpect(jsonPath("$.length()", is(2)));

        put(owner, "/api/settings/sms", Map.of("autoReminders", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoReminders", is(true))).andExpect(jsonPath("$.configured", is(true)));
        // the second customer pays before the SMS goes out: no SMS for them
        UUID debt = jdbcClient.sql("SELECT id FROM debts WHERE customer_id = :c").param("c", paid).query(UUID.class).single();
        pay(owner, debt, "80.00", "CASH").andExpect(status().isCreated());
        doThrow(new IllegalStateException("HTTP 500")).doNothing().when(gateway).send(anyString(), anyString());

        int sent = reminders.sendBySms(store); // provider refuses once
        assertEquals(0, sent);
        sent = reminders.sendBySms(store);
        assertEquals(1, sent);
        verify(gateway, times(2)).send(eq("994501290001"), contains("Gecikən Əli"));

        get(owner, "/api/reminders?status=SENT").andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].channel", is("SMS")));
        get(owner, "/api/sms").andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].status", is("SENT"))).andExpect(jsonPath("$[1].status", is("FAILED")));
        get(owner, "/api/settings/sms").andExpect(jsonPath("$.sentThisMonth", is(1)));

        post(owner, "/api/settings/sms/test", Map.of("phone", "abc")).andExpect(status().isBadRequest());
        post(owner, "/api/settings/sms/test", Map.of("phone", "+994 55 123 45 67")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sent", is(true)));
        verify(gateway).send(eq("994551234567"), anyString());
    }
}
