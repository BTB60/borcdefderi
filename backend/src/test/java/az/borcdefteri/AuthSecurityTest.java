package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authentication, JWT enforcement, role gating and subscription gating. */
class AuthSecurityTest extends IntegrationTestBase {

    @Test
    void registerCreatesOwnerWithStoreAndNoSubscriptionYet() throws Exception {
        String email = uniqueEmail("reg");
        var res = postRaw("/api/auth/register", Map.of(
                "email", email, "password", OWNER_PASSWORD, "fullName", "Əli Məmmədov",
                "storeName", "Test Market", "storePhone", "+994501234567"));
        res.andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andExpect(jsonPath("$.me.role", is("STORE_OWNER")))
                .andExpect(jsonPath("$.me.storeName", is("Test Market")))
                .andExpect(jsonPath("$.me.subscription.status", is("NONE")));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = uniqueEmail("dup");
        Map<String, Object> payload = Map.of("email", email, "password", OWNER_PASSWORD,
                "fullName", "Dup", "storeName", "Dup Store");
        postRaw("/api/auth/register", payload).andExpect(status().isCreated());
        postRaw("/api/auth/register", payload).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("EMAIL_EXISTS")));
    }

    @Test
    void wrongPasswordReturnsUnauthorizedWithoutLeakingDetails() throws Exception {
        String email = uniqueEmail("pw");
        postRaw("/api/auth/register", Map.of("email", email, "password", OWNER_PASSWORD,
                "fullName", "Pw", "storeName", "Pw Store")).andExpect(status().isCreated());
        postRaw("/api/auth/login", Map.of("email", email, "password", "WrongPassword#1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")));
    }

    @Test
    void weakPasswordFailsValidation() throws Exception {
        postRaw("/api/auth/register", Map.of("email", uniqueEmail("weak"), "password", "short",
                "fullName", "Weak", "storeName", "Weak Store"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
    }

    @Test
    void businessEndpointsRequireAuthentication() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/debts")
                        .param("type", "CUSTOMER_DEBT"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessModulesRequireActiveSubscription() throws Exception {
        String owner = register("Abunəliksiz Mağaza");
        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("SUBSCRIPTION_REQUIRED")));
    }

    @Test
    void storeOwnerCannotUseSuperAdminEndpoints() throws Exception {
        String owner = register("Admin Olmayan");
        get(owner, "/api/admin/stores").andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanListStoresAfterLogin() throws Exception {
        get(adminToken(), "/api/admin/stores").andExpect(status().isOk());
    }

    @Test
    void healthEndpointReportsDatabaseStatus() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database", is("UP")));
    }
}
