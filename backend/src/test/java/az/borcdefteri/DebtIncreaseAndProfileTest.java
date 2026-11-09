package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Borc artır" (customer took more on credit) and the customer/company profile page. */
class DebtIncreaseAndProfileTest extends IntegrationTestBase {

    private Map<String, Object> increase(String amount, long version, String receipt, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("version", version);
        if (receipt != null) body.put("receiptNumber", receipt);
        if (note != null) body.put("note", note);
        return body;
    }

    private long versionOf(String token, UUID debt) throws Exception {
        return body(get(token, "/api/debts/" + debt)).get("version").asLong();
    }

    @Test
    void increaseAddsToAmountAndRemainingKeepsPaidAndRecordsHistory() throws Exception {
        String owner = register("Artım Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Elvin Məmmədov", "+994501230001");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "500.00");

        pay(owner, debt, "100.00", "CASH").andExpect(status().isCreated());

        post(owner, "/api/debts/" + debt + "/increase", increase("200.00", versionOf(owner, debt), "ÇEK-77", "2 kisə un"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(700.0)))
                .andExpect(jsonPath("$.paidAmount", is(100.0)))
                .andExpect(jsonPath("$.remainingAmount", is(600.0)))
                .andExpect(jsonPath("$.status", is("PARTIAL")));

        // Further partial payment works on the increased balance: 600 - 300 = 300
        pay(owner, debt, "300.00", "CARD").andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAfter", is(300.0)));

