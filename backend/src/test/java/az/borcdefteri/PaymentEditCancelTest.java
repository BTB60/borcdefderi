package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Editing and cancelling (ləğv) payments: debt balance, ledger, cash desk, totals, audit, permissions. */
class PaymentEditCancelTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    private double balance(String token, String kind) throws Exception {
        for (JsonNode a : body(get(token, "/api/cash/accounts").andExpect(status().isOk()))) {
            if (kind.equals(a.get("kind").asText()) && a.get("isDefault").asBoolean()) return a.get("balance").asDouble();
        }
        return 0;
    }

    private static Map<String, Object> edit(String amount, String method) {
        Map<String, Object> m = new HashMap<>();
        m.put("amount", amount);
        m.put("paymentMethod", method);
        return m;
    }

    @Test
    void editingAmountAndMethodMovesDebtLedgerAndCashDesk() throws Exception {
        String owner = register("Düzəliş Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Düzəliş Müştəri", "+994501119900");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "100.00");
        String paymentId = body(pay(owner, debt, "50.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        assertEquals(50.0, balance(owner, "CASH"));

        // amount 50 → 40, same method: debt 60 left, cash desk 40
        Map<String, Object> req = edit("40.00", "CASH");
        req.put("description", "düzəldildi");
        put(owner, "/api/payments/" + paymentId, req).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(paymentId)))
                .andExpect(jsonPath("$.amount", is(40.0)))
                .andExpect(jsonPath("$.remainingAfter", is(60.0)))
                .andExpect(jsonPath("$.description", is("düzəldildi")))
                .andExpect(jsonPath("$.editedAt", notNullValue()));
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.paidAmount", is(40.0)))
                .andExpect(jsonPath("$.remainingAmount", is(60.0)));
        assertEquals(40.0, balance(owner, "CASH"));

        // method CASH → CARD: the money leaves the cash desk and lands on the card account
        put(owner, "/api/payments/" + paymentId, edit("40.00", "CARD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod", is("CARD")));
        assertEquals(0.0, balance(owner, "CASH"));
        assertEquals(40.0, balance(owner, "CARD"));

        // more than the debt allows (60 left + 40 of this payment = 100)
        put(owner, "/api/payments/" + paymentId, edit("100.01", "CARD")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("PAYMENT_EXCEEDS_BALANCE")));
        put(owner, "/api/payments/" + paymentId, edit("100.00", "CARD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.debtStatusAfter", is("PAID")));

        // ledger: payment 50, reversal 50 + payment 40 (amount), reversal 40 + payment 40 (method), reversal 40 + 100
        JsonNode history = body(get(owner, "/api/debts/" + debt + "/history"));
        long reversals = 0;
        for (JsonNode e : history) if ("REVERSAL".equals(e.get("type").asText())) reversals++;
        assertEquals(3, reversals);
        assertEquals(0.0, history.get(history.size() - 1).get("balanceAfter").asDouble());

        Long audits = jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PAYMENT_EDITED' AND entity_id = :id")
                .param("id", paymentId).query(Long.class).single();
        assertEquals(3L, audits);
    }

    @Test
    void cancelledPaymentRestoresDebtBooksCashBackAndDropsOutOfLists() throws Exception {
        String owner = register("Ləğv Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Ləğv Müştəri", "+994501119901");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "80.00");
        String paymentId = body(pay(owner, debt, "80.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.status", is("PAID")));

        post(owner, "/api/payments/" + paymentId + "/cancel", Map.of("reason", "səhv müştəri"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voidedAt", notNullValue()))
                .andExpect(jsonPath("$.voidReason", is("səhv müştəri")))
                .andExpect(jsonPath("$.remainingAfter", is(80.0)));
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.paidAmount", is(0.0)))
                .andExpect(jsonPath("$.remainingAmount", is(80.0)))
                .andExpect(jsonPath("$.status", is(not("PAID"))));
        assertEquals(0.0, balance(owner, "CASH"));

        // hidden from the default list (older apps), shown when asked for
        get(owner, "/api/payments").andExpect(jsonPath("$", hasSize(0)));
        get(owner, "/api/payments?includeVoided=true").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].voidedAt", notNullValue()));

        // a cancelled payment is final
        post(owner, "/api/payments/" + paymentId + "/cancel", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("PAYMENT_CANCELLED")));
        put(owner, "/api/payments/" + paymentId, edit("10.00", "CASH")).andExpect(status().isConflict());

        // the debt can be paid again
        pay(owner, debt, "80.00", "CARD").andExpect(status().isCreated());

        // the row is kept; deleting it is still impossible
        assertThrows(Exception.class, () -> jdbc.sql("DELETE FROM payments WHERE id = :id")
                .param("id", UUID.fromString(paymentId)).update());
        assertThrows(Exception.class, () -> jdbc.sql("UPDATE payments SET amount = 1 WHERE id = :id")
                .param("id", UUID.fromString(paymentId)).update());
        Long audits = jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PAYMENT_CANCELLED' AND entity_id = :id")
                .param("id", paymentId).query(Long.class).single();
        assertEquals(1L, audits);
        get(owner, "/api/payments/" + paymentId + "/receipt").andExpect(status().isOk());
    }

    @Test
    void supplierPaymentCancelPutsMoneyBackIntoTheDesk() throws Exception {
        String owner = register("Təchizatçı Ləğv");
        activateSubscription(owner, "MONTHLY");
        UUID company = createCompany(owner, "Təchizatçı MMC");
        UUID debt = debtIdOf(owner, "COMPANY_PAYABLE", company, "30.00");
        String paymentId = body(pay(owner, debt, "30.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        assertEquals(-30.0, balance(owner, "CASH"));
        post(owner, "/api/payments/" + paymentId + "/cancel", null).andExpect(status().isOk());
        assertEquals(0.0, balance(owner, "CASH"));
    }

    /** "Fariz" case: 700 owed, 100 paid by mistake (600 shown), payment cancelled → 700 again, for every section. */
    @Test
    void cancellingAPaymentRestoresTheFullBalanceInEverySection() throws Exception {
        String owner = register("Fariz Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID customer = createCustomer(owner, "Fariz", "+994501119903");
        UUID company = createCompany(owner, "Fariz MMC");
        String[][] cases = {{"CUSTOMER_DEBT", customer.toString()}, {"COMPANY_RECEIVABLE", company.toString()},
                {"COMPANY_PAYABLE", company.toString()}};
        for (String[] c : cases) {
            UUID debt = debtIdOf(owner, c[0], UUID.fromString(c[1]), "700.00");
            String paymentId = body(pay(owner, debt, "100.00", "CASH").andExpect(status().isCreated())).get("id").asText();
            get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.remainingAmount", is(600.0)));
            post(owner, "/api/payments/" + paymentId + "/cancel", Map.of()).andExpect(status().isOk());
            get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.amount", is(700.0)))
                    .andExpect(jsonPath("$.paidAmount", is(0.0)))
                    .andExpect(jsonPath("$.remainingAmount", is(700.0)))
                    .andExpect(jsonPath("$.status", is("OPEN")));
        }
        assertEquals(0.0, balance(owner, "CASH"));
        get(owner, "/api/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.customerDebts", is(700.0)))
                .andExpect(jsonPath("$.summary.companyReceivables", is(700.0)))
                .andExpect(jsonPath("$.summary.companyPayables", is(700.0)))
                .andExpect(jsonPath("$.summary.cashReceived", is(0.0)))
                .andExpect(jsonPath("$.summary.todayTotal", is(0.0)))
                .andExpect(jsonPath("$.recentTransactions", hasSize(0)));
        get(owner, "/api/payments").andExpect(jsonPath("$", hasSize(0)));
    }

    private UUID increase(String token, UUID debt, String amount) throws Exception {
        long version = body(get(token, "/api/debts/" + debt)).get("version").asLong();
        post(token, "/api/debts/" + debt + "/increase", Map.of("amount", amount, "version", version))
                .andExpect(status().isOk());
        JsonNode history = body(get(token, "/api/debts/" + debt + "/history"));
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("DEBT_INCREASED".equals(history.get(i).get("type").asText())) {
                return UUID.fromString(history.get(i).get("id").asText());
            }
        }
        throw new AssertionError("no increase entry");
    }

    @Test
    void cancellingADebtIncreaseRestoresThePreviousDebt() throws Exception {
        String owner = register("Artım Ləğv");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID company = createCompany(owner, "Artım MMC");
        UUID debt = debtIdOf(owner, "COMPANY_PAYABLE", company, "700.00");
        UUID entry = increase(owner, debt, "100.00");
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.amount", is(800.0)));

        String email = uniqueEmail("artim");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD,
                "fullName", "İşçi", "permissions", perms("COMPANY_PAYABLES"))).andExpect(status().is2xxSuccessful());
        String employee = login(email, EMPLOYEE_PASSWORD);
        // recorded by the owner: an employee may not cancel it
        post(employee, "/api/debts/" + debt + "/history/" + entry + "/cancel", Map.of()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("NOT_YOUR_OPERATION")));
        // their own increase they may
        UUID ownEntry = increase(employee, debt, "5.00");
        post(employee, "/api/debts/" + debt + "/history/" + ownEntry + "/cancel", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(800.0)));

        post(owner, "/api/debts/" + debt + "/history/" + entry + "/cancel", Map.of("reason", "səhv yazılıb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(700.0)))
                .andExpect(jsonPath("$.remainingAmount", is(700.0)));
        post(owner, "/api/debts/" + debt + "/history/" + entry + "/cancel", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("ALREADY_CANCELLED")));
        get(owner, "/api/dashboard").andExpect(jsonPath("$.summary.companyPayables", is(700.0)));

        JsonNode history = body(get(owner, "/api/debts/" + debt + "/history"));
        boolean marked = false;
        for (JsonNode e : history) {
            if (e.get("id").asText().equals(entry.toString())) marked = e.get("cancelled").asBoolean();
        }
        assertEquals(true, marked);
        JsonNode last = history.get(history.size() - 1);
        assertEquals("DEBT_ADJUSTED", last.get("type").asText());
        assertEquals(-100.0, last.get("amount").asDouble());
        assertEquals(entry.toString(), last.get("reversesEntryId").asText());

        // cancelling the creation entry cancels the whole debt: it leaves the lists and the totals
        String created = history.get(0).get("id").asText();
        post(owner, "/api/debts/" + debt + "/history/" + created + "/cancel", Map.of("reason", "səhv"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount", is(0.0)));
        get(owner, "/api/dashboard").andExpect(jsonPath("$.summary.companyPayables", is(0.0)));
        get(owner, "/api/debts?type=COMPANY_PAYABLE").andExpect(jsonPath("$.total", is(0)));
        post(owner, "/api/debts/" + debt + "/history/" + created + "/cancel", Map.of()).andExpect(status().isConflict());
        post(owner, "/api/debts/" + debt + "/increase", Map.of("amount", "5.00", "version", 99)).andExpect(status().isConflict());
    }

    @Test
    void aDebtWithPaymentsCannotBeCancelledUntilThePaymentsAre() throws Exception {
        String owner = register("Borc Ləğv");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Ləğv Müştəri", "+994501119903");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "50.00");
        String created = body(get(owner, "/api/debts/" + debt + "/history")).get(0).get("id").asText();
        String paid = body(pay(owner, debt, "10.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        post(owner, "/api/debts/" + debt + "/history/" + created + "/cancel", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("DEBT_HAS_PAYMENTS")));
        post(owner, "/api/payments/" + paid + "/cancel", Map.of()).andExpect(status().isOk());
        post(owner, "/api/debts/" + debt + "/history/" + created + "/cancel", Map.of()).andExpect(status().isOk());
        get(owner, "/api/dashboard").andExpect(jsonPath("$.summary.customerDebts", is(0.0)));
        // the card's history keeps both rows; the creation is marked cancelled
        JsonNode card = body(get(owner, "/api/customers/" + customer + "/profile"));
        boolean marked = false;
        for (JsonNode e : body(get(owner, "/api/customers/" + customer + "/history"))) {
            if (e.get("id").asText().equals(created)) marked = e.get("cancelled").asBoolean() && e.get("debtCancelled").asBoolean();
        }
        assertEquals(true, marked);
        assertEquals(0, card.get("debts").size());

        // a cancelled operation can be removed from the history; the whole cancelled debt goes with it
        int before = body(get(owner, "/api/customers/" + customer + "/history")).size();
        post(owner, "/api/debts/" + debt + "/history/" + created + "/hide", Map.of()).andExpect(status().isOk());
        assertEquals(0, body(get(owner, "/api/customers/" + customer + "/history")).size());
        assertEquals(true, before > 0);
    }

    @Test
    void onlyCancelledOperationsCanBeRemovedFromTheHistory() throws Exception {
        String owner = register("Tarixçə Sil");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Tarixçə Müştəri", "+994501119904");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "80.00");
        String created = body(get(owner, "/api/debts/" + debt + "/history")).get(0).get("id").asText();
        post(owner, "/api/debts/" + debt + "/history/" + created + "/hide", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("NOT_CANCELLED")));

        String paid = body(pay(owner, debt, "30.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        UUID inc = increase(owner, debt, "5.00");
        post(owner, "/api/payments/" + paid + "/cancel", Map.of()).andExpect(status().isOk());
        post(owner, "/api/debts/" + debt + "/history/" + inc + "/cancel", Map.of()).andExpect(status().isOk());
        assertEquals(5, body(get(owner, "/api/debts/" + debt + "/history")).size());

        String payEntry = null;
        for (JsonNode e : body(get(owner, "/api/debts/" + debt + "/history"))) {
            if (paid.equals(e.path("paymentId").asText()) && e.get("type").asText().startsWith("PAYMENT")) payEntry = e.get("id").asText();
        }
        post(owner, "/api/debts/" + debt + "/history/" + payEntry + "/hide", Map.of()).andExpect(status().isOk());
        post(owner, "/api/debts/" + debt + "/history/" + inc + "/hide", Map.of()).andExpect(status().isOk());
        JsonNode left = body(get(owner, "/api/debts/" + debt + "/history"));
        assertEquals(1, left.size());
        assertEquals("DEBT_CREATED", left.get(0).get("type").asText());
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.remainingAmount", is(80.0)));
    }

    @Test
    void anIncreaseThatIsAlreadyPaidCannotBeCancelledUntilThePaymentIs() throws Exception {
        String owner = register("Artım Ödənib");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Artım Müştəri", "+994501119904");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "50.00");
        UUID entry = increase(owner, debt, "30.00");
        String paymentId = body(pay(owner, debt, "80.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        post(owner, "/api/debts/" + debt + "/history/" + entry + "/cancel", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("INCREASE_ALREADY_PAID")));
        post(owner, "/api/payments/" + paymentId + "/cancel", Map.of()).andExpect(status().isOk());
        post(owner, "/api/debts/" + debt + "/history/" + entry + "/cancel", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(50.0)))
                .andExpect(jsonPath("$.remainingAmount", is(50.0)))
                .andExpect(jsonPath("$.status", is("OPEN")));
    }

    @Test
    void ownerChangesAnyEmployeeOnlyTheirOwn() throws Exception {
        String owner = register("İcazə Ləğv");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID customer = createCustomer(owner, "İcazə Müştəri", "+994501119902");
        String[] staff = new String[2];
        for (int i = 0; i < 2; i++) {
            String email = uniqueEmail("kassir" + i);
            post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD,
                    "fullName", "Kassir " + i, "permissions", perms("CUSTOMER_DEBTS", "EXPENSES"))).andExpect(status().is2xxSuccessful());
            staff[i] = login(email, EMPLOYEE_PASSWORD);
        }
        String employee = staff[0], colleague = staff[1];
        UUID debt = debtIdOf(employee, "CUSTOMER_DEBT", customer, "60.00");
        String own = body(pay(employee, debt, "20.00", "CASH").andExpect(status().isCreated())).get("id").asText();
        String byOwner = body(pay(owner, debt, "10.00", "CASH").andExpect(status().isCreated())).get("id").asText();

        // a colleague may not touch it, the employee who recorded it may
        post(colleague, "/api/payments/" + own + "/cancel", Map.of()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("NOT_YOUR_OPERATION")));
        put(employee, "/api/payments/" + own, edit("15.00", "CASH")).andExpect(status().isOk());
        post(employee, "/api/payments/" + byOwner + "/cancel", Map.of()).andExpect(status().isForbidden());

        // another store's owner does not even see it
        String other = register("Başqa Mağaza");
        activateSubscription(other, "MONTHLY");
        post(other, "/api/payments/" + own + "/cancel", Map.of()).andExpect(status().isNotFound());

        post(employee, "/api/payments/" + own + "/cancel", Map.of()).andExpect(status().isOk());
        post(owner, "/api/payments/" + byOwner + "/cancel", Map.of()).andExpect(status().isOk());
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.remainingAmount", is(60.0)));

        // expenses follow the same rule
        Map<String, Object> exp = Map.of("title", "Çay", "categoryId", "10000000-0000-0000-0000-000000000003", "amount", "3.00",
                "paymentMethod", "CASH");
        String ownExp = body(post(employee, "/api/expenses", exp).andExpect(status().isCreated())).get("id").asText();
        String ownerExp = body(post(owner, "/api/expenses", exp).andExpect(status().isCreated())).get("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/expenses/" + ownerExp)
                .header("Authorization", "Bearer " + employee)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/expenses/" + ownExp)
                .header("Authorization", "Bearer " + employee)).andExpect(status().is2xxSuccessful());
    }
}
