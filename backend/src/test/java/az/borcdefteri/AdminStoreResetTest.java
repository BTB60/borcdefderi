package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Platform admin: overview, store card, stopping a subscription and wiping a store's business data. */
class AdminStoreResetTest extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private String storeId(String token) throws Exception {
        return body(get(token, "/api/auth/me")).get("storeId").asText();
    }

    /** Puts something into every module: debts, payments (one cancelled), notes, products, a sale, cash, expenses, pocket. */
    private void fill(String owner) throws Exception {
        UUID customer = createCustomer(owner, "Sıfır Müştəri", "+994501119990");
        UUID company = createCompany(owner, "Sıfır MMC");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "100.00");
        debtIdOf(owner, "COMPANY_PAYABLE", company, "50.00");
        String paid = body(pay(owner, debt, "30.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        pay(owner, debt, "10.00", "CARD").andExpect(status().isCreated());
        post(owner, "/api/payments/" + paid + "/cancel", Map.of()).andExpect(status().isOk());
        post(owner, "/api/customers/" + customer + "/notes", Map.of("body", "Zəng etmək")).andExpect(status().is2xxSuccessful());

        Map<String, Object> p = new HashMap<>(Map.of("name", "Çay", "purchasePrice", "1.00", "salePrice", "2.00", "initialStock", "10"));
        String product = body(post(owner, "/api/products", p).andExpect(status().isCreated())).get("id").asText();
        Map<String, Object> sale = new HashMap<>();
        sale.put("items", List.of(Map.of("productId", product, "quantity", "2")));
        sale.put("paidCash", "2.00");
        sale.put("customerId", customer.toString());
        post(owner, "/api/sales", sale).andExpect(status().isCreated());

        post(owner, "/api/expenses", Map.of("title", "İcarə", "categoryId", "10000000-0000-0000-0000-000000000003",
                "amount", "20.00", "paymentMethod", "CASH")).andExpect(status().isCreated());
        post(owner, "/api/pocket", Map.of("kind", "IN", "amount", "5.00")).andExpect(status().is2xxSuccessful());
    }

    @Test
    void theAdminWipesAStoresDataAndKeepsTheAccount() throws Exception {
        String owner = register("Sıfırlanan Mağaza");
        activateSubscription(owner, "SEMI_ANNUAL");
        fill(owner);
        String id = storeId(owner);
        String admin = adminToken();

        get(admin, "/api/admin/stores/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.customers", is(1))).andExpect(jsonPath("$.usage.sales", is(1)))
                .andExpect(jsonPath("$.usage.products", is(1)));

        // the store's name must be typed exactly; a store owner may not call it
        post(admin, "/api/admin/stores/" + id + "/reset-data", Map.of("confirmName", "başqa ad")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("CONFIRM_NAME_MISMATCH")));
        post(owner, "/api/admin/stores/" + id + "/reset-data", Map.of("confirmName", "Sıfırlanan Mağaza"))
                .andExpect(status().isForbidden());

        JsonNode res = body(post(admin, "/api/admin/stores/" + id + "/reset-data", Map.of("confirmName", "  sıfırlanan mağaza "))
                .andExpect(status().isOk()));
        assertTrue(res.get("rows").asLong() > 10);

        // everything is gone, the account and the subscription still work
        get(owner, "/api/customers").andExpect(jsonPath("$", hasSize(0)));
        get(owner, "/api/companies").andExpect(jsonPath("$", hasSize(0)));
        get(owner, "/api/products").andExpect(jsonPath("$", hasSize(0)));
        get(owner, "/api/sales").andExpect(jsonPath("$.total", is(0)));
        get(owner, "/api/dashboard").andExpect(status().isOk()).andExpect(jsonPath("$.summary.customerDebts", is(0.0)));
        for (JsonNode a : body(get(owner, "/api/cash/accounts"))) {
            assertEquals(0.0, a.get("balance").asDouble());
        }
        get(admin, "/api/admin/stores/" + id).andExpect(jsonPath("$.usage.customers", is(0)))
                .andExpect(jsonPath("$.usage.payments", is(0)));
        // the store starts again normally
        createCustomer(owner, "Yeni Müştəri", "+994501119991");
        get(admin, "/api/admin/audit-logs?storeId=" + id + "&limit=50")
                .andExpect(jsonPath("$[?(@.action == 'STORE_DATA_RESET')]", hasSize(1)));
    }

    @Test
    void anotherStoresDataIsUntouchedAndLedgersStayAppendOnly() throws Exception {
        String a = register("Sıfır A");
        activateSubscription(a, "MONTHLY");
        fill(a);
        String b = register("Sıfır B");
        activateSubscription(b, "MONTHLY");
        fill(b);
        post(adminToken(), "/api/admin/stores/" + storeId(a) + "/reset-data", Map.of("confirmName", "Sıfır A"))
                .andExpect(status().isOk());
        get(b, "/api/customers").andExpect(jsonPath("$", hasSize(1)));
        get(b, "/api/sales").andExpect(jsonPath("$.total", is(1)));
        // outside a reset the ledger still refuses deletes
        try {
            jdbc.sql("DELETE FROM financial_transactions WHERE store_id = CAST(:s AS uuid)").param("s", storeId(b)).update();
            throw new AssertionError("delete should be refused");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertTrue(expected.getMessage().contains("append-only"));
        }
    }

    @Test
    void theAdminSeesTheOverviewAndCanStopASubscription() throws Exception {
        String owner = register("Dayanan Abunə");
        activateSubscription(owner, "MONTHLY");
        String id = storeId(owner);
        String admin = adminToken();
        get(admin, "/api/admin/overview").andExpect(status().isOk())
                .andExpect(jsonPath("$.stores.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.signups", hasSize(30)))
                .andExpect(jsonPath("$.revenueByMonth", hasSize(6)));
        get(owner, "/api/overview").andExpect(status().is4xxClientError());

        post(admin, "/api/admin/stores/" + id + "/cancel-subscription", Map.of("reason", "geri qaytarıldı")).andExpect(status().isOk());
        get(owner, "/api/customers").andExpect(status().isPaymentRequired());
        post(admin, "/api/admin/stores/" + id + "/cancel-subscription", Map.of()).andExpect(status().isConflict());
    }

    private double stockOf(String owner, String name) throws Exception {
        for (JsonNode p : body(get(owner, "/api/products"))) {
            if (name.equals(p.get("name").asText())) return p.get("stockQty").asDouble();
        }
        throw new AssertionError("no product " + name);
    }

    @Test
    void theAdminWipesOnlyTheChosenParts() throws Exception {
        String owner = register("Seçmə Silmə");
        activateSubscription(owner, "MONTHLY");
        fill(owner);
        String id = storeId(owner);
        String admin = adminToken();
        assertEquals(8.0, stockOf(owner, "Çay"));

        // only stock: products stay with 0, sales and debts stay
        post(admin, "/api/admin/stores/" + id + "/purge", Map.of("parts", List.of("STOCK"), "confirmName", "Seçmə Silmə"))
                .andExpect(status().isOk());
        assertEquals(0.0, stockOf(owner, "Çay"));
        get(owner, "/api/sales").andExpect(jsonPath("$.total", is(1)));
        get(owner, "/api/customers").andExpect(jsonPath("$", hasSize(1)));

        // sales: gone with the credit debt they made; the plain customer debt stays
        post(admin, "/api/admin/stores/" + id + "/purge", Map.of("parts", List.of("SALES"), "confirmName", "Seçmə Silmə"))
                .andExpect(status().isOk());
        get(owner, "/api/sales").andExpect(jsonPath("$.total", is(0)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(jsonPath("$.total", is(1)));

        // name must match, parts must be known
        post(admin, "/api/admin/stores/" + id + "/purge", Map.of("parts", List.of("SALES"), "confirmName", "x"))
                .andExpect(status().isBadRequest());
        post(admin, "/api/admin/stores/" + id + "/purge", Map.of("parts", List.of("NOPE"), "confirmName", "Seçmə Silmə"))
                .andExpect(status().isBadRequest());
        // customers pull in debts
        post(admin, "/api/admin/stores/" + id + "/purge", Map.of("parts", List.of("CUSTOMERS"), "confirmName", "Seçmə Silmə"))
                .andExpect(status().isOk());
        get(owner, "/api/customers").andExpect(jsonPath("$", hasSize(0)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(jsonPath("$.total", is(0)));
        get(owner, "/api/products").andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void theAdminFindsAndDeletesOneOperation() throws Exception {
        String owner = register("Tək Əməliyyat");
        activateSubscription(owner, "MONTHLY");
        fill(owner);
        String id = storeId(owner);
        String admin = adminToken();

        String saleNo = body(get(owner, "/api/sales")).get("items").get(0).get("number").asText();
        JsonNode found = body(get(admin, "/api/admin/stores/" + id + "/operations?q=" + saleNo));
        assertEquals("SALE", found.get(0).get("type").asText());
        delete(admin, "/api/admin/stores/" + id + "/operations/SALE/" + found.get(0).get("id").asText());
        get(owner, "/api/sales").andExpect(jsonPath("$.total", is(0)));
        assertEquals(10.0, stockOf(owner, "Çay"));

        // a payment: the debt is recomputed (100 debt, 30 cancelled + 10 card live -> paid 10)
        JsonNode all = body(get(admin, "/api/admin/stores/" + id + "/operations?q=10.00"));
        String pay = null;
        for (JsonNode o : all) if ("PAYMENT".equals(o.get("type").asText()) && o.get("amount").asDouble() == 10.0) pay = o.get("id").asText();
        delete(admin, "/api/admin/stores/" + id + "/operations/PAYMENT/" + pay);
        JsonNode debts = body(get(owner, "/api/debts?type=CUSTOMER_DEBT")).get("items");
        assertEquals(100.0, debts.get(0).get("remainingAmount").asDouble());

        // a store owner may not use it
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/admin/stores/" + id + "/operations").header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        get(admin, "/api/admin/audit-logs?storeId=" + id + "&limit=50")
                .andExpect(jsonPath("$[?(@.action == 'OPERATION_DELETED')]", hasSize(2)));
    }

    private void delete(String token, String url) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }
}
