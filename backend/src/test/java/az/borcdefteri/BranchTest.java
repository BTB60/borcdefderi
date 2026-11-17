package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Filiallar: one owner, several stores — open a branch, send goods there, switch, see all branches together. */
class BranchTest extends IntegrationTestBase {

    @Test
    void ownerOpensABranchSendsGoodsAndSwitches() throws Exception {
        String owner = register("Mərkəz Mağaza");
        activateSubscription(owner, "SEMI_ANNUAL");
        String tea = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Çay", "code", "CAY-1", "barcode", "4760001000017",
                "purchasePrice", "3.00", "salePrice", "5.00", "initialStock", "10"))).andExpect(status().isCreated())).get("id").asText();

        get(owner, "/api/branches").andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].main", is(true)))
                .andExpect(jsonPath("$[0].current", is(true)));
        String branch = body(post(owner, "/api/branches", Map.of("name", "Yasamal filialı", "address", "Yasamal"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current", is(false))).andExpect(jsonPath("$.main", is(false))))
                .get("id").asText();
        get(owner, "/api/branches").andExpect(jsonPath("$", hasSize(2)));

        // 4 teas go to the branch: created there with the same code and barcode
        post(owner, "/api/branches/transfer", Map.of("toStoreId", branch, "items", List.of(Map.of("productId", tea, "quantity", "4"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created", contains("Çay")));
        get(owner, "/api/products/" + tea).andExpect(jsonPath("$.stockQty", is(6.0)));
        // the main store sees the branch has 4 teas (by code)
        get(owner, "/api/products/branch-stock").andExpect(jsonPath("$['" + tea + "'][0].storeName", is("Yasamal filialı")))
                .andExpect(jsonPath("$['" + tea + "'][0].qty", is(4)));
        get(owner, "/api/products/branch-stock?productId=" + tea).andExpect(jsonPath("$.*", hasSize(1)));
        // more than in stock is refused
        post(owner, "/api/branches/transfer", Map.of("toStoreId", branch, "items", List.of(Map.of("productId", tea, "quantity", "7"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("INSUFFICIENT_STOCK")));

        // switch: every screen is now the branch
        post(owner, "/api/branches/" + branch + "/switch", Map.of()).andExpect(status().isOk());
        get(owner, "/api/auth/me").andExpect(jsonPath("$.storeName", is("Yasamal filialı")));
        activateSubscription(owner, "SEMI_ANNUAL"); // the branch has its own subscription
        String there = body(get(owner, "/api/products").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code", is("CAY-1"))).andExpect(jsonPath("$[0].stockQty", is(4.0)))).get(0).get("id").asText();
        // sending back adds to the existing tea (found by code), nothing new is created
        post(owner, "/api/branches/transfer", Map.of("toStoreId", body(get(owner, "/api/branches")).get(0).get("id").asText(),
                "items", List.of(Map.of("productId", there, "quantity", "1")))).andExpect(jsonPath("$.created", hasSize(0)));

        // all branches together
        get(owner, "/api/branches/overview").andExpect(jsonPath("$.branches", hasSize(2)))
                .andExpect(jsonPath("$.total.stockValue", is(30.0)));

        // back to the main store
        String main = body(get(owner, "/api/branches")).get(0).get("id").asText();
        post(owner, "/api/branches/" + main + "/switch", Map.of()).andExpect(status().isOk());
        get(owner, "/api/auth/me").andExpect(jsonPath("$.storeName", is("Mərkəz Mağaza")));
        get(owner, "/api/products/" + tea).andExpect(jsonPath("$.stockQty", is(7.0)));

        // employees do not see branches; a stranger's store cannot be switched to
        String email = uniqueEmail("kassir");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Kassir", "permissions", perms("SALES")))
                .andExpect(status().isCreated());
        String cashier = login(email, EMPLOYEE_PASSWORD);
        get(cashier, "/api/branches").andExpect(status().isForbidden());
        // a cashier of the main store sees the other branch's stock too (to tell the customer where it is)
        get(cashier, "/api/products/branch-stock?productId=" + tea).andExpect(jsonPath("$['" + tea + "'][0].qty", is(3)));
        String other = register("Başqa Mağaza");
        String otherStore = body(get(other, "/api/auth/me")).get("storeId").asText();
        post(owner, "/api/branches/" + otherStore + "/switch", Map.of()).andExpect(status().isNotFound());
    }
}