        JsonNode history = body(get(owner, "/api/debts/" + debt + "/history"));
        JsonNode inc = null;
        for (JsonNode e : history) {
            if ("DEBT_INCREASED".equals(e.get("type").asText())) inc = e;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(inc, "increase must be in history");
        org.junit.jupiter.api.Assertions.assertEquals(200.0, inc.get("amount").asDouble());
        org.junit.jupiter.api.Assertions.assertEquals(600.0, inc.get("balanceAfter").asDouble());
        org.junit.jupiter.api.Assertions.assertEquals("ÇEK-77", inc.get("receiptNumber").asText());
        org.junit.jupiter.api.Assertions.assertEquals("2 kisə un", inc.get("note").asText());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.now(clock).toString(), inc.get("entryDate").asText());
        // the original creation entry is still there
        org.junit.jupiter.api.Assertions.assertEquals("DEBT_CREATED", history.get(0).get("type").asText());
    }

    @Test
    void increasingAPaidDebtReopensIt() throws Exception {
        String owner = register("Yenidən Açılma");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Nigar", "+994501230002");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "50.00");
        pay(owner, debt, "50.00", "CASH").andExpect(status().isCreated());

        post(owner, "/api/debts/" + debt + "/increase", increase("20.00", versionOf(owner, debt), null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(70.0)))
                .andExpect(jsonPath("$.remainingAmount", is(20.0)))
                .andExpect(jsonPath("$.status", is("PARTIAL")));
    }

    @Test
    void sameIncreaseSentTwiceIsAppliedOnlyOnce() throws Exception {
        String owner = register("İkiqat Basma");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Kamran", "+994501230003");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "100.00");
        long version = versionOf(owner, debt);

        post(owner, "/api/debts/" + debt + "/increase", increase("40.00", version, null, null)).andExpect(status().isOk());
        post(owner, "/api/debts/" + debt + "/increase", increase("40.00", version, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("VERSION_CONFLICT")));
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.amount", is(140.0)));
    }

    @Test
    void invalidIncreasesAreRejected() throws Exception {
        String owner = register("Yanlış Artım");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Səbinə", "+994501230004");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "10.00");
        long v = versionOf(owner, debt);

        post(owner, "/api/debts/" + debt + "/increase", increase("0.00", v, null, null)).andExpect(status().isBadRequest());
        post(owner, "/api/debts/" + debt + "/increase", increase("-5.00", v, null, null)).andExpect(status().isBadRequest());
        post(owner, "/api/debts/" + debt + "/increase", increase("1.234", v, null, null)).andExpect(status().isBadRequest());
        Map<String, Object> future = increase("5.00", v, null, null);
        future.put("date", LocalDate.now(clock).plusDays(2).toString());
        post(owner, "/api/debts/" + debt + "/increase", future)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("FUTURE_DATE")));
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.amount", is(10.0)));
    }

    @Test
    void anotherStoreCannotIncreaseOrSeeProfile() throws Exception {
        String owner = register("Mağaza A");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Gizli Müştəri", "+994501230005");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "80.00");

        String other = register("Mağaza B");
        activateSubscription(other, "MONTHLY");
        post(other, "/api/debts/" + debt + "/increase", increase("10.00", versionOf(owner, debt), null, null))
                .andExpect(status().isNotFound());
        get(other, "/api/customers/" + customer + "/profile").andExpect(status().isNotFound());
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.amount", is(80.0)));
    }

    @Test
    void employeeWithoutCustomerPermissionCannotIncrease() throws Exception {
        String owner = register("İcazə Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Orxan", "+994501230006");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "30.00");
        String email = uniqueEmail("isci");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "İşçi",
                "permissions", perms("COMPANY_PAYABLES"))).andExpect(status().isCreated());
        String employee = login(email, EMPLOYEE_PASSWORD);

        post(employee, "/api/debts/" + debt + "/increase", increase("5.00", versionOf(owner, debt), null, null))
                .andExpect(status().isForbidden());
        get(employee, "/api/customers/" + customer + "/profile").andExpect(status().isForbidden());
    }

    @Test
    void customerProfileShowsTotalsDebtsAndPayments() throws Exception {
        String owner = register("Profil Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Aysel Quliyeva", "+994501230007");
        UUID other = createCustomer(owner, "Başqa", "+994501230008");
        UUID d1 = debtIdOf(owner, "CUSTOMER_DEBT", customer, "1000.00");
        UUID d2 = debtIdOf(owner, "CUSTOMER_DEBT", customer, "200.00");
        debtIdOf(owner, "CUSTOMER_DEBT", other, "999.00");
        pay(owner, d1, "300.00", "CASH").andExpect(status().isCreated());
        pay(owner, d2, "200.00", "CARD").andExpect(status().isCreated());

        get(owner, "/api/customers/" + customer + "/profile").andExpect(status().isOk())
                .andExpect(jsonPath("$.party.name", is("Aysel Quliyeva")))
                .andExpect(jsonPath("$.totals.totalDebt", is(1200.0)))
                .andExpect(jsonPath("$.totals.paid", is(500.0)))
                .andExpect(jsonPath("$.totals.remaining", is(700.0)))
                .andExpect(jsonPath("$.totals.debtCount", is(2)))
                .andExpect(jsonPath("$.totals.openDebtCount", is(1)))
                .andExpect(jsonPath("$.debts", hasSize(2)))
                .andExpect(jsonPath("$.payments", hasSize(2)));
    }

    @Test
    void companyProfileSeparatesNothingButOnlyShowsAllowedSections() throws Exception {
        String owner = register("Firma Profili");
        activateSubscription(owner, "MONTHLY");
        UUID company = createCompany(owner, "Azər MMC");
        debtIdOf(owner, "COMPANY_RECEIVABLE", company, "400.00");
        debtIdOf(owner, "COMPANY_PAYABLE", company, "150.00");

        get(owner, "/api/companies/" + company + "/profile").andExpect(status().isOk())
                .andExpect(jsonPath("$.debts", hasSize(2)));

        String email = uniqueEmail("alacaq");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Alacaq İşçisi",
                "permissions", perms("COMPANY_RECEIVABLES"))).andExpect(status().isCreated());
        String employee = login(email, EMPLOYEE_PASSWORD);
        get(employee, "/api/companies/" + company + "/profile").andExpect(status().isOk())
                .andExpect(jsonPath("$.debts", hasSize(1)))
                .andExpect(jsonPath("$.debts[0].debtType", is("COMPANY_RECEIVABLE")))
                .andExpect(jsonPath("$.totals.totalDebt", is(400.0)));
    }
}
