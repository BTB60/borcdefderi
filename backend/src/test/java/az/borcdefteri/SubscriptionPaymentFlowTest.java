package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Packages designed by the SUPER_ADMIN (period prices, colours, limits), payment & contact settings, and the
 * payment request flow: request -> approve / reject, with server-side prices and tenant isolation.
 */
class SubscriptionPaymentFlowTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    private Map<String, Object> planBody(String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", name);
        b.put("description", "Böyük mağazalar üçün");
        b.put("features", List.of("Limitsiz borc qeydi", "PDF və Excel hesabatlar", "  "));
        b.put("priceMonthly", "25.00");
        b.put("priceQuarterly", "70.00");
        b.put("priceSemiAnnual", "130.00");
        b.put("priceYearly", "240.00");
        b.put("customDays", 45);
        b.put("customPrice", "35.00");
        b.put("maxEmployees", 1);
        b.put("maxCustomers", 2);
        b.put("active", true);
        b.put("recommended", true);
        b.put("primaryColor", "#7c3aed");
        b.put("secondaryColor", "#D6AD60");
        b.put("backgroundColor", "#FFFFFF");
        b.put("textColor", "#101C35");
        b.put("buttonColor", "#7C3AED");
        return b;
    }

    private JsonNode createPlan(String name) throws Exception {
        return body(post(adminToken(), "/api/admin/plans", planBody(name)).andExpect(status().isCreated()));
    }

    private UUID storeIdOf(String token) throws Exception {
        return UUID.fromString(body(get(token, "/api/auth/me")).get("storeId").asText());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Baku"));
    }

    private JsonNode requestPayment(String token, String planCode, String period, String method, String declared)
            throws Exception {
        return body(mvc.perform(multipart("/api/subscriptions")
                        .file(new MockMultipartFile("receipt", "çek.png", "image/png", pngBytes()))
                        .param("planCode", planCode).param("period", period).param("paymentMethod", method)
                        .param("payerName", "Əli Həsənov").param("paidAmount", declared)
                        .param("paymentDate", today().toString()).param("note", "M10 ilə ödədim")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()));
    }

    // 1-3: create a package with period prices, palette colour and a custom HEX colour
    @Test
    void superAdminCreatesPackageWithPricesFeaturesAndColours() throws Exception {
        JsonNode plan = createPlan("Biznes " + UUID.randomUUID().toString().substring(0, 6));
        assertEquals("#7C3AED", plan.get("primaryColor").asText(), "HEX is stored upper-case");
        assertEquals(2, plan.get("features").size(), "blank advantages are dropped");
        assertEquals(5, plan.get("options").size());
        assertEquals(70.00, plan.get("options").get(1).get("price").asDouble());
        assertEquals(45, plan.get("options").get(4).get("days").asInt());

        get(null, "/api/public/plans").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + plan.get("code").asText() + "')].recommended", contains(true)))
                .andExpect(jsonPath("$[?(@.code=='" + plan.get("code").asText() + "')].buttonColor", contains("#7C3AED")));
    }

    @Test
    void invalidColourAndMissingPricesAreRejected() throws Exception {
        Map<String, Object> bad = planBody("Rəng");
        bad.put("primaryColor", "red");
        post(adminToken(), "/api/admin/plans", bad).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_COLOR")));

        Map<String, Object> noPrice = planBody("Qiymətsiz");
        for (String k : List.of("priceMonthly", "priceQuarterly", "priceSemiAnnual", "priceYearly", "customDays", "customPrice")) {
            noPrice.remove(k);
        }
        post(adminToken(), "/api/admin/plans", noPrice).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("NO_PRICE")));
    }

    @Test
    void onlySuperAdminManagesPackagesAndSettings() throws Exception {
        String owner = register("Paket icazə " + UUID.randomUUID());
        post(owner, "/api/admin/plans", planBody("Hack")).andExpect(status().isForbidden());
        put(owner, "/api/admin/settings/contact", Map.of("cardNumber", "4169 0000 0000 0000"))
                .andExpect(status().isForbidden());
    }

    @Test
    void editDeactivateReorderAndArchiveInsteadOfDeletingUsedPackage() throws Exception {
        JsonNode a = createPlan("Arxiv A");
        JsonNode b = createPlan("Arxiv B");
        String idA = a.get("id").asText();
        String idB = b.get("id").asText();

        Map<String, Object> edit = planBody("Arxiv A (yeni)");
        edit.put("priceMonthly", "30.00");
        edit.put("active", false);
        put(adminToken(), "/api/admin/plans/" + idA, edit).andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Arxiv A (yeni)")))
                .andExpect(jsonPath("$.active", is(false)));
        get(null, "/api/public/plans").andExpect(jsonPath("$[?(@.id=='" + idA + "')]", empty()));

        put(adminToken(), "/api/admin/plans/order", Map.of("ids", List.of(idB, idA))).andExpect(status().isOk());
        int orderA = jdbc.sql("SELECT sort_order FROM subscription_plans WHERE id = :id").param("id", UUID.fromString(idA)).query(Integer.class).single();
        int orderB = jdbc.sql("SELECT sort_order FROM subscription_plans WHERE id = :id").param("id", UUID.fromString(idB)).query(Integer.class).single();
        assertEquals(1, orderB);
        assertEquals(2, orderA);

        // unused package: really deleted
        mvc.perform(delete("/api/admin/plans/" + idA).header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result", is("DELETED")));

        // used package: archived, history kept
        String owner = register("Arxiv mağaza " + UUID.randomUUID());
        requestPayment(owner, b.get("code").asText(), "MONTHLY", "M10", "25.00");
        mvc.perform(delete("/api/admin/plans/" + idB).header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result", is("ARCHIVED")));
        get(null, "/api/public/plans").andExpect(jsonPath("$[?(@.id=='" + idB + "')]", empty()));
        get(adminToken(), "/api/admin/plans?archived=true").andExpect(jsonPath("$[?(@.id=='" + idB + "')].archived", contains(true)));
        get(owner, "/api/subscriptions/history").andExpect(jsonPath("$[0].planName", is("Arxiv B")));
    }

    // 5-7, 10: M10, card and WhatsApp settings; wa.me number in international form
    @Test
    void paymentAndContactSettingsAreStoredAndShownToStores() throws Exception {
        Map<String, Object> s = new HashMap<>();
        s.put("phone", "050 123 45 67");
        s.put("m10Account", "+994 55 000 11 22");
        s.put("m10Recipient", "N. Məmmədov");
        s.put("paymentNote", "Təyinata mağaza adını yazın");
        s.put("cardNumber", "4169 7388 1234 5678");
        s.put("cardHolder", "NURLAN MAMMADOV");
        s.put("cardBank", "Kapital Bank");
        s.put("cardNote", "Köçürmədən sonra çeki göndərin");
        s.put("extraPhone", "012 555 55 55");
        s.put("extraContact", "Hər gün 09:00-21:00");
        s.put("whatsappTemplate", "Salam. Paket: [Paket adı]");
        s.put("paymentInstructions", "M10 və ya kartla ödəyin");
        s.put("afterPaymentText", "Təşəkkürlər! 1 saat ərzində aktivləşdiriləcək.");
        put(adminToken(), "/api/admin/settings/contact", s).andExpect(status().isOk())
                .andExpect(jsonPath("$.whatsapp", is("994501234567")));

        String owner = register("Ayar oxuyan " + UUID.randomUUID());
        get(owner, "/api/public/contact").andExpect(status().isOk())
                .andExpect(jsonPath("$.m10Account", is("+994 55 000 11 22")))
                .andExpect(jsonPath("$.cardNumber", is("4169 7388 1234 5678")))
                .andExpect(jsonPath("$.cardHolder", is("NURLAN MAMMADOV")))
                .andExpect(jsonPath("$.whatsappTemplate", is("Salam. Paket: [Paket adı]")))
                .andExpect(jsonPath("$.afterPaymentText", startsWith("Təşəkkürlər")));
        // before login the bank card is not exposed
        get(null, "/api/public/contact").andExpect(jsonPath("$.cardNumber").doesNotExist())
                .andExpect(jsonPath("$.whatsapp", is("994501234567")));

        put(adminToken(), "/api/admin/settings/contact", Map.of("cardNumber", "41 69 x")).andExpect(status().isBadRequest());
    }

    // 8, 9, 11-14: store sees the package, pays, admin approves, subscription and store become active
    @Test
    void paymentRequestUsesServerPriceAndApprovalActivatesStore() throws Exception {
        JsonNode plan = createPlan("Axın " + UUID.randomUUID().toString().substring(0, 6));
        String code = plan.get("code").asText();
        String owner = register("Axın mağaza " + UUID.randomUUID());
        UUID storeId = storeIdOf(owner);

        JsonNode req = requestPayment(owner, code, "QUARTERLY", "CARD", "1.00");
        assertEquals("PENDING", req.get("status").asText());
        assertEquals(70.00, req.get("amount").asDouble(), "amount comes from the package, not the client");
        assertEquals(1.00, req.get("declaredAmount").asDouble());
        assertEquals("CARD", req.get("paymentMethod").asText());
        assertEquals("Əli Həsənov", req.get("payerName").asText());
        assertEquals(true, req.get("hasReceipt").asBoolean());
        String id = req.get("id").asText();

        // the store is still locked until the admin approves
        get(owner, "/api/subscriptions/current").andExpect(jsonPath("$.active", is(false)))
                .andExpect(jsonPath("$.status", is("PENDING")));

        get(adminToken(), "/api/admin/subscriptions?status=PENDING")
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].ownerName", contains(startsWith("Sahib"))))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].periodCode", contains("QUARTERLY")));

        jdbc.sql("UPDATE stores SET status = 'SUSPENDED' WHERE id = :id").param("id", storeId).update();
        post(adminToken(), "/api/admin/subscriptions/" + id + "/approve", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.requestStatus", is("APPROVED")))
                .andExpect(jsonPath("$.startDate", is(today().toString())))
                .andExpect(jsonPath("$.endDate", is(today().plusMonths(3).minusDays(1).toString())));

        String storeStatus = jdbc.sql("SELECT status FROM stores WHERE id = :id").param("id", storeId).query(String.class).single();
        assertEquals("ACTIVE", storeStatus, "approval reactivates the store");
        get(owner, "/api/subscriptions/current").andExpect(jsonPath("$.active", is(true)));
        get(owner, "/api/notifications").andExpect(jsonPath("$[?(@.type=='PAYMENT_APPROVED')].title", contains("Ödənişiniz təsdiqləndi")));
        get(owner, "/api/customers").andExpect(status().isOk());

        // the same request can never be approved twice
        post(adminToken(), "/api/admin/subscriptions/" + id + "/approve", Map.of()).andExpect(status().isConflict());
        long active = jdbc.sql("SELECT COUNT(*) FROM subscriptions WHERE store_id = :s AND status = 'ACTIVE'")
                .param("s", storeId).query(Long.class).single();
        assertEquals(1, active);

        // 18: data survives a "reload" (new read of the same endpoints)
        get(owner, "/api/subscriptions/history").andExpect(jsonPath("$[0].payerName", is("Əli Həsənov")))
                .andExpect(jsonPath("$[0].note", is("M10 ilə ödədim")));
    }

    @Test
    void renewalKeepsRemainingTimeAndCustomPeriodUsesDays() throws Exception {
        JsonNode plan = createPlan("Yenilə " + UUID.randomUUID().toString().substring(0, 6));
        String code = plan.get("code").asText();
        String owner = register("Yenilə mağaza " + UUID.randomUUID());

        String first = requestPayment(owner, code, "MONTHLY", "M10", "25").get("id").asText();
        JsonNode a = body(post(adminToken(), "/api/admin/subscriptions/" + first + "/approve", Map.of()));
        LocalDate firstEnd = LocalDate.parse(a.get("endDate").asText());

        String second = requestPayment(owner, code, "CUSTOM", "M10", "35").get("id").asText();
        JsonNode b = body(post(adminToken(), "/api/admin/subscriptions/" + second + "/approve", Map.of()));
        assertEquals(firstEnd.plusDays(1).toString(), b.get("startDate").asText(), "new period starts after the paid one");
        assertEquals(firstEnd.plusDays(45).toString(), b.get("endDate").asText());
        assertEquals(35.00, b.get("amount").asDouble());
    }

    // 15: rejected payment never activates
    @Test
    void rejectedPaymentDoesNotActivate() throws Exception {
        JsonNode plan = createPlan("Rədd " + UUID.randomUUID().toString().substring(0, 6));
        String owner = register("Rədd mağaza " + UUID.randomUUID());
        String id = requestPayment(owner, plan.get("code").asText(), "YEARLY", "M10", "240").get("id").asText();

        post(adminToken(), "/api/admin/subscriptions/" + id + "/reject", Map.of("reason", "Çek oxunmur"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestStatus", is("REJECTED")));
        post(adminToken(), "/api/admin/subscriptions/" + id + "/approve", Map.of()).andExpect(status().isConflict());
        get(owner, "/api/subscriptions/current").andExpect(jsonPath("$.active", is(false)));
        get(owner, "/api/customers").andExpect(status().isPaymentRequired());
        get(owner, "/api/notifications").andExpect(jsonPath("$[?(@.type=='SUBSCRIPTION_REJECTED')].message", contains(containsString("Çek oxunmur"))));
    }

    @Test
    void periodNotOfferedOrInactivePackageIsRefused() throws Exception {
        Map<String, Object> only = planBody("Yalnız aylıq");
        for (String k : List.of("priceQuarterly", "priceSemiAnnual", "priceYearly", "customDays", "customPrice")) {
            only.remove(k);
        }
        JsonNode plan = body(post(adminToken(), "/api/admin/plans", only).andExpect(status().isCreated()));
        String owner = register("Müddət mağaza " + UUID.randomUUID());
        post(owner, "/api/subscriptions/request", Map.of("planCode", plan.get("code").asText(), "period", "YEARLY"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_PERIOD")));
        post(owner, "/api/subscriptions/request", Map.of("planCode", plan.get("code").asText(), "paymentMethod", "PAYPAL"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_METHOD")));
    }

    // 16-17: a store sees only its own requests and receipts
    @Test
    void storesSeeOnlyTheirOwnPaymentRequestsAndReceipts() throws Exception {
        JsonNode plan = createPlan("İzolyasiya " + UUID.randomUUID().toString().substring(0, 6));
        String a = register("Mağaza A " + UUID.randomUUID());
        String b = register("Mağaza B " + UUID.randomUUID());
        String idA = requestPayment(a, plan.get("code").asText(), "MONTHLY", "M10", "25").get("id").asText();

        get(b, "/api/subscriptions/history").andExpect(jsonPath("$[?(@.id=='" + idA + "')]", empty()));
        get(b, "/api/subscriptions/" + idA + "/receipt").andExpect(status().isNotFound());
        get(a, "/api/subscriptions/" + idA + "/receipt").andExpect(status().isOk());
        get(b, "/api/admin/subscriptions").andExpect(status().isForbidden());
        post(b, "/api/admin/subscriptions/" + idA + "/approve", Map.of()).andExpect(status().isForbidden());
    }

    @Test
    void packageLimitsApplyToEmployeesAndCustomers() throws Exception {
        JsonNode plan = createPlan("Limit " + UUID.randomUUID().toString().substring(0, 6)); // 1 employee, 2 customers
        String owner = register("Limit mağaza " + UUID.randomUUID());
        String id = requestPayment(owner, plan.get("code").asText(), "MONTHLY", "M10", "25").get("id").asText();
        post(adminToken(), "/api/admin/subscriptions/" + id + "/approve", Map.of()).andExpect(status().isOk());

        createCustomer(owner, "Müştəri 1", "0501111111");
        createCustomer(owner, "Müştəri 2", "0502222222");
        post(owner, "/api/customers", Map.of("name", "Müştəri 3")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("PLAN_LIMIT_CUSTOMERS")));

        Map<String, Object> emp = Map.of("email", uniqueEmail("w1"), "password", EMPLOYEE_PASSWORD,
                "fullName", "İşçi 1", "permissions", perms("CUSTOMER_DEBTS"));
        post(owner, "/api/employees", emp).andExpect(status().isCreated());
        Map<String, Object> emp2 = Map.of("email", uniqueEmail("w2"), "password", EMPLOYEE_PASSWORD,
                "fullName", "İşçi 2", "permissions", perms("CUSTOMER_DEBTS"));
        post(owner, "/api/employees", emp2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("PLAN_LIMIT_EMPLOYEES")));
    }
}
