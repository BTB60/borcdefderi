package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Taksit qrafiki: equal parts with dates; payments after the start cover the parts in order; late parts show. */
class InstallmentTest extends IntegrationTestBase {

    @Test
    void paymentsCoverThePartsInOrderAndLatePartsShow() throws Exception {
        String owner = register("Taksit Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID customer = createCustomer(owner, "Taksit Müştərisi", "+994501234567");
        UUID debt = UUID.fromString(body(createDebt(owner, "CUSTOMER_DEBT", customer, "600.00").andExpect(status().isCreated())).get("id").asText());
        String base = "/api/customers/" + customer + "/installments";
        LocalDate today = LocalDate.now(clock);

        get(owner, base).andExpect(jsonPath("$.plan").doesNotExist()).andExpect(jsonPath("$.openBalance", is(600.0)));
        // checks
        post(owner, base, Map.of("count", 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_COUNT")));
        post(owner, base, Map.of("count", 3, "total", "700")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("TOTAL_TOO_BIG")));

        // 3 parts from today: 200 + 200 + 200 (the whole open balance by default)
        post(owner, base, Map.of("count", 3, "firstDate", today.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(600.0))).andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].amount", is(200.0))).andExpect(jsonPath("$.items[0].status", is("DUE_SOON")))
                .andExpect(jsonPath("$.items[1].dueDate", is(today.plusMonths(1).toString())));

        // 250 paid: part 1 paid, part 2 has 50
        pay(owner, debt, "250.00", "CASH").andExpect(status().isCreated());
        get(owner, base).andExpect(jsonPath("$.plan.paid", is(250.0))).andExpect(jsonPath("$.plan.left", is(350.0)))
                .andExpect(jsonPath("$.plan.items[0].status", is("PAID"))).andExpect(jsonPath("$.plan.items[1].paid", is(50.0)))
                .andExpect(jsonPath("$.plan.next.seq", is(2)));

        // 40 days later part 2 is late with 150 left
        clock.setDate(today.plusDays(40));
        get(owner, base).andExpect(jsonPath("$.plan.items[1].status", is("OVERDUE")))
                .andExpect(jsonPath("$.plan.overdue", is(150.0))).andExpect(jsonPath("$.plan.items[1].daysLate", greaterThan(5)));
        get(owner, "/api/installments").andExpect(jsonPath("$[0].customerName", is("Taksit Müştərisi")))
                .andExpect(jsonPath("$[0].overdue", is(150.0)));

        // the rest paid: completed
        pay(owner, debt, "350.00", "CASH").andExpect(status().isCreated());
        get(owner, base).andExpect(jsonPath("$.plan.status", is("COMPLETED"))).andExpect(jsonPath("$.plan.overdue", is(0.0)));

        // closing removes it from the page
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base)
                .header("Authorization", "Bearer " + owner)).andExpect(jsonPath("$.closed", is(true)));
        get(owner, "/api/installments").andExpect(jsonPath("$", hasSize(0)));
    }
}
