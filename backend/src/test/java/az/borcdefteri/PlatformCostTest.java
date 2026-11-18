package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Platforma xərcləri: the admin keeps the platform's own bills — due dates, payments, cost vs income. */
class PlatformCostTest extends IntegrationTestBase {

    @Test
    void adminTracksServerDatabaseAndDomainBills() throws Exception {
        String admin = adminToken();
        LocalDate today = LocalDate.now(clock);

        // the services HesabPro runs on are there from the start (free = 0)
        get(admin, "/api/admin/costs").andExpect(status().isOk())
                .andExpect(jsonPath("$.costs[*].provider", hasItems("Render", "Neon", "Hostinger", "Vercel", "Brevo")))
                .andExpect(jsonPath("$.costs[?(@.provider == 'Render')].status", contains("FREE")));

        // Render Starter: 7 USD a month, due in 3 days
        String render = body(get(admin, "/api/admin/costs")).get("costs").get(0).get("id").asText();
        Map<String, Object> starter = new HashMap<>(Map.of("name", "Server (backend API)", "provider", "Render", "amount", "7.00",
                "currency", "USD", "period", "MONTHLY", "nextDue", today.plusDays(3).toString()));
        put(admin, "/api/admin/costs/" + render, starter).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SOON"))).andExpect(jsonPath("$.daysLeft", is(3)))
                .andExpect(jsonPath("$.monthlyAzn", is(11.9)));

        // a yearly domain: 12 USD → 1.70 AZN a month
        String domain = body(post(admin, "/api/admin/costs", Map.of("name", "Domen test", "provider", "Hostinger", "amount", "12",
                "currency", "USD", "period", "YEARLY", "nextDue", today.minusDays(1).toString()))).get("id").asText();
        get(admin, "/api/admin/costs").andExpect(jsonPath("$.overdue", is(1))).andExpect(jsonPath("$.dueSoon", is(1)))
                .andExpect(jsonPath("$.monthlyCostAzn", is(13.6)));

        // paid: the payment is kept and the date moves a year on
        post(admin, "/api/admin/costs/" + domain + "/paid", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextDue", is(today.minusDays(1).plusYears(1).toString())))
                .andExpect(jsonPath("$.status", is("OK"))).andExpect(jsonPath("$.payments", hasSize(1)))
                .andExpect(jsonPath("$.payments[0].amount", is(12.0)));
        get(admin, "/api/admin/costs").andExpect(jsonPath("$.paidThisYearAzn", is(20.4)));

        // a bad link is refused; a cost with payments is only switched off
        post(admin, "/api/admin/costs", Map.of("name", "X", "url", "javascript:alert(1)")).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/costs/" + domain)
                .header("Authorization", "Bearer " + admin)).andExpect(jsonPath("$.deleted", is(false)));

        // store owners never see it
        String owner = register("Xərc Mağazası");
        get(owner, "/api/admin/costs").andExpect(status().isForbidden());
    }
}
