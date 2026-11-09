package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HesabPro 2.0: dashboard cards and lists, customer card (code, notes, history, contact), portal, PDF, Excel, activity. */
class HesabPro2Test extends IntegrationTestBase {

    private Map<String, Object> charge(String amount, String type) {
        Map<String, Object> b = new HashMap<>();
        b.put("amount", amount);
        if (type != null) b.put("debtType", type);
        return b;
    }

    private Map<String, Object> pay(String amount, String method, String type) {
        Map<String, Object> b = new HashMap<>();
        b.put("amount", amount);
        b.put("paymentMethod", method);
        if (type != null) b.put("debtType", type);
        return b;
    }

    @Test
    void dashboardCardsEqualTheirListsAndNeverMixIncomingWithOutgoing() throws Exception {
        String owner = register("Panel 2.0");
        activateSubscription(owner, "MONTHLY");
        UUID c1 = createCustomer(owner, "Aynur Əliyeva", "+994501250001");
        UUID c2 = createCustomer(owner, "Bəxtiyar", "+994501250002");
        UUID co = createCompany(owner, "Təchizat MMC");

        post(owner, "/api/customers/" + c1 + "/charge", charge("300.00", null)).andExpect(status().isOk());
        post(owner, "/api/customers/" + c2 + "/charge", charge("100.00", null)).andExpect(status().isOk());
        post(owner, "/api/customers/" + c2 + "/pay", pay("100.00", "CASH", null)).andExpect(status().isCreated()); // closed
        post(owner, "/api/customers/" + c1 + "/pay", pay("50.00", "CARD", null)).andExpect(status().isCreated());
        post(owner, "/api/companies/" + co + "/charge", charge("1000.00", "COMPANY_PAYABLE")).andExpect(status().isOk());
        post(owner, "/api/companies/" + co + "/pay", pay("400.00", "TRANSFER", "COMPANY_PAYABLE")).andExpect(status().isCreated());
        post(owner, "/api/companies/" + co + "/charge", charge("80.00", "COMPANY_RECEIVABLE")).andExpect(status().isOk());

        JsonNode o = body(get(owner, "/api/dashboard/overview").andExpect(status().isOk()));
        assertEquals(250.0, o.get("customerDebts").get("sum").asDouble());
        assertEquals(1, o.get("customerDebts").get("count").asLong());
        assertEquals(600.0, o.get("companyPayables").get("sum").asDouble(), "Toplam borcum: only what the store owes");
        assertEquals(80.0, o.get("companyReceivables").get("sum").asDouble());
        assertEquals(400.0, o.get("paymentsOut").get("sum").asDouble(), "Ödədiyim: only payments the store made");
        assertEquals(150.0, o.get("paymentsIn").get("sum").asDouble());
        assertEquals(3, o.get("activeDebts").get("count").asLong());
        assertEquals(1, o.get("closedDebts").get("count").asLong());
        assertEquals(4, o.get("todayDebts").get("count").asLong());
        assertEquals(1480.0, o.get("todayDebts").get("sum").asDouble());

        String[][] pairs = {{"customer-debts", "customerDebts"}, {"company-payables", "companyPayables"},
                {"company-receivables", "companyReceivables"}, {"payments-out", "paymentsOut"}, {"payments-in", "paymentsIn"},
                {"active-debts", "activeDebts"}, {"closed-debts", "closedDebts"}, {"today-debts", "todayDebts"},
                {"active-customers", "activeCustomers"}, {"overdue-debts", "overdueDebts"}};
        for (String[] p : pairs) {
            JsonNode l = body(get(owner, "/api/dashboard/lists/" + p[0]).andExpect(status().isOk()));
            assertEquals(o.get(p[1]).get("count").asLong(), l.get("total").asLong(), p[0] + " count");
            assertEquals(o.get(p[1]).get("sum").asDouble(), l.get("sum").asDouble(), p[0] + " sum");
            assertEquals(l.get("total").asLong(), l.get("items").size(), p[0] + " items");
        }
        // the classic dashboard counts only money that came IN: the 400 TRANSFER paid to the supplier is not "received"
        get(owner, "/api/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.cashReceived", is(100.0)))
                .andExpect(jsonPath("$.summary.cardReceived", is(50.0)))
                .andExpect(jsonPath("$.summary.otherReceived", is(0.0)))
                .andExpect(jsonPath("$.summary.todayTotal", is(150.0)));
        get(owner, "/api/dashboard/lists/active-debts?partyKind=COMPANY").andExpect(jsonPath("$.total", is(2)));
        get(owner, "/api/dashboard/lists/payments-out").andExpect(jsonPath("$.items[0].recordedByName", notNullValue()));
        get(owner, "/api/dashboard/lists/nonsense").andExpect(status().isBadRequest());
    }

    @Test
    void overdueDebtsCountDaysFromTheDueDate() throws Exception {
        String owner = register("Gecikmə");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Gecikən", "+994501250003");
        Map<String, Object> b = charge("70.00", null);
        b.put("date", LocalDate.now(clock).minusDays(20).toString());
        b.put("dueDate", LocalDate.now(clock).minusDays(5).toString());
        post(owner, "/api/customers/" + c + "/charge", b).andExpect(status().isOk());

        get(owner, "/api/dashboard/lists/overdue-debts").andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].overdueDays", is(5)))
                .andExpect(jsonPath("$.sum", is(70.0)));
        get(owner, "/api/customers").andExpect(jsonPath("$[0].overdueDebts", is(1)));
    }

    @Test
    void customersGetUniqueCodesAndCanBeFoundByCodePhoneAndReceipt() throws Exception {
        String owner = register("Kod Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID a = createCustomer(owner, "Kod Bir", "+994501250004");
        UUID b = createCustomer(owner, "Kod İki", "+994501250005");
        String codeA = body(get(owner, "/api/customers/" + a + "/profile")).get("party").get("code").asText();
        String codeB = body(get(owner, "/api/customers/" + b + "/profile")).get("party").get("code").asText();
        assertTrue(codeA.matches("HP-\\d{6}"), codeA);
        assertTrue(codeB.matches("HP-\\d{6}"), codeB);
        assertNotEquals(codeA, codeB);

        get(owner, "/api/customers?q=" + codeB).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name", is("Kod İki")));
        Map<String, Object> ch = charge("10.00", null);
        ch.put("receiptNumber", "ÇEK-4455");
        post(owner, "/api/customers/" + a + "/charge", ch).andExpect(status().isOk());
        get(owner, "/api/dashboard/lists/customer-debts?q=4455").andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].code", is(codeA)));
        get(owner, "/api/dashboard/lists/customer-debts?q=" + codeA).andExpect(jsonPath("$.total", is(1)));
        get(owner, "/api/dashboard/lists/today-debts?q=501250004").andExpect(jsonPath("$.total", is(1)));
    }

    @Test
    void historyNotesAndContactAreRecorded() throws Exception {
        String owner = register("Tarixçə 2.0");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Səməd", "+994501250006");
        post(owner, "/api/customers/" + c + "/charge", charge("200.00", null)).andExpect(status().isOk());
        Map<String, Object> more = charge("50.00", null);
        more.put("receiptNumber", "Ç-9");
        more.put("note", "Un");
        post(owner, "/api/customers/" + c + "/charge", more).andExpect(status().isOk());
        post(owner, "/api/customers/" + c + "/pay", pay("120.00", "CASH", null)).andExpect(status().isCreated());

        JsonNode h = body(get(owner, "/api/customers/" + c + "/history").andExpect(status().isOk()));
        assertEquals(3, h.size());
        assertEquals("DEBT_CREATED", h.get(0).get("type").asText());
        assertEquals(200.0, h.get(0).get("balanceAfter").asDouble());
        assertEquals("DEBT_INCREASED", h.get(1).get("type").asText());
        assertEquals("Ç-9", h.get(1).get("receiptNumber").asText());
        assertEquals(250.0, h.get(1).get("balanceAfter").asDouble());
        assertEquals("PAYMENT_CASH", h.get(2).get("type").asText());
        assertEquals(130.0, h.get(2).get("balanceAfter").asDouble());
        assertNotNull(h.get(2).get("actorName").asText());

        post(owner, "/api/customers/" + c + "/notes", Map.of("body", "Ayın sonunda ödəyəcək")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName", notNullValue()));
        post(owner, "/api/customers/" + c + "/notes", Map.of("body", "  ")).andExpect(status().isBadRequest());
        get(owner, "/api/customers/" + c + "/notes").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].body", is("Ayın sonunda ödəyəcək")));

        post(owner, "/api/customers/" + c + "/contact", Map.of("channel", "WHATSAPP")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastContactAt", notNullValue()));
        post(owner, "/api/customers/" + c + "/contact", Map.of("channel", "SMS")).andExpect(status().isBadRequest());

        String other = register("Yad 2.0");
        activateSubscription(other, "MONTHLY");
        get(other, "/api/customers/" + c + "/notes").andExpect(status().isNotFound());
        get(other, "/api/customers/" + c + "/history").andExpect(status().isNotFound());
        post(other, "/api/customers/" + c + "/notes", Map.of("body", "x")).andExpect(status().isNotFound());
    }

    @Test
    void customerPortalShowsOnlyThatCustomerAndCanBeRevokedOrExpire() throws Exception {
        String owner = register("Kabinet");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Portal Müştərisi", "+994501250007");
        UUID other = createCustomer(owner, "Başqa Müştəri", "+994501250008");
        post(owner, "/api/customers/" + c + "/charge", charge("90.00", null)).andExpect(status().isOk());
        post(owner, "/api/customers/" + other + "/charge", charge("555.00", null)).andExpect(status().isOk());

        JsonNode link = body(post(owner, "/api/customers/" + c + "/portal-links", Map.of("days", 7)).andExpect(status().isCreated()));
        String url = link.get("url").asText();
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertTrue(token.length() >= 40, "random 256-bit token");

        get(null, "/api/public/portal/" + token).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName", is("Portal Müştərisi")))
                .andExpect(jsonPath("$.remaining", is(90.0)))
                .andExpect(jsonPath("$.debts", hasSize(1)))
                .andExpect(jsonPath("$.history", hasSize(1)));
        byte[] pdf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/public/portal/" + token + "/statement")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        get(null, "/api/public/portal/" + token.substring(0, token.length() - 2) + "xx").andExpect(status().isNotFound());
        get(null, "/api/public/portal/short").andExpect(status().isNotFound());

        UUID linkId = UUID.fromString(link.get("id").asText());
        get(owner, "/api/customers/" + c + "/portal-links").andExpect(jsonPath("$[0].active", is(true)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/customers/" + c + "/portal-links/" + linkId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        get(null, "/api/public/portal/" + token).andExpect(status().isNotFound());

        JsonNode l2 = body(post(owner, "/api/customers/" + c + "/portal-links", Map.of("days", 1)));
        String t2 = l2.get("url").asText().substring(l2.get("url").asText().lastIndexOf('/') + 1);
        clock.setDate(LocalDate.now().plusDays(3));
        get(null, "/api/public/portal/" + t2).andExpect(status().isNotFound());
        clock.reset();

        post(owner, "/api/customers/" + c + "/portal-links", Map.of("days", 0)).andExpect(status().isBadRequest());
        String stranger = register("Kənar");
        activateSubscription(stranger, "MONTHLY");
        post(stranger, "/api/customers/" + c + "/portal-links", Map.of("days", 5)).andExpect(status().isNotFound());
    }

    @Test
    void statementPdfAndExcelExportsAreReal() throws Exception {
        String owner = register("Hesabat 2.0");
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Çıxarış Şəxsi", "+994501250009");
        UUID co = createCompany(owner, "Ödəniş MMC");
        post(owner, "/api/customers/" + c + "/charge", charge("40.00", null)).andExpect(status().isOk());
        post(owner, "/api/companies/" + co + "/charge", charge("15.00", "COMPANY_PAYABLE")).andExpect(status().isOk());

        byte[] pdf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/customers/" + c + "/statement").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        byte[] pdf2 = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/companies/" + co + "/statement").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf2, 0, 4, StandardCharsets.US_ASCII));

        for (String path : new String[]{"/api/reports/customers/export", "/api/reports/companies/export"}) {
            byte[] x = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get(path).header("Authorization", "Bearer " + owner))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertEquals('P', (char) x[0]);
            assertEquals('K', (char) x[1]); // xlsx = zip
        }
    }

    @Test
    void activityIsOwnerOnlyAndCountsEachEmployee() throws Exception {
        String owner = register("Fəaliyyət");
        activateSubscription(owner, "MONTHLY");
        String email = uniqueEmail("kassir");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Kassir Lalə",
                "permissions", perms("CUSTOMER_DEBTS"))).andExpect(status().isCreated());
        String employee = login(email, EMPLOYEE_PASSWORD);
        UUID c = createCustomer(owner, "Alıcı", "+994501250010");
        post(employee, "/api/customers/" + c + "/charge", charge("60.00", null)).andExpect(status().isOk());
        post(employee, "/api/customers/" + c + "/pay", pay("25.00", "CASH", null)).andExpect(status().isCreated());

        JsonNode a = body(get(owner, "/api/activity").andExpect(status().isOk()));
        JsonNode kassir = null;
        for (JsonNode e : a.get("employees")) if ("Kassir Lalə".equals(e.get("fullName").asText())) kassir = e;
        assertNotNull(kassir);
        assertEquals(1, kassir.get("debtEntries").asLong());
        assertEquals(60.0, kassir.get("debtAmount").asDouble());
        assertEquals(1, kassir.get("paymentsReceived").asLong());
        assertEquals(25.0, kassir.get("paymentsAmount").asDouble());
        assertTrue(a.get("entries").size() >= 3);

        get(employee, "/api/activity").andExpect(status().isForbidden());
        get(employee, "/api/dashboard/overview").andExpect(status().isForbidden()); // no REPORTS permission
        get(employee, "/api/dashboard/lists/active-debts").andExpect(status().isForbidden());
    }
}
