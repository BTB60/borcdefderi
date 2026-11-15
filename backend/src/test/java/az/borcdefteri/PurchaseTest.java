package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mal qəbulu: stock up, purchase price averaged, supplier debt for the unpaid part, cash out for the paid part. */
class PurchaseTest extends IntegrationTestBase {

    private String product(String token, String name, String buy, String stock) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("name", name, "purchasePrice", buy, "salePrice", "100"));
        if (stock != null) b.put("initialStock", stock);
        return body(post(token, "/api/products", b).andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode productOf(String token, String id) throws Exception {
        return body(get(token, "/api/products/" + id));
    }

    private void deposit(String token, String amount) throws Exception {
        String id = null;
        for (JsonNode a : body(get(token, "/api/cash/accounts"))) {
            if ("CASH".equals(a.get("kind").asText()) && a.get("isDefault").asBoolean()) id = a.get("id").asText();
        }
        if (id == null) {
            id = body(post(token, "/api/cash/accounts", Map.of("name", "Kassa", "kind", "CASH", "isDefault", true))).get("id").asText();
        }
        post(token, "/api/cash/deposit", Map.of("accountId", id, "amount", amount)).andExpect(status().isCreated());
    }

    private double cashBalance(String token) throws Exception {
        for (JsonNode a : body(get(token, "/api/cash/accounts"))) {
            if ("CASH".equals(a.get("kind").asText()) && a.get("isDefault").asBoolean()) return a.get("balance").asDouble();
        }
        return 0;
    }

    @Test
    void aPurchaseFromASupplierRaisesStockAndTheSupplierDebt() throws Exception {
        String owner = register("Alış Mağazası");
        activateSubscription(owner, "MONTHLY");
        String a = product(owner, "Un", "10.00", "10");
        String b = product(owner, "Şəkər", "5.00", null);
        UUID supplier = createCompany(owner, "Topdan MMC");
        deposit(owner, "500.00");

        Map<String, Object> req = new HashMap<>();
        req.put("companyId", supplier.toString());
        req.put("items", List.of(Map.of("productId", a, "quantity", "10", "unitCost", "14.00"),
                Map.of("productId", b, "quantity", "20", "unitCost", "5.00")));
        req.put("paid", "100.00");
        req.put("supplierInvoice", "Q-77");
        JsonNode p = body(post(owner, "/api/purchases", req).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total", is(240.0))).andExpect(jsonPath("$.unpaid", is(140.0)))
                .andExpect(jsonPath("$.items.length()", is(2))));
        assertEquals(20.0, productOf(owner, a).get("stockQty").asDouble());
        assertEquals(12.0, productOf(owner, a).get("purchasePrice").asDouble(), "(10*10 + 10*14) / 20");
        assertEquals(20.0, productOf(owner, b).get("stockQty").asDouble());

        get(owner, "/api/debts/" + p.get("debtId").asText()).andExpect(jsonPath("$.debtType", is("COMPANY_PAYABLE")))
                .andExpect(jsonPath("$.remainingAmount", is(140.0)));
        assertEquals(400.0, cashBalance(owner));
        get(owner, "/api/purchases").andExpect(jsonPath("$[0].number", is(p.get("number").asText())))
                .andExpect(jsonPath("$[0].companyName", is("Topdan MMC")));
    }

    @Test
    void withoutASupplierItMustBePaidInFullFromTheTill() throws Exception {
        String owner = register("Alış Nağd");
        activateSubscription(owner, "MONTHLY");
        String a = product(owner, "Çay", "3.00", null);
        Map<String, Object> req = new HashMap<>();
        req.put("items", List.of(Map.of("productId", a, "quantity", "5", "unitCost", "3.00")));
        req.put("paid", "10.00");
        post(owner, "/api/purchases", req).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("SUPPLIER_REQUIRED")));
        assertEquals(0.0, productOf(owner, a).get("stockQty").asDouble(), "nothing half-done");

        req.put("paid", "15.00");
        post(owner, "/api/purchases", req).andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("INSUFFICIENT_FUNDS")));
        deposit(owner, "20.00");
        post(owner, "/api/purchases", req).andExpect(status().isCreated());
        assertEquals(5.0, productOf(owner, a).get("stockQty").asDouble());
        assertEquals(5.0, cashBalance(owner));
        get(owner, "/api/cash/movements?kind=PURCHASE").andExpect(jsonPath("$[0].amount", is(15.0)));
    }
}
