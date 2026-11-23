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

/** Tədarükçünü dəyiş: the debt of a receipt moves to the right supplier, stock stays; a sale price can be set with it. */
class PurchaseSupplierChangeTest extends IntegrationTestBase {

    private double owed(String token, UUID company) throws Exception {
        double sum = 0;
        for (JsonNode d : body(get(token, "/api/debts?type=COMPANY_PAYABLE&size=100")).get("items")) {
            if (company.toString().equals(d.get("partyId").asText())) sum += d.get("remainingAmount").asDouble();
        }
        return sum;
    }

    @Test
    void theReceiptsDebtMovesToTheRightSupplierAndStockStays() throws Exception {
        String owner = register("Qaimə Mağaza");
        activateSubscription(owner, "MONTHLY");
        String un = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Un", "purchasePrice", "10", "salePrice", "15"))))
                .get("id").asText();
        UUID wrong = createCompany(owner, "Sevinc MMC");
        UUID right = createCompany(owner, "Azərsun");
        // the wrong supplier already had an older, separate debt
        post(owner, "/api/companies/" + wrong + "/charge", Map.of("amount", "50.00", "debtType", "COMPANY_PAYABLE")).andExpect(status().isOk());

        Map<String, Object> req = new HashMap<>();
        req.put("companyId", wrong.toString());
        req.put("items", List.of(Map.of("productId", un, "quantity", "10", "unitCost", "20.00", "salePrice", "29.90")));
        req.put("paid", "0");
        req.put("supplierInvoice", "Q-1");
        String id = body(post(owner, "/api/purchases", req).andExpect(status().isCreated())).get("id").asText();
        get(owner, "/api/products/" + un).andExpect(jsonPath("$.salePrice", is(29.9))).andExpect(jsonPath("$.stockQty", is(10.0)));
        assertEquals(250.0, owed(owner, wrong));

        post(owner, "/api/purchases/" + id + "/supplier", Map.of("companyId", wrong.toString())).andExpect(status().isBadRequest());
        post(owner, "/api/purchases/" + id + "/supplier", Map.of("companyId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        post(owner, "/api/purchases/" + id + "/supplier", Map.of("companyId", right.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName", is("Azərsun"))).andExpect(jsonPath("$.unpaid", is(200.0)));
        assertEquals(50.0, owed(owner, wrong), "only the old, separate debt is left");
        assertEquals(200.0, owed(owner, right));
        get(owner, "/api/products/" + un).andExpect(jsonPath("$.stockQty", is(10.0)));

        // paid in part, then moved: only the open part moves
        req.put("paid", "30.00");
        req.put("items", List.of(Map.of("productId", un, "quantity", "5", "unitCost", "20.00")));
        req.put("paymentMethod", "TRANSFER");
        String id2 = body(post(owner, "/api/purchases", req).andExpect(status().isCreated())).get("id").asText();
        assertEquals(120.0, owed(owner, wrong));
        post(owner, "/api/purchases/" + id2 + "/supplier", Map.of("companyId", right.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid", is(30.0))).andExpect(jsonPath("$.unpaid", is(70.0)));
        assertEquals(50.0, owed(owner, wrong));
        assertEquals(270.0, owed(owner, right));

        // a receipt without a supplier just gets one (it was paid in full then)
        Map<String, Object> cashReq = new HashMap<>();
        cashReq.put("items", List.of(Map.of("productId", un, "quantity", "1", "unitCost", "20.00")));
        cashReq.put("paid", "0");
        String id3 = body(post(owner, "/api/purchases", cashReq).andExpect(status().isCreated())).get("id").asText();
        post(owner, "/api/purchases/" + id3 + "/supplier", Map.of("companyId", right.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName", is("Azərsun")));
        assertEquals(270.0, owed(owner, right));

        // a storekeeper without the supplier-debt permission cannot move debts
        String email = uniqueEmail("keeper");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Anbardar",
                "permissions", perms("INVENTORY"))).andExpect(status().isCreated());
        post(login(email, EMPLOYEE_PASSWORD), "/api/purchases/" + id + "/supplier", Map.of("companyId", wrong.toString()))
                .andExpect(status().isForbidden());
    }
}
