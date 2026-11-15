package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Automatic debt reminders: due-soon and overdue customers land on the list once, with a ready message. */
class ReminderTest extends IntegrationTestBase {

    private void charge(String token, UUID c, String amount, LocalDate date, LocalDate due) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("amount", amount, "date", date.toString(), "dueDate", due.toString()));
        post(token, "/api/customers/" + c + "/charge", b).andExpect(status().isOk());
    }

    @Test
    void overdueAndDueSoonCustomersGetOneReminderEach() throws Exception {
        String owner = register("Xatırlatma Mağaza");
        activateSubscription(owner, "MONTHLY");
        LocalDate today = LocalDate.now(clock);
        UUID late = createCustomer(owner, "Gecikən Əli", "+994501290001");
        UUID soon = createCustomer(owner, "Sabah Leyla", "+994501290002");
        UUID far = createCustomer(owner, "Uzaq Vaqif", "+994501290003");
        charge(owner, late, "80.00", today.minusDays(20), today.minusDays(3));
        charge(owner, soon, "40.00", today, today.plusDays(1));
        charge(owner, far, "10.00", today, today.plusDays(20));

        JsonNode list = body(get(owner, "/api/reminders").andExpect(jsonPath("$.length()", is(2))));
        JsonNode overdue = null;
        for (JsonNode r : list) if ("OVERDUE".equals(r.get("kind").asText())) overdue = r;
        assertEquals("Gecikən Əli", overdue.get("customerName").asText());
        assertEquals(80.0, overdue.get("amount").asDouble());
        org.hamcrest.MatcherAssert.assertThat(overdue.get("message").asText(), containsString("keçib"));

        // opening the list again does not create duplicates
        get(owner, "/api/reminders").andExpect(jsonPath("$.length()", is(2)));
        post(owner, "/api/reminders/" + overdue.get("id").asText() + "/sent", Map.of("channel", "WHATSAPP")).andExpect(status().isOk());
        get(owner, "/api/reminders").andExpect(jsonPath("$.length()", is(1)));
        get(owner, "/api/reminders?status=SENT").andExpect(jsonPath("$[0].channel", is("WHATSAPP")));

        // the overdue one comes back after the repeat window (3 days by default)
        clock.setDate(today.plusDays(3));
        get(owner, "/api/reminders").andExpect(jsonPath("$[?(@.kind == 'OVERDUE')]", hasSize(2)));

        // turned off: nothing new
        put(owner, "/api/settings/reminders", Map.of("enabled", false, "daysBefore", 1, "repeatDays", 3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)));
    }
}
