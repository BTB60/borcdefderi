package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Strict tenant isolation: a store must never read or change another store's records. */
class TenantIsolationTest extends IntegrationTestBase {

    @Test
    void storeCannotReadOrPayAnotherStoresDebt() throws Exception {
        String storeA = register("Mağaza A");
        String storeB = register("Mağaza B");
        activateSubscription(storeA, "MONTHLY");
        activateSubscription(storeB, "MONTHLY");

        UUID customerA = createCustomer(storeA, "A Müştərisi", "+994501000001");
        UUID debtA = debtIdOf(storeA, "CUSTOMER_DEBT", customerA, "100.00");

        get(storeB, "/api/debts/" + debtA).andExpect(status().isNotFound());
        get(storeB, "/api/debts/" + debtA + "/history").andExpect(status().isNotFound());
        pay(storeB, debtA, "10.00", "CASH").andExpect(status().isNotFound());
        put(storeB, "/api/debts/" + debtA, Map.of("amount", "1.00", "debtDate", "2026-01-01", "version", 0))
                .andExpect(status().isNotFound());

        get(storeB, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)));

        // The balance of store A is untouched by store B's attempts.
        get(storeA, "/api/debts/" + debtA).andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAmount", is(100.0)));
    }

    @Test
    void storeCannotCreateDebtForAnotherStoresCustomer() throws Exception {
        String storeA = register("Party A");
        String storeB = register("Party B");
        activateSubscription(storeA, "MONTHLY");
        activateSubscription(storeB, "MONTHLY");

        UUID customerA = createCustomer(storeA, "Gizli Müştəri", "+994501000002");
        // Store B supplies store A's customer id: must be rejected, never linked.
        createDebt(storeB, "CUSTOMER_DEBT", customerA, "50.00")
                .andExpect(status().isNotFound());
    }

    @Test
    void storeCannotEditAnotherStoresCustomer() throws Exception {
        String storeA = register("Edit A");
        String storeB = register("Edit B");
        activateSubscription(storeA, "MONTHLY");
        activateSubscription(storeB, "MONTHLY");
        UUID customerA = createCustomer(storeA, "Orijinal Ad", "+994501000003");

        put(storeB, "/api/customers/" + customerA, Map.of("name", "Dəyişdirilib"))
                .andExpect(status().isNotFound());
        get(storeA, "/api/customers?q=Orijinal").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Orijinal Ad")));
    }

    @Test
    void storeIdInRequestBodyIsIgnored() throws Exception {
        String storeA = register("Body A");
        String storeB = register("Body B");
        activateSubscription(storeA, "MONTHLY");
        activateSubscription(storeB, "MONTHLY");

        // A forged storeId in the payload is not part of the contract and must not redirect the write.
        UUID customerB = createCustomer(storeB, "B müştəri", "+994501000004");
        post(storeA, "/api/debts", Map.of(
                "debtType", "CUSTOMER_DEBT",
                "partyId", customerB.toString(),
                "storeId", UUID.randomUUID().toString(),
                "amount", "20.00",
                "debtDate", "2026-03-01")).andExpect(status().isNotFound());
    }

    @Test
    void suspendedStoreLosesAccessImmediatelyAndCanBeReactivated() throws Exception {
        String owner = register("Dayandırılan Mağaza");
        activateSubscription(owner, "MONTHLY");
        createCustomer(owner, "Müştəri", "+994501000005");
        get(owner, "/api/customers").andExpect(status().isOk());

        JsonStoreId id = new JsonStoreId(storeIdOf(owner));
        post(adminToken(), "/api/admin/stores/" + id.value() + "/suspend", Map.of()).andExpect(status().isOk());
        get(owner, "/api/customers").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("STORE_SUSPENDED")));

        post(adminToken(), "/api/admin/stores/" + id.value() + "/activate", Map.of()).andExpect(status().isOk());
        get(owner, "/api/customers").andExpect(status().isOk());
    }

    private String storeIdOf(String token) throws Exception {
        return body(get(token, "/api/auth/me")).get("storeId").asText();
    }

    private record JsonStoreId(String value) {}
}
