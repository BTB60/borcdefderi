package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Köməkçi admin: only the listed admin calls work; packages, money, settings, users and deletions do not. */
class SupportAdminTest extends IntegrationTestBase {

    @Test
    void aHelperCanApprovePaymentsButNothingElse() throws Exception {
        String email = "helper-" + UUID.randomUUID() + "@test.az";
        JsonNode created = body(post(adminToken(), "/api/admin/support-admins", Map.of("email", email, "fullName", "Köməkçi"))
                .andExpect(status().isCreated()));
        String password = created.get("temporaryPassword").asText();
        assertThat(password).hasSizeGreaterThanOrEqualTo(12);
        String helper = login(email, password);

        // a store asks to pay
        String owner = register("Köməkçi Test");
        JsonNode sub = body(uploadReceipt(owner, "MONTHLY", "r.png", "image/png", pngBytes()));
        String storeId = sub.get("storeId").asText();

        // allowed
        get(helper, "/api/admin/stores").andExpect(status().isOk());
        get(helper, "/api/admin/stores/" + storeId).andExpect(status().isOk());
        get(helper, "/api/admin/subscriptions?status=PENDING").andExpect(status().isOk());
        post(helper, "/api/admin/stores/" + storeId + "/remind", Map.of("channel", "WHATSAPP")).andExpect(status().isOk());
        post(helper, "/api/admin/stores/" + storeId + "/notes", Map.of("body", "Zəng etdim")).andExpect(status().isCreated());
        put(helper, "/api/admin/stores/" + storeId + "/tags", Map.of("tags", java.util.List.of("VIP"))).andExpect(status().isOk());
        get(helper, "/api/admin/follow-ups").andExpect(status().isOk());
        post(helper, "/api/admin/subscriptions/" + sub.get("id").asText() + "/approve", Map.of()).andExpect(status().isOk());

        // not allowed
        get(helper, "/api/admin/overview").andExpect(status().isForbidden());
        get(helper, "/api/admin/revenue").andExpect(status().isForbidden());
        get(helper, "/api/admin/plans").andExpect(status().isForbidden());
        get(helper, "/api/admin/users").andExpect(status().isForbidden());
        get(helper, "/api/admin/export").andExpect(status().isForbidden());
        get(helper, "/api/admin/audit-logs").andExpect(status().isForbidden());
        get(helper, "/api/admin/support-admins").andExpect(status().isForbidden());
        get(helper, "/api/admin/stores/" + storeId + "/operations").andExpect(status().isForbidden());
        post(helper, "/api/admin/stores/" + storeId + "/suspend", Map.of()).andExpect(status().isForbidden());
        post(helper, "/api/admin/stores/" + storeId + "/extend", Map.of("days", 30)).andExpect(status().isForbidden());
        post(helper, "/api/admin/stores/" + storeId + "/reset-data", Map.of("confirmName", "Köməkçi Test")).andExpect(status().isForbidden());
        post(helper, "/api/admin/announcements", Map.of("title", "t", "body", "b")).andExpect(status().isForbidden());
        post(helper, "/api/admin/support-admins", Map.of("email", "x@y.az", "fullName", "x")).andExpect(status().isForbidden());

        // the platform admin switches the helper off: the old token stops working
        JsonNode list = body(get(adminToken(), "/api/admin/support-admins"));
        String helperId = null;
        for (JsonNode h : list) if (h.get("email").asText().equals(email)) helperId = h.get("id").asText();
        post(adminToken(), "/api/admin/support-admins/" + helperId + "/enabled", Map.of("enabled", false)).andExpect(status().isOk());
        get(helper, "/api/admin/stores").andExpect(status().isUnauthorized());
    }
}
