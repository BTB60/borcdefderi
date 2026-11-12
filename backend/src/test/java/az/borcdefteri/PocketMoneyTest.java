package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** "Cib pulu": money added and spent per day, carried over, private per user, separate from kassa and expenses. */
class PocketMoneyTest extends IntegrationTestBase {

    @Test
    void dayTotalsCarryOverAndStayPrivate() throws Exception {
        String owner = register("Cib Mağazası");
        activateSubscription(owner, "MONTHLY");
        LocalDate today = LocalDate.now(clock);
        String yesterday = today.minusDays(1).toString();

        post(owner, "/api/pocket", Map.of("kind", "IN", "amount", "50.00", "date", yesterday)).andExpect(status().isCreated());
        post(owner, "/api/pocket", Map.of("kind", "OUT", "amount", "20.00", "note", "Nahar", "date", yesterday))
                .andExpect(status().isCreated());
        post(owner, "/api/pocket", Map.of("kind", "IN", "amount", "100.00")).andExpect(status().isCreated());
        JsonNode taxi = body(post(owner, "/api/pocket", Map.of("kind", "OUT", "amount", "15.50", "note", "Taksi"))
                .andExpect(status().isCreated()));
        post(owner, "/api/pocket", Map.of("kind", "OUT", "amount", "500")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("POCKET_INSUFFICIENT")));
        post(owner, "/api/pocket", Map.of("kind", "OUT", "amount", "-1")).andExpect(status().isBadRequest());
        post(owner, "/api/pocket", Map.of("kind", "IN", "amount", "1", "date", today.plusDays(1).toString()))
                .andExpect(status().isBadRequest());

        get(owner, "/api/pocket").andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(30.0)))
                .andExpect(jsonPath("$.added", is(100.0)))
                .andExpect(jsonPath("$.spent", is(15.5)))
                .andExpect(jsonPath("$.closingBalance", is(114.5)))
                .andExpect(jsonPath("$.entries", hasSize(2)));
        get(owner, "/api/pocket/history?days=7").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].closingBalance", is(114.5)))
                .andExpect(jsonPath("$[1].closingBalance", is(30.0)));

        // Separate from expenses: nothing was written there.
        get(owner, "/api/expenses").andExpect(status().isOk()).andExpect(jsonPath("$.total", is(0)));

        // An employee of the same store has an empty pocket and cannot delete the owner's entry.
        String email = uniqueEmail("cib");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Cib İşçi",
                "permissions", perms("SALES"))).andExpect(status().isCreated());
        String employee = login(email, EMPLOYEE_PASSWORD);
        get(employee, "/api/pocket").andExpect(jsonPath("$.closingBalance", is(0.0))).andExpect(jsonPath("$.entries", hasSize(0)));
        mvc.perform(delete("/api/pocket/" + taxi.get("id").asText()).header("Authorization", "Bearer " + employee))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/pocket/" + taxi.get("id").asText()).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        get(owner, "/api/pocket").andExpect(jsonPath("$.closingBalance", is(130.0)));
    }
}
