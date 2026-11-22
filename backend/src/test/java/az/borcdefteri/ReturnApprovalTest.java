package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manager approval of sale returns: limit, PIN, lockout, who approved. */
class ReturnApprovalTest extends IntegrationTestBase {

    private String cashier(String owner, String... permissions) throws Exception {
        String email = uniqueEmail("kassir");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Kassir Aynur",
                "permissions", perms(permissions))).andExpect(status().isCreated());
        return login(email, EMPLOYEE_PASSWORD);
    }

    /** Sells 4 × 10 AZN for cash and returns [saleId, saleItemId]. */
    private String[] cashSale(String token, String productId) throws Exception {
        var s = body(post(token, "/api/sales", Map.of("items", List.of(Map.of("productId", productId, "quantity", "4")),
                "paidCash", "40.00")).andExpect(status().isCreated()));
        return new String[]{s.get("id").asText(), s.get("items").get(0).get("id").asText()};
    }

    private Map<String, Object> ret(String line, String qty, String pin) {
        Map<String, Object> b = new HashMap<>();
        b.put("items", List.of(Map.of("saleItemId", line, "quantity", qty)));
        if (pin != null) b.put("approvalPin", pin);
        return b;
    }

    @Test
    void returnAboveLimitNeedsManagerPin() throws Exception {
        String owner = register("Təsdiq Bir");
        activateSubscription(owner, "MONTHLY");
        String p = body(post(owner, "/api/products", Map.of("name", "Kofe", "purchasePrice", "6.00", "salePrice", "10.00",
                "initialStock", "50")).andExpect(status().isCreated())).get("id").asText();
        String kassir = cashier(owner, "SALES");

        // off by default: the cashier returns freely
        String[] s1 = cashSale(kassir, p);
        post(kassir, "/api/sales/" + s1[0] + "/returns", ret(s1[1], "1", null)).andExpect(status().isOk());

        // only the owner sets the policy; the owner sets a PIN (password-protected, no trivial PINs)
        put(kassir, "/api/return-approval/policy", Map.of("enabled", true, "limit", "15.00")).andExpect(status().isForbidden());
        put(owner, "/api/return-approval/policy", Map.of("enabled", true, "limit", "15.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true))).andExpect(jsonPath("$.limit", is(15.0)));
        put(kassir, "/api/return-approval/pin", Map.of("pin", "4826", "currentPassword", EMPLOYEE_PASSWORD))
                .andExpect(status().isForbidden());
        put(owner, "/api/return-approval/pin", Map.of("pin", "4826", "currentPassword", "yanlis-sifre"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("WRONG_PASSWORD")));
        put(owner, "/api/return-approval/pin", Map.of("pin", "1234", "currentPassword", OWNER_PASSWORD))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("WEAK_PIN")));
        put(owner, "/api/return-approval/pin", Map.of("pin", "4826", "currentPassword", OWNER_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasPin", is(true))).andExpect(jsonPath("$.canApprove", is(true)));
        get(kassir, "/api/return-approval").andExpect(jsonPath("$.canApprove", is(false))).andExpect(jsonPath("$.hasPin", is(false)));

        // up to the limit: no PIN
        String[] s2 = cashSale(kassir, p);
        post(kassir, "/api/sales/" + s2[0] + "/returns", ret(s2[1], "1", null)).andExpect(status().isOk());
        // above: PIN required, wrong PIN refused, nothing returned
        post(kassir, "/api/sales/" + s2[0] + "/returns", ret(s2[1], "2", null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error", is("APPROVAL_REQUIRED")));
        post(kassir, "/api/sales/" + s2[0] + "/returns", ret(s2[1], "2", "0000"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error", is("APPROVAL_PIN_INVALID")));
        get(kassir, "/api/sales/" + s2[0]).andExpect(jsonPath("$.returnedAmount", is(10.0)));
        // right PIN: done, and the manager is recorded
        post(kassir, "/api/sales/" + s2[0] + "/returns", ret(s2[1], "2", "4826")).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedAmount", is(30.0)))
                .andExpect(jsonPath("$.returns[1].approvedByName", is("Sahib Təsdiq Bir")));

        // cancel is a return too
        String[] s3 = cashSale(kassir, p);
        post(kassir, "/api/sales/" + s3[0] + "/cancel", Map.of())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error", is("APPROVAL_REQUIRED")));
        post(kassir, "/api/sales/" + s3[0] + "/cancel", Map.of("approvalPin", "4826")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        // the owner (and RETURN_APPROVE holders) need no PIN
        String[] s4 = cashSale(owner, p);
        post(owner, "/api/sales/" + s4[0] + "/returns", ret(s4[1], "4", null)).andExpect(status().isOk());
        String manager = cashier(owner, "SALES", "RETURN_APPROVE");
        String[] s5 = cashSale(manager, p);
        post(manager, "/api/sales/" + s5[0] + "/returns", ret(s5[1], "4", null)).andExpect(status().isOk());

        // a manager's PIN may not repeat another approver's
        put(manager, "/api/return-approval/pin", Map.of("pin", "4826", "currentPassword", EMPLOYEE_PASSWORD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("PIN_TAKEN")));
        put(manager, "/api/return-approval/pin", Map.of("pin", "7391", "currentPassword", EMPLOYEE_PASSWORD))
                .andExpect(status().isOk());
        String[] s6 = cashSale(kassir, p);
        post(kassir, "/api/sales/" + s6[0] + "/returns", ret(s6[1], "4", "7391")).andExpect(status().isOk())
                .andExpect(jsonPath("$.returns[0].approvedByName", is("Kassir Aynur")));
    }

    @Test
    void fiveWrongPinsLockTheCashier() throws Exception {
        String owner = register("Təsdiq İki");
        activateSubscription(owner, "MONTHLY");
        String p = body(post(owner, "/api/products", Map.of("name", "Şokolad", "purchasePrice", "6.00", "salePrice", "10.00",
                "initialStock", "50")).andExpect(status().isCreated())).get("id").asText();
        put(owner, "/api/return-approval/policy", Map.of("enabled", true, "limit", "0")).andExpect(status().isOk());
        put(owner, "/api/return-approval/pin", Map.of("pin", "5729", "currentPassword", OWNER_PASSWORD)).andExpect(status().isOk());
        String kassir = cashier(owner, "SALES");
        String[] s = cashSale(kassir, p);

        for (int left = 4; left >= 1; left--) {
            post(kassir, "/api/sales/" + s[0] + "/returns", ret(s[1], "1", "1111"))
                    .andExpect(jsonPath("$.error", is("APPROVAL_PIN_INVALID")))
                    .andExpect(jsonPath("$.message", containsString(left + " cəhd")));
        }
        post(kassir, "/api/sales/" + s[0] + "/returns", ret(s[1], "1", "1111"))
                .andExpect(jsonPath("$.error", is("APPROVAL_LOCKED")));
        // even the right PIN is refused while locked
        post(kassir, "/api/sales/" + s[0] + "/returns", ret(s[1], "1", "5729"))
                .andExpect(jsonPath("$.error", is("APPROVAL_LOCKED")));
        get(kassir, "/api/sales/" + s[0]).andExpect(jsonPath("$.returnedAmount", is(0.0)));

        // the owner can remove their PIN
        var res = body(mvc.perform(delete("/api/return-approval/pin").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()));
        assertFalse(res.get("hasPin").asBoolean());
    }
}
