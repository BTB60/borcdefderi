package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Admin "Bitən abunəliklər": stores close to the end date and the record of who was already reminded. */
class SubscriptionReminderTest extends IntegrationTestBase {

    @Test
    void adminSeesExpiringStoreAndRecordsReminder() throws Exception {
        String name = "Xatırlatma " + UUID.randomUUID().toString().substring(0, 8);
        String owner = register(name);
        activateSubscription(owner, "MONTHLY");
        LocalDate end = LocalDate.parse(body(get(owner, "/api/subscriptions/current")).get("endDate").asText());
        clock.setDate(end.minusDays(3));

        String admin = adminToken();
        String url = "/api/admin/stores?q=" + name;
        String storeId = body(get(admin, url)).get(0).get("id").asText();
        get(admin, url).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].daysRemaining", is(3)))
                .andExpect(jsonPath("$[0].expiring", is(true)))
                .andExpect(jsonPath("$[0].lastRemindedAt").doesNotExist());

        post(admin, "/api/admin/stores/" + storeId + "/remind", Map.of("channel", "WHATSAPP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindedAt", notNullValue()));
        get(admin, url).andExpect(jsonPath("$[0].lastRemindedAt", notNullValue()));
        get(admin, "/api/admin/audit-logs?storeId=" + storeId)
                .andExpect(jsonPath("$[0].action", is("SUBSCRIPTION_REMINDED")));

        post(admin, "/api/admin/stores/" + storeId + "/remind", Map.of("channel", "SMS")).andExpect(status().isBadRequest());
        post(admin, "/api/admin/stores/" + UUID.randomUUID() + "/remind", Map.of("channel", "PHONE"))
                .andExpect(status().isNotFound());
        post(owner, "/api/admin/stores/" + storeId + "/remind", Map.of("channel", "PHONE"))
                .andExpect(status().isForbidden());
    }
}
