package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Employee accounts: module permissions, owner-only management and deactivation. */
class EmployeePermissionTest extends IntegrationTestBase {

    @Test
    void employeeOnlySeesAndWritesPermittedDebtSection() throws Exception {
        String owner = register("İşçi Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID customer = createCustomer(owner, "İşçi Müştərisi", "+994501113300");
        UUID company = createCompany(owner, "İşçi Şirkəti");

        String employeeEmail = uniqueEmail("cashier");
        body(post(owner, "/api/employees", Map.of(
                "email", employeeEmail,
                "password", EMPLOYEE_PASSWORD,
                "fullName", "Kassir Nigar",
                "permissions", perms("CUSTOMER_DEBTS"))))
                .get("memberId");
        String employee = login(employeeEmail, EMPLOYEE_PASSWORD);

        get(employee, "/api/auth/me").andExpect(jsonPath("$.role", is("EMPLOYEE")))
                .andExpect(jsonPath("$.permissions[0]", is("CUSTOMER_DEBTS")));

        // Permitted: create + pay a customer debt.
        UUID debt = UUID.fromString(body(createDebt(employee, "CUSTOMER_DEBT", customer, "25.00")).get("id").asText());
        pay(employee, debt, "25.00", "CASH").andExpect(status().isCreated())
                .andExpect(jsonPath("$.debtStatusAfter", is("PAID")));

        // Not permitted: company sections.
        createDebt(employee, "COMPANY_PAYABLE", company, "10.00").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("PERMISSION_DENIED")));
        get(employee, "/api/debts?type=COMPANY_RECEIVABLE").andExpect(status().isForbidden());

        // Not permitted: dashboard (REPORTS), owner-only employee management.
        get(employee, "/api/dashboard").andExpect(status().isForbidden());
        get(employee, "/api/employees").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("OWNER_ONLY")));
        post(employee, "/api/employees", Map.of("email", uniqueEmail("x"), "password", EMPLOYEE_PASSWORD,
                "fullName", "Hack", "permissions", perms())).andExpect(status().isForbidden());

        // Payments list is filtered to permitted sections only.
        get(employee, "/api/payments").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].debtType", is("CUSTOMER_DEBT")));
    }

    @Test
    void ownerGrantsAndRevokesPermissionsAndCanDeactivateEmployee() throws Exception {
        String owner = register("Yetki Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        String email = uniqueEmail("payer");
        String memberId = body(post(owner, "/api/employees", Map.of(
                "email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Ödəyici",
                "permissions", perms()))).get("memberId").asText();
        String employee = login(email, EMPLOYEE_PASSWORD);

        get(employee, "/api/debts?type=COMPANY_PAYABLE").andExpect(status().isForbidden());

        put(owner, "/api/employees/" + memberId, Map.of("permissions", perms("COMPANY_PAYABLES"), "active", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0]", is("COMPANY_PAYABLES")));
        get(employee, "/api/debts?type=COMPANY_PAYABLE").andExpect(status().isOk());

        put(owner, "/api/employees/" + memberId, Map.of("permissions", perms("COMPANY_PAYABLES"), "active", false))
                .andExpect(status().isOk());
        get(employee, "/api/debts?type=COMPANY_PAYABLE").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("MEMBER_DISABLED")));
    }

    @Test
    void employeeCannotUseAnotherStoresEmployeeRecord() throws Exception {
        String ownerA = register("Işçi A");
        String ownerB = register("Işçi B");
        activateSubscription(ownerA, "MONTHLY");
        activateSubscription(ownerB, "MONTHLY");
        String memberA = body(post(ownerA, "/api/employees", Map.of(
                "email", uniqueEmail("a-worker"), "password", EMPLOYEE_PASSWORD, "fullName", "A İşçi",
                "permissions", perms("CUSTOMER_DEBTS")))).get("memberId").asText();

        put(ownerB, "/api/employees/" + memberA, Map.of("permissions", perms("REPORTS"), "active", true))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPermissionNameIsRejected() throws Exception {
        String owner = register("Yanlış İcazə");
        activateSubscription(owner, "MONTHLY");
        post(owner, "/api/employees", Map.of("email", uniqueEmail("bad"), "password", EMPLOYEE_PASSWORD,
                "fullName", "Bad", "permissions", perms("SUPER_POWERS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_PERMISSION")));
    }
}
