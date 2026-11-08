package az.borcdefteri;

import az.borcdefteri.subscriptions.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manual subscription workflow: PENDING -> ACTIVE/REJECTED, EXPIRED, renewal, receipts and plans. */
class SubscriptionLifecycleTest extends IntegrationTestBase {

    @Autowired
    private SubscriptionService subscriptions;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void submissionIsPendingAndBlocksBusinessUntilApproved() throws Exception {
        String owner = register("Abunəlik Mağazası");
        uploadReceipt(owner, "MONTHLY", "qebz.png", "image/png", pngBytes())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.amount", is(19.0)))
                .andExpect(jsonPath("$.hasReceipt", is(true)));

        get(owner, "/api/subscriptions/current").andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.active", is(false)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired());

        // A second submission updates the same pending request (new receipt) instead of creating a duplicate.
        uploadReceipt(owner, "MONTHLY", "again.png", "image/png", pngBytes())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")));
        get(owner, "/api/subscriptions/history").andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void invalidReceiptTypeIsRejectedByMagicBytes() throws Exception {
        String owner = register("Fayl Mağazası");
        uploadReceipt(owner, "MONTHLY", "qebz.png", "image/png", "not really an image".getBytes())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_FILE_TYPE")));
        uploadReceipt(owner, "MONTHLY", "qebz.exe", "application/octet-stream", new byte[]{0x4D, 0x5A, 0, 0})
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminApprovalActivatesSubscriptionAndSetsPeriod() throws Exception {
        String owner = register("Təsdiq Mağazası");
        UUID sub = UUID.fromString(body(uploadReceipt(owner, "MONTHLY", "r.pdf", "application/pdf",
                "%PDF-1.4 test".getBytes())).get("id").asText());

        post(adminToken(), "/api/admin/subscriptions/" + sub + "/approve", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        get(owner, "/api/subscriptions/current")
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.planCode", is("MONTHLY")))
                .andExpect(jsonPath("$.startDate", is(LocalDate.now(clock).toString())));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isOk());

        post(adminToken(), "/api/admin/subscriptions/" + sub + "/approve", Map.of())
                .andExpect(status().isConflict());
    }

    @Test
    void rejectionKeepsStoreLockedAndStoresReason() throws Exception {
        String owner = register("Rədd Mağazası");
        UUID sub = UUID.fromString(body(uploadReceipt(owner, "MONTHLY", "r.png", "image/png", pngBytes())).get("id").asText());

        post(adminToken(), "/api/admin/subscriptions/" + sub + "/reject", Map.of("reason", "Ödəniş tapılmadı"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectReason", is("Ödəniş tapılmadı")));

        get(owner, "/api/subscriptions/current").andExpect(jsonPath("$.status", is("REJECTED")));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired());
    }

    @Test
    void expiredSubscriptionBlocksAccessAndRenewalReminderAppearsBeforeEnd() throws Exception {
        String owner = register("Müddət Mağazası");
        activateSubscription(owner, "MONTHLY"); // one month from today

        LocalDate end = LocalDate.parse(body(get(owner, "/api/subscriptions/current")).get("endDate").asText());
        clock.setDate(end.minusDays(3));
        get(owner, "/api/subscriptions/current")
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.renewalReminder", is(true)));

        clock.setDate(end.plusDays(1));
        int expired = subscriptions.expireDue();
        org.junit.jupiter.api.Assertions.assertTrue(expired >= 1);
        get(owner, "/api/subscriptions/current").andExpect(jsonPath("$.status", is("EXPIRED")))
                .andExpect(jsonPath("$.active", is(false)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("SUBSCRIPTION_REQUIRED")));
    }

    @Test
    void lateRenewalAfterEndDateAlsoBlocksEvenBeforeExpiryJobRuns() throws Exception {
        String owner = register("Gecikmiş Mağaza");
        activateSubscription(owner, "MONTHLY");
        LocalDate end = LocalDate.parse(body(get(owner, "/api/subscriptions/current")).get("endDate").asText());
        clock.setDate(end.plusDays(2));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired());
    }

    @Test
    void renewalBeforeExpiryExtendsFromCurrentEndDate() throws Exception {
        String owner = register("Yeniləmə Mağazası");
        activateSubscription(owner, "MONTHLY");
        LocalDate firstEnd = LocalDate.parse(body(get(owner, "/api/subscriptions/current")).get("endDate").asText());

        activateSubscription(owner, "MONTHLY");
        LocalDate secondEnd = LocalDate.parse(body(get(owner, "/api/subscriptions/current")).get("endDate").asText());
        // Second period starts the day after the first ends and lasts one month.
        org.junit.jupiter.api.Assertions.assertEquals(firstEnd.plusDays(1).plusMonths(1).minusDays(1), secondEnd);
    }

    @Test
    void yearlyPlanGivesTwelveMonthsAndAmountComesFromPlanNotClient() throws Exception {
        String owner = register("İllik Mağaza");
        JsonNodeHolder r = new JsonNodeHolder(body(uploadReceipt(owner, "YEARLY", "r.png", "image/png", pngBytes())));
        org.junit.jupiter.api.Assertions.assertEquals(179.0, r.node().get("amount").doubleValue());
        UUID sub = UUID.fromString(r.node().get("id").asText());
        post(adminToken(), "/api/admin/subscriptions/" + sub + "/approve", Map.of()).andExpect(status().isOk());
        JsonNodeHolder cur = new JsonNodeHolder(body(get(owner, "/api/subscriptions/current")));
        LocalDate start = LocalDate.parse(cur.node().get("startDate").asText());
        LocalDate end = LocalDate.parse(cur.node().get("endDate").asText());
        org.junit.jupiter.api.Assertions.assertEquals(start.plusMonths(12).minusDays(1), end);
    }

    @Test
    void receiptIsDownloadableOnlyByOwningStoreAndAdmin() throws Exception {
        String owner = register("Qəbz Mağazası");
        String other = register("Başqa Mağaza");
        UUID sub = UUID.fromString(body(uploadReceipt(owner, "MONTHLY", "receipt.png", "image/png", pngBytes())).get("id").asText());

        get(owner, "/api/subscriptions/" + sub + "/receipt").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", "image/png"));
        get(other, "/api/subscriptions/" + sub + "/receipt").andExpect(status().isNotFound());
        get(adminToken(), "/api/admin/subscriptions/" + sub + "/receipt").andExpect(status().isOk());
    }

    @Test
    void adminCanUpdatePlanPriceAndNewRequestsUseIt() throws Exception {
        String owner = register("Qiymət Mağazası");
        UUID planId = UUID.fromString(jdbc.sql("SELECT id FROM subscription_plans WHERE code = 'MONTHLY'")
                .query(UUID.class).single().toString());
        try {
            put(adminToken(), "/api/admin/plans/" + planId, Map.of(
                    "name", "Aylıq", "price", "12.50", "durationMonths", 1, "active", true))
                    .andExpect(status().isOk());
            JsonNodeHolder r = new JsonNodeHolder(body(uploadReceipt(owner, "MONTHLY", "r.png", "image/png", pngBytes())));
            org.junit.jupiter.api.Assertions.assertEquals(12.5, r.node().get("amount").doubleValue());
        } finally {
            put(adminToken(), "/api/admin/plans/" + planId, Map.of(
                    "name", "Aylıq", "price", "19.00", "durationMonths", 1, "active", true));
        }
    }

    @Test
    void adminRevenueAndExtensionWork() throws Exception {
        String owner = register("Gəlir Mağazası");
        UUID sub = activateSubscription(owner, "MONTHLY");
        get(adminToken(), "/api/admin/revenue").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue", greaterThanOrEqualTo(19.0)));
        post(adminToken(), "/api/admin/stores/" + storeIdOf(owner) + "/extend", Map.of("days", 10))
                .andExpect(status().isOk());
        LocalDate start = LocalDate.now(clock);
        org.junit.jupiter.api.Assertions.assertNotNull(sub);
        get(owner, "/api/subscriptions/current").andExpect(jsonPath("$.daysRemaining", greaterThanOrEqualTo(10)));
    }

    private String storeIdOf(String token) throws Exception {
        return body(get(token, "/api/auth/me")).get("storeId").asText();
    }

    private record JsonNodeHolder(com.fasterxml.jackson.databind.JsonNode node) {}
}
