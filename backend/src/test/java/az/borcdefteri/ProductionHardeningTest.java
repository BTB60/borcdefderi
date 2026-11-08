package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Payment methods, receipts (çek), payments report, search, validation and admin tooling added in the audit. */
class ProductionHardeningTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    // ---------------------------------------------------------------- payments

    @Test
    void transferAndOtherPaymentsAreRecordedInLedgerAndDashboardTotals() throws Exception {
        String owner = register("Ödəniş Növləri " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Kamran", "+994501112233");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", c, "100.00");

        pay(owner, debt, "10.00", "CASH").andExpect(status().isCreated());
        pay(owner, debt, "20.00", "CARD").andExpect(status().isCreated());
        pay(owner, debt, "30.00", "TRANSFER").andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAfter", is(40.0)));
        pay(owner, debt, "40.00", "OTHER").andExpect(status().isCreated())
                .andExpect(jsonPath("$.debtStatusAfter", is("PAID")));
        pay(owner, debt, "0.01", "TRANSFER").andExpect(status().isConflict());

        get(owner, "/api/debts/" + debt + "/history")
                .andExpect(jsonPath("$[*].type", contains("DEBT_CREATED", "PAYMENT_CASH", "PAYMENT_CARD",
                        "PAYMENT_TRANSFER", "PAYMENT_OTHER")))
                .andExpect(jsonPath("$[4].balanceAfter", is(0.0)));

        get(owner, "/api/dashboard")
                .andExpect(jsonPath("$.summary.cashReceived", is(10.0)))
                .andExpect(jsonPath("$.summary.cardReceived", is(20.0)))
                .andExpect(jsonPath("$.summary.otherReceived", is(70.0)))
                .andExpect(jsonPath("$.summary.todayTotal", is(100.0)))
                .andExpect(jsonPath("$.summary.monthTotal", is(100.0)))
                .andExpect(jsonPath("$.summary.customerDebts", is(0.0)));
        post(owner, "/api/debts/" + debt + "/payments", Map.of("amount", "1", "paymentMethod", "BITCOIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paymentReceiptPdfIsStoreScopedAndPermissionChecked() throws Exception {
        String owner = register("Çek Mağazası " + UUID.randomUUID());
        String other = register("Başqa Çek Mağazası " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        activateSubscription(other, "MONTHLY");
        UUID company = createCompany(owner, "Azər Topdan");
        UUID debt = debtIdOf(owner, "COMPANY_PAYABLE", company, "250.00");
        String paymentId = body(pay(owner, debt, "100.00", "TRANSFER")).get("id").asText();

        byte[] pdf = get(owner, "/api/payments/" + paymentId + "/receipt")
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("cek-")))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(Arrays.copyOf(pdf, 4)));

        get(other, "/api/payments/" + paymentId + "/receipt").andExpect(status().isNotFound());

        String email = uniqueEmail("cek-isci");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "İşçi",
                "permissions", perms("CUSTOMER_DEBTS"))).andExpect(status().isCreated());
        get(login(email, EMPLOYEE_PASSWORD), "/api/payments/" + paymentId + "/receipt").andExpect(status().isForbidden());
    }

    @Test
    void receiptCanBeSharedByLinkWithoutLoginButLinksCannotBeForged() throws Exception {
        String owner = register("WhatsApp Çek " + UUID.randomUUID());
        String other = register("Başqa WhatsApp Çek " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        activateSubscription(other, "MONTHLY");
        UUID c = createCustomer(owner, "Səbinə", "+994501119988");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", c, "60.00");
        JsonNode payment = body(pay(owner, debt, "25.00", "CASH"));
        String url = payment.get("receiptUrl").asText();
        assertTrue(url.contains("/api/public/receipts/" + payment.get("id").asText() + "/"), url);
        String path = java.net.URI.create(url).getPath();

        // The customer opens the link from WhatsApp: no login, PDF shown inline in the browser.
        byte[] pdf = get(null, path).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("inline")))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(Arrays.copyOf(pdf, 4)));

        // Payments list carries the same link; the share-link endpoint is store-scoped.
        get(owner, "/api/payments?debtId=" + debt).andExpect(jsonPath("$[0].receiptUrl", is(url)));
        get(owner, "/api/payments/" + payment.get("id").asText() + "/share-link").andExpect(jsonPath("$.url", is(url)));
        get(other, "/api/payments/" + payment.get("id").asText() + "/share-link").andExpect(status().isNotFound());

        // Forged / altered / reused tokens are rejected without revealing anything.
        String token = path.substring(path.lastIndexOf('/') + 1);
        String forged = (token.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);
        get(null, path.replace(token, forged)).andExpect(status().isNotFound());
        get(null, "/api/public/receipts/" + payment.get("id").asText() + "/short").andExpect(status().isNotFound());
        UUID debt2 = debtIdOf(owner, "CUSTOMER_DEBT", c, "10.00");
        String otherPayment = body(pay(owner, debt2, "5.00", "CARD")).get("id").asText();
        get(null, "/api/public/receipts/" + otherPayment + "/" + token).andExpect(status().isNotFound());
        get(null, "/api/public/receipts/" + UUID.randomUUID() + "/" + token).andExpect(status().isNotFound());
    }

    @Test
    void paymentsReportNeedsReportsPermissionAndCoversTheRange() throws Exception {
        String owner = register("Hesabat Mağazası " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        UUID c = createCustomer(owner, "Nigar", "+994551234567");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", c, "80.00");
        pay(owner, debt, "30.00", "CASH").andExpect(status().isCreated());
        pay(owner, debt, "15.00", "CARD").andExpect(status().isCreated());
        String today = LocalDate.now(clock).toString();

        byte[] xlsx = get(owner, "/api/reports/payments/export?format=xlsx&from=" + today + "&to=" + today)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("PK", new String(Arrays.copyOf(xlsx, 2)));
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            double grand = sheet.getRow(sheet.getLastRowNum()).getCell(4).getNumericCellValue();
            assertEquals(45.0, grand, 0.001);
        }
        get(owner, "/api/reports/payments/export?format=pdf").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        get(owner, "/api/reports/payments/export?format=pdf&from=2026-02-01&to=2026-01-01")
                .andExpect(status().isBadRequest());

        String email = uniqueEmail("hesabat-isci");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "İşçi",
                "permissions", perms("CUSTOMER_DEBTS"))).andExpect(status().isCreated());
        get(login(email, EMPLOYEE_PASSWORD), "/api/reports/payments/export").andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- search & validation

    @Test
    void searchFindsAzerbaijaniCapitalIAndTreatsWildcardsLiterally() throws Exception {
        String owner = register("Axtarış Mağazası " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        UUID ilham = createCustomer(owner, "İlham Əliyev", "+994501110000");
        createCustomer(owner, "Leyla", "+994501110001");
        debtIdOf(owner, "CUSTOMER_DEBT", ilham, "10.00");

        get(owner, "/api/customers?q=" + enc("İlham")).andExpect(jsonPath("$[*].name", contains("İlham Əliyev")));
        get(owner, "/api/customers?q=" + enc("%")).andExpect(jsonPath("$", hasSize(0)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT&q=" + enc("İLHAM")).andExpect(jsonPath("$.total", is(1)));
    }

    @Test
    void tooLongPasswordsAndBadInputGiveClearErrorsNotServerErrors() throws Exception {
        String longAz = "ə".repeat(40); // 40 characters but 80 bytes
        Map<String, Object> reg = new HashMap<>(Map.of("email", uniqueEmail("long"), "password", longAz,
                "fullName", "Uzun", "storeName", "Uzun Şifrə"));
        postRaw("/api/auth/register", reg).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("PASSWORD_TOO_LONG")));
        postRaw("/api/auth/login", Map.of("email", "nobody@test.az", "password", longAz))
                .andExpect(status().isUnauthorized());

        String owner = register("Validasiya Mağazası " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        Map<String, Object> emp = new HashMap<>();
        emp.put("email", uniqueEmail("null-perm"));
        emp.put("password", EMPLOYEE_PASSWORD);
        emp.put("fullName", "İşçi");
        emp.put("permissions", Arrays.asList("REPORTS", null));
        post(owner, "/api/employees", emp).andExpect(status().isBadRequest());

        post(owner, "/api/subscriptions/request", Map.of("planCode", "MONTHLY", "payerReference", "x".repeat(121)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- subscriptions

    @Test
    void receiptIsStoredInDatabaseWithM10ReferenceAndStillDownloadable() throws Exception {
        String owner = register("M10 Mağazası " + UUID.randomUUID());
        JsonNode sub = body(mvc.perform(multipart("/api/subscriptions")
                        .file(new MockMultipartFile("receipt", "m10-qəbz.png", "image/png", pngBytes()))
                        .param("planCode", "SEMI_ANNUAL").param("payerReference", "M10-778899")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payerReference", is("M10-778899")))
                .andExpect(jsonPath("$.hasReceipt", is(true))));
        UUID id = UUID.fromString(sub.get("id").asText());
        Integer bytes = jdbc.sql("SELECT octet_length(content) FROM subscription_receipts WHERE subscription_id = :id")
                .param("id", id).query(Integer.class).single();
        assertEquals(pngBytes().length, bytes);
        get(adminToken(), "/api/admin/subscriptions/" + id + "/receipt").andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("UTF-8''")));

        // Without payment confirmation by the admin the store stays locked.
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired());
    }

    @Test
    void paymentSettingsShowM10DetailsPublicly() throws Exception {
        put(adminToken(), "/api/admin/settings/contact", Map.of("phone", "+994 50 111 22 33",
                "m10Account", "+994501112233", "m10Recipient", "Platforma MMC", "paymentNote", "Təyinat: mağaza adı"))
                .andExpect(status().isOk());
        get(null, "/api/public/contact")
                .andExpect(jsonPath("$.m10Account", is("+994501112233")))
                .andExpect(jsonPath("$.m10Recipient", is("Platforma MMC")))
                .andExpect(jsonPath("$.whatsapp", is("994501112233")));
    }

    @Test
    void expiredStoreGetsNotificationAndExtensionCountsFromToday() throws Exception {
        String owner = register("Bitmiş Mağaza " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        String storeId = body(get(owner, "/api/auth/me")).get("storeId").asText();
        LocalDate today = LocalDate.now(clock);
        jdbc.sql("UPDATE subscriptions SET start_date = :s, end_date = :e WHERE store_id = :id AND status = 'ACTIVE'")
                .param("s", today.minusDays(40)).param("e", today.minusDays(5)).param("id", UUID.fromString(storeId))
                .update();

        get(owner, "/api/notifications").andExpect(jsonPath("$[*].type", hasItem("SUBSCRIPTION_EXPIRED")));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired());

        post(adminToken(), "/api/admin/stores/" + storeId + "/extend", Map.of("days", 10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate", is(today.minusDays(1).plusDays(10).toString())));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isOk());
    }

    @Test
    void storeActivityHistoryShowsWhoDidWhat() throws Exception {
        String owner = register("Tarixçə Mağazası " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        String storeId = body(get(owner, "/api/auth/me")).get("storeId").asText();
        createCustomer(owner, "Rəşad", "+994701234567");

        JsonNode logs = body(get(adminToken(), "/api/admin/audit-logs?storeId=" + storeId));
        assertTrue(logs.size() >= 3);
        boolean customer = false;
        for (JsonNode l : logs) {
            assertEquals(storeId, l.get("storeId").asText());
            if ("CUSTOMER_CREATED".equals(l.get("action").asText())) {
                customer = true;
                assertTrue(l.get("actorEmail").asText().startsWith("owner-"));
            }
        }
        assertTrue(customer);
        get(owner, "/api/admin/audit-logs").andExpect(status().isForbidden());
    }

    /** MockMvc URL templates are encoded by MockMvc itself, so values are passed as typed by the user. */
    private static String enc(String s) {
        return s;
    }
}
