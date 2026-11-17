package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Toplu WhatsApp: recipients by filter; ad günü: upcoming birthdays and the bell. */
class CampaignTest extends IntegrationTestBase {

    @Test
    void recipientsAndBirthdays() throws Exception {
        String owner = register("Kampaniya Mağaza");
        activateSubscription(owner, "MONTHLY");
        UUID clean = createCustomer(owner, "Təmiz Müştəri", "+994501110001");
        UUID debtor = createCustomer(owner, "Borclu Müştəri", "+994501110002");
        createDebt(owner, "CUSTOMER_DEBT", debtor, "25.00").andExpect(status().isCreated());

        assertThat(body(get(owner, "/api/campaign/recipients?filter=ALL")).size()).isEqualTo(2);
        assertThat(body(get(owner, "/api/campaign/recipients?filter=NO_DEBT")).findValuesAsText("name")).containsExactly("Təmiz Müştəri");
        assertThat(body(get(owner, "/api/campaign/recipients?filter=HAS_DEBT")).findValuesAsText("name")).containsExactly("Borclu Müştəri");
        assertThat(body(get(owner, "/api/campaign/recipients?filter=INACTIVE")).size()).isEqualTo(2);
        get(owner, "/api/campaign/recipients?filter=XYZ").andExpect(status().isBadRequest());

        LocalDate today = LocalDate.now(clock);
        put(owner, "/api/customers/" + clean + "/birthday", Map.of("birthday", today.minusYears(30).toString())).andExpect(status().isOk());
        put(owner, "/api/customers/" + debtor + "/birthday", Map.of("birthday", today.plusDays(3).minusYears(25).toString())).andExpect(status().isOk());
        JsonNode b = body(get(owner, "/api/campaign/birthdays?days=7"));
        assertThat(b.findValuesAsText("name")).containsExactly("Təmiz Müştəri", "Borclu Müştəri");
        assertThat(b.get(0).get("daysUntil").asLong()).isZero();
        assertThat(body(get(owner, "/api/notifications")).toString()).contains("Təmiz Müştəri");
        put(owner, "/api/customers/" + clean + "/birthday", Map.of("birthday", today.plusDays(1).toString())).andExpect(status().isBadRequest());

        post(owner, "/api/campaign/sent", Map.of("count", 2, "text", "Endirim var!")).andExpect(status().isOk());
    }
}
