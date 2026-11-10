package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ERP stage 8: products, stock movements, sales (cash / card / credit), returns, cancel, profit and loss. */
class InventorySalesTest extends IntegrationTestBase {

    private String product(String token, String name, String buy, String sell, String stock) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("name", name, "purchasePrice", buy, "salePrice", sell, "minStock", "2"));
        if (stock != null) b.put("initialStock", stock);
        return body(post(token, "/api/products", b).andExpect(status().isCreated())).get("id").asText();
    }

    private double stock(String token, String id) throws Exception {
        return body(get(token, "/api/products/" + id)).get("stockQty").asDouble();
    }

    private Map<String, Object> sale(List<Map<String, Object>> items, String cash, String card, Object customer) {
        Map<String, Object> b = new HashMap<>();
        b.put("items", items);
        if (cash != null) b.put("paidCash", cash);
        if (card != null) b.put("paidCard", card);
        if (customer != null) b.put("customerId", customer);
        return b;
    }

    private Map<String, Object> item(String productId, String qty) {
        return new HashMap<>(Map.of("productId", productId, "quantity", qty));
    }

    private double cashBalance(String token) throws Exception {
        for (JsonNode a : body(get(token, "/api/cash/accounts"))) {
            if ("CASH".equals(a.get("kind").asText()) && a.get("isDefault").asBoolean()) return a.get("balance").asDouble();
        }
        return 0;
    }

    @Test
    void stockMovementsKeepQuantityAndHistoryInStep() throws Exception {
        String owner = register("Anbar Bir");
        activateSubscription(owner, "MONTHLY");
        String p = product(owner, "Un 1 kq", "1.00", "1.50", "10");
        assertEquals(10.0, stock(owner, p));
        get(owner, "/api/products/" + p).andExpect(jsonPath("$.code", startsWith("P-")));

        // receipt at a new cost → weighted average purchase price: (10×1 + 10×2) / 20 = 1.50
        post(owner, "/api/inventory/movements", Map.of("productId", p, "kind", "RECEIPT", "quantity", "10", "unitCost", "2.00"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceAfter", is(20.0)));
        get(owner, "/api/products/" + p).andExpect(jsonPath("$.purchasePrice", is(1.5)));
        post(owner, "/api/inventory/movements", Map.of("productId", p, "kind", "ISSUE", "quantity", "25"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("INSUFFICIENT_STOCK")));
        post(owner, "/api/inventory/movements", Map.of("productId", p, "kind", "ISSUE", "quantity", "3")).andExpect(status().isCreated());
        post(owner, "/api/inventory/movements", Map.of("productId", p, "kind", "COUNT", "quantity", "16")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity", is(-1.0)));
        assertEquals(16.0, stock(owner, p));
        get(owner, "/api/inventory/movements?productId=" + p).andExpect(jsonPath("$", hasSize(4)));

        post(owner, "/api/inventory/movements", Map.of("productId", p, "kind", "ISSUE", "quantity", "15")).andExpect(status().isCreated());
        get(owner, "/api/products?stock=LOW").andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].lowStock", is(true)));
        get(owner, "/api/products/summary").andExpect(jsonPath("$.lowStockCount", is(1))).andExpect(jsonPath("$.costValue", is(1.5)));
    }

    @Test
    void cashSaleUpdatesStockCashAndProfit() throws Exception {
        String owner = register("Satış Bir");
        activateSubscription(owner, "MONTHLY");
        String a = product(owner, "Çay", "3.00", "5.00", "10");
        String b = product(owner, "Şəkər", "1.00", "2.00", "20");
        Map<String, Object> req = sale(List.of(item(a, "2"), item(b, "5")), "15.00", "4.00", null);
        req.put("discount", "1.00");
        req.put("clientRequestId", UUID.randomUUID().toString());
        JsonNode s = body(post(owner, "/api/sales", req).andExpect(status().isCreated()));
        post(owner, "/api/sales", req).andExpect(status().isCreated()); // double tap: same sale returned
        assertEquals(19.0, s.get("total").asDouble()); // 10 + 10 − 1
        assertTrue(s.get("number").asText().startsWith("S-"));
        assertEquals(8.0, stock(owner, a));
        assertEquals(15.0, stock(owner, b));
        assertEquals(15.0, cashBalance(owner));
        get(owner, "/api/sales").andExpect(jsonPath("$.total", is(1)));

        post(owner, "/api/expenses", Map.of("title", "İşıq", "categoryId", "10000000-0000-0000-0000-000000000003", "amount", "4.00",
                "paymentMethod", "CASH")).andExpect(status().isCreated());
        get(owner, "/api/finance/pnl").andExpect(status().isOk())
                .andExpect(jsonPath("$.netSales", is(19.0)))
                .andExpect(jsonPath("$.costOfGoods", is(11.0)))   // 2×3 + 5×1
                .andExpect(jsonPath("$.grossProfit", is(8.0)))
                .andExpect(jsonPath("$.expenses", is(4.0)))
                .andExpect(jsonPath("$.netResult", is(4.0)))
                .andExpect(jsonPath("$.salesPaidNow", is(19.0)));

        byte[] pdf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/sales/" + s.get("id").asText() + "/receipt")
                .header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void creditSaleCreatesCustomerDebtAndLaterPaymentIsNotRevenueAgain() throws Exception {
        String owner = register("Nisyə Satış");
        activateSubscription(owner, "MONTHLY");
        String p = product(owner, "Televizor", "300.00", "500.00", "3");
        UUID c = createCustomer(owner, "Kreditli", "+994501270001");

        post(owner, "/api/sales", sale(List.of(item(p, "1")), "100.00", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("CUSTOMER_REQUIRED")));
        JsonNode s = body(post(owner, "/api/sales", sale(List.of(item(p, "1")), "100.00", null, c.toString())).andExpect(status().isCreated()));
        assertEquals(400.0, s.get("creditAmount").asDouble());
        get(owner, "/api/customers/" + c + "/profile").andExpect(jsonPath("$.totals.remaining", is(400.0)));

        post(owner, "/api/customers/" + c + "/pay", Map.of("amount", "150.00", "paymentMethod", "CASH")).andExpect(status().isCreated());
        get(owner, "/api/finance/pnl").andExpect(jsonPath("$.netSales", is(500.0)))   // counted once, at the sale
                .andExpect(jsonPath("$.salesOnCredit", is(400.0)))
                .andExpect(jsonPath("$.debtCollections", is(150.0)))
                .andExpect(jsonPath("$.grossProfit", is(200.0)));
        assertEquals(250.0, cashBalance(owner)); // 100 at the sale + 150 later
    }

    @Test
    void returnLowersDebtFirstThenRefundsAndCancelRestoresEverything() throws Exception {
        String owner = register("Qaytarma");
        activateSubscription(owner, "MONTHLY");
        String p = product(owner, "Ayaqqabı", "40.00", "100.00", "5");
        UUID c = createCustomer(owner, "Qaytaran", "+994501270002");
        JsonNode s = body(post(owner, "/api/sales", sale(List.of(item(p, "3")), "120.00", null, c.toString())));
        String id = s.get("id").asText();
        String line = s.get("items").get(0).get("id").asText();
        assertEquals(180.0, s.get("creditAmount").asDouble());
        assertEquals(2.0, stock(owner, p));

        // return 2 of 3 (200): 180 of the debt is cleared, 20 is paid back from the cash desk
        post(owner, "/api/sales/" + id + "/returns", Map.of("items", List.of(Map.of("saleItemId", line, "quantity", "2"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.returnedAmount", is(200.0)))
                .andExpect(jsonPath("$.returns[0].debtReduced", is(180.0))).andExpect(jsonPath("$.returns[0].refunded", is(20.0)));
        assertEquals(4.0, stock(owner, p));
        get(owner, "/api/customers/" + c + "/profile").andExpect(jsonPath("$.totals.remaining", is(0.0)));
        assertEquals(100.0, cashBalance(owner));
        post(owner, "/api/sales/" + id + "/returns", Map.of("items", List.of(Map.of("saleItemId", line, "quantity", "2"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("RETURN_EXCEEDS")));

        post(owner, "/api/sales/" + id + "/cancel", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
        assertEquals(5.0, stock(owner, p));
        assertEquals(0.0, cashBalance(owner));
        post(owner, "/api/sales/" + id + "/cancel", Map.of()).andExpect(status().isConflict());
        get(owner, "/api/finance/pnl").andExpect(jsonPath("$.netSales", is(0.0))).andExpect(jsonPath("$.costOfGoods", is(0.0)));
    }

    @Test
    void notEnoughStockRejectsTheWholeSale() throws Exception {
        String owner = register("Stok Yoxdur");
        activateSubscription(owner, "MONTHLY");
        String a = product(owner, "A", "1.00", "2.00", "5");
        String b = product(owner, "B", "1.00", "2.00", "1");
        post(owner, "/api/sales", sale(List.of(item(a, "2"), item(b, "3")), "10.00", null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("INSUFFICIENT_STOCK")));
        assertEquals(5.0, stock(owner, a)); // nothing half-done
        get(owner, "/api/sales").andExpect(jsonPath("$.total", is(0)));
        post(owner, "/api/sales", sale(List.of(item(a, "1")), "3.00", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("OVERPAID")));
    }

    @Test
    void productDeleteOnlyWhenUnusedAndIsolation() throws Exception {
        String owner = register("Məhsul Sil");
        activateSubscription(owner, "MONTHLY");
        String unused = product(owner, "Boş", "1.00", "2.00", null);
        String used = product(owner, "İşlənmiş", "1.00", "2.00", "3");
        mvc.perform(delete("/api/products/" + unused).header("Authorization", "Bearer " + owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));
        mvc.perform(delete("/api/products/" + used).header("Authorization", "Bearer " + owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivated", is(true)));
        post(owner, "/api/sales", sale(List.of(item(used, "1")), "2.00", null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("PRODUCT_INACTIVE")));

        Map<String, Object> dup = new HashMap<>(Map.of("name", "Kopya", "code", "X-1", "purchasePrice", "1", "salePrice", "1"));
        post(owner, "/api/products", dup).andExpect(status().isCreated());
        post(owner, "/api/products", dup).andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("DUPLICATE_CODE")));

        String other = register("Yad Anbar");
        activateSubscription(other, "MONTHLY");
        get(other, "/api/products/" + used).andExpect(status().isNotFound());
        post(other, "/api/sales", sale(List.of(item(used, "1")), "2.00", null, null)).andExpect(status().isNotFound());

        String email = uniqueEmail("satici");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Satıcı",
                "permissions", perms("SALES"))).andExpect(status().isCreated());
        String seller = login(email, EMPLOYEE_PASSWORD);
        get(seller, "/api/products").andExpect(status().isOk());                       // may see products to sell
        post(seller, "/api/products", Map.of("name", "X", "purchasePrice", "1", "salePrice", "1")).andExpect(status().isForbidden());
        get(seller, "/api/finance/pnl").andExpect(status().isForbidden());             // no REPORTS
    }
}
