package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer / company as one account: "Borc əlavə et" and "Borc ödə". */
class PartyAccountTest extends IntegrationTestBase {

    private static Map<String, Object> charge(String amount, Object expected) {
        Map<String, Object> b = new HashMap<>();
        b.put("amount", amount);
        if (expected != null) b.put("expectedRemaining", expected);
        return b;
    }

    private static Map<String, Object> payBody(String amount, String method, Object expected) {
        Map<String, Object> b = new HashMap<>();
        b.put("amount", amount);
        b.put("paymentMethod", method);
        if (expected != null) b.put("expectedRemaining", expected);
        return b;
    }

    private JsonNode profile(String token, UUID customer) throws Exception {
        return body(get(token, "/api/customers/" + customer + "/profile"));
    }

    @Test
    void firstChargeCreatesTheAccountAndNextChargesAddToIt() throws Exception {
        String owner = register("Hesab Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Ramil", "+994501240001");

        Map<String, Object> first = charge("500.00", "0.00");
        first.put("receiptNumber", "Ç-1");
        first.put("note", "Ərzaq");
        post(owner, "/api/customers/" + c + "/charge", first).andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingBefore", is(0.0)))
                .andExpect(jsonPath("$.remainingAfter", is(500.0)));

        post(owner, "/api/customers/" + c + "/charge", charge("200.00", "500.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAfter", is(700.0)));

        JsonNode p = profile(owner, c);
        assertEquals(1, p.get("debts").size(), "charges go onto the same account, not new separate debts");
        assertEquals(700.0, p.get("totals").get("remaining").asDouble());
    }

    @Test
    void payReducesBalanceAndCannotExceedIt() throws Exception {
        String owner = register("Ödəmə Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Lalə", "+994501240002");
        post(owner, "/api/customers/" + c + "/charge", charge("1000.00", null)).andExpect(status().isOk());

        post(owner, "/api/customers/" + c + "/pay", payBody("300.00", "CASH", "1000.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAfter", is(700.0)))
                .andExpect(jsonPath("$.payments", hasSize(1)));
        post(owner, "/api/customers/" + c + "/pay", payBody("701.00", "CARD", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("PAYMENT_EXCEEDS_BALANCE")));
        post(owner, "/api/customers/" + c + "/pay", payBody("700.00", "CARD", "700.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAfter", is(0.0)));
        post(owner, "/api/customers/" + c + "/pay", payBody("1.00", "CASH", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("NO_DEBT")));

        // the paid account takes new debt again
        post(owner, "/api/customers/" + c + "/charge", charge("50.00", "0.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAfter", is(50.0)));
        assertEquals(50.0, profile(owner, c).get("totals").get("remaining").asDouble());
    }

    @Test
    void payGoesToOldestDebtsFirstAcrossOldSeparateDebts() throws Exception {
        String owner = register("Köhnə Borclar");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Tural", "+994501240003");
        UUID older = debtIdOf(owner, "CUSTOMER_DEBT", c, "100.00");
        UUID newer = debtIdOf(owner, "CUSTOMER_DEBT", c, "200.00");

        post(owner, "/api/customers/" + c + "/pay", payBody("150.00", "CASH", "300.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments", hasSize(2)))
                .andExpect(jsonPath("$.remainingAfter", is(150.0)));
        get(owner, "/api/debts/" + older).andExpect(jsonPath("$.status", is("PAID")));
        get(owner, "/api/debts/" + newer).andExpect(jsonPath("$.remainingAmount", is(150.0)));
    }

    @Test
    void staleScreenOrDoubleTapIsRefused() throws Exception {
        String owner = register("İkiqat");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Fuad", "+994501240004");
        post(owner, "/api/customers/" + c + "/charge", charge("100.00", "0.00")).andExpect(status().isOk());
        post(owner, "/api/customers/" + c + "/charge", charge("100.00", "0.00"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("STALE_BALANCE")));
        post(owner, "/api/customers/" + c + "/pay", payBody("40.00", "CASH", "100.00")).andExpect(status().isCreated());
        post(owner, "/api/customers/" + c + "/pay", payBody("40.00", "CASH", "100.00"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("STALE_BALANCE")));
        assertEquals(60.0, profile(owner, c).get("totals").get("remaining").asDouble());
    }

    @Test
    void companyAccountNeedsDirectionAndKeepsDirectionsSeparate() throws Exception {
        String owner = register("Firma Hesabı");
        activateSubscription(owner, "MONTHLY");
        UUID co = createCompany(owner, "Bakı Topdan MMC");

        post(owner, "/api/companies/" + co + "/charge", charge("100.00", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("DEBT_TYPE_REQUIRED")));

        Map<String, Object> rec = charge("400.00", "0.00");
        rec.put("debtType", "COMPANY_RECEIVABLE");
        post(owner, "/api/companies/" + co + "/charge", rec).andExpect(status().isOk());
        Map<String, Object> pay = charge("250.00", "0.00");
        pay.put("debtType", "COMPANY_PAYABLE");
        post(owner, "/api/companies/" + co + "/charge", pay).andExpect(status().isOk());

        Map<String, Object> p = payBody("100.00", "TRANSFER", "250.00");
        p.put("debtType", "COMPANY_PAYABLE");
        post(owner, "/api/companies/" + co + "/pay", p).andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAfter", is(150.0)));

        JsonNode prof = body(get(owner, "/api/companies/" + co + "/profile"));
        assertEquals(2, prof.get("debts").size());
        for (JsonNode d : prof.get("debts")) {
            if ("COMPANY_RECEIVABLE".equals(d.get("debtType").asText())) assertEquals(400.0, d.get("remainingAmount").asDouble());
            else assertEquals(150.0, d.get("remainingAmount").asDouble());
        }
    }

    @Test
    void otherStoreAndEmployeeWithoutPermissionAreBlocked() throws Exception {
        String owner = register("Qorunan");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Gizli", "+994501240005");
        post(owner, "/api/customers/" + c + "/charge", charge("80.00", null)).andExpect(status().isOk());

        String other = register("Yad Mağaza");
        activateSubscription(other, "MONTHLY");
        post(other, "/api/customers/" + c + "/charge", charge("10.00", null)).andExpect(status().isNotFound());
        post(other, "/api/customers/" + c + "/pay", payBody("10.00", "CASH", null)).andExpect(status().isNotFound());

        String email = uniqueEmail("firma-isci");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Firma İşçisi",
                "permissions", perms("COMPANY_PAYABLES"))).andExpect(status().isCreated());
        String employee = login(email, EMPLOYEE_PASSWORD);
        post(employee, "/api/customers/" + c + "/pay", payBody("10.00", "CASH", null)).andExpect(status().isForbidden());

        assertEquals(80.0, profile(owner, c).get("totals").get("remaining").asDouble());
    }
}
