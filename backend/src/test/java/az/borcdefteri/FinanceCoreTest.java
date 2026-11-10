package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ERP stage 2: cash desks / bank accounts, transfers, expenses, recurring expenses, budgets, payments into cash. */
class FinanceCoreTest extends IntegrationTestBase {

    private static final String RENT = "10000000-0000-0000-0000-000000000001";
    private static final String FUEL = "10000000-0000-0000-0000-000000000008";

    private JsonNode accounts(String token) throws Exception {
        return body(get(token, "/api/cash/accounts").andExpect(status().isOk()));
    }

    private String accountId(String token, String kind) throws Exception {
        for (JsonNode a : accounts(token)) {
            if (kind.equals(a.get("kind").asText()) && a.get("isDefault").asBoolean()) return a.get("id").asText();
        }
        JsonNode created = body(post(token, "/api/cash/accounts", Map.of("name", kind + " hesab", "kind", kind, "isDefault", true)));
        return created.get("id").asText();
    }

    private double balance(String token, String id) throws Exception {
        for (JsonNode a : accounts(token)) if (id.equals(a.get("id").asText())) return a.get("balance").asDouble();
        throw new AssertionError("account not found");
    }

    private Map<String, Object> move(String account, String amount) {
        Map<String, Object> b = new HashMap<>();
        b.put("accountId", account);
        b.put("amount", amount);
        return b;
    }

    private Map<String, Object> expense(String title, String category, String amount, String method) {
        Map<String, Object> b = new HashMap<>();
        b.put("title", title);
        b.put("categoryId", category);
        b.put("amount", amount);
        b.put("paymentMethod", method);
        return b;
    }

    @Test
    void debtPaymentsMoveMoneyButAreNotIncomeOrExpense() throws Exception {
        String owner = register("Kassa Bir");
        activateSubscription(owner, "MONTHLY");
        String cash = accountId(owner, "CASH");
        UUID customer = createCustomer(owner, "Kassa Müştəri", "+994501260001");
        UUID company = createCompany(owner, "Kassa Təchizat");
        post(owner, "/api/customers/" + customer + "/charge", Map.of("amount", "500.00")).andExpect(status().isOk());
        post(owner, "/api/customers/" + customer + "/pay", Map.of("amount", "200.00", "paymentMethod", "CASH")).andExpect(status().isCreated());
        post(owner, "/api/customers/" + customer + "/pay", Map.of("amount", "100.00", "paymentMethod", "CARD")).andExpect(status().isCreated());
        post(owner, "/api/companies/" + company + "/charge", Map.of("amount", "300.00", "debtType", "COMPANY_PAYABLE")).andExpect(status().isOk());
        post(owner, "/api/companies/" + company + "/pay", Map.of("amount", "50.00", "paymentMethod", "CASH", "debtType", "COMPANY_PAYABLE"))
                .andExpect(status().isCreated());

        assertEquals(150.0, balance(owner, cash), "200 collected in cash − 50 paid to the supplier");
        String card = accountId(owner, "CARD");
        assertEquals(100.0, balance(owner, card), "card payment lands on the card account");

        JsonNode moves = body(get(owner, "/api/cash/movements?accountId=" + cash));
        assertEquals(2, moves.size());
        for (JsonNode mv : moves) {
            assertTrue(java.util.Set.of("DEBT_COLLECTION", "SUPPLIER_PAYMENT").contains(mv.get("kind").asText()));
            assertNotNull(mv.get("paymentId").asText());
        }
    }

    @Test
    void openingDepositWithdrawTransferAndCountKeepBalancesRight() throws Exception {
        String owner = register("Kassa İki");
        activateSubscription(owner, "MONTHLY");
        String cash = accountId(owner, "CASH");
        String bank = accountId(owner, "BANK");

        post(owner, "/api/cash/opening", move(cash, "1000.00")).andExpect(status().isCreated());
        post(owner, "/api/cash/opening", move(cash, "5.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("OPENING_EXISTS")));
        post(owner, "/api/cash/deposit", move(cash, "250.00")).andExpect(status().isCreated());
        post(owner, "/api/cash/withdraw", move(cash, "100.00")).andExpect(status().isCreated());
        post(owner, "/api/cash/withdraw", move(cash, "5000.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("INSUFFICIENT_FUNDS")));

        Map<String, Object> tr = new HashMap<>(Map.of("fromAccountId", cash, "toAccountId", bank, "amount", "400.00"));
        String req = UUID.randomUUID().toString();
        tr.put("clientRequestId", req);
        post(owner, "/api/cash/transfer", tr).andExpect(status().isCreated()).andExpect(jsonPath("$", hasSize(2)));
        post(owner, "/api/cash/transfer", tr).andExpect(status().isCreated()); // double tap: same transfer, not a second one
        assertEquals(750.0, balance(owner, cash));
        assertEquals(400.0, balance(owner, bank));

        tr.put("toAccountId", cash);
        tr.remove("clientRequestId");
        post(owner, "/api/cash/transfer", tr).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("SAME_ACCOUNT")));

        post(owner, "/api/cash/count", Map.of("accountId", cash, "countedAmount", "740.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.expected", is(750.0)))
                .andExpect(jsonPath("$.difference", is(-10.0)))
                .andExpect(jsonPath("$.adjustment.kind", is("COUNT_ADJUSTMENT")));
        assertEquals(740.0, balance(owner, cash));

        JsonNode day = body(get(owner, "/api/cash/day-report").andExpect(status().isOk()));
        assertEquals(1140.0, day.get("totalClosing").asDouble(), "cash 740 + bank 400; transfers do not change the total");
        assertEquals(0.0, day.get("totalOpening").asDouble());
    }

    @Test
    void expensesTakeMoneyOutAndEditsAndDeletesPostReversals() throws Exception {
        String owner = register("Xərc Bir");
        activateSubscription(owner, "MONTHLY");
        String cash = accountId(owner, "CASH");
        post(owner, "/api/cash/opening", move(cash, "1000.00")).andExpect(status().isCreated());

        get(owner, "/api/expense-categories").andExpect(jsonPath("$", hasSize(15)));
        Map<String, Object> e = expense("Noyabr icarəsi", RENT, "500.00", "CASH");
        String req = UUID.randomUUID().toString();
        e.put("clientRequestId", req);
        JsonNode created = body(post(owner, "/api/expenses", e).andExpect(status().isCreated()));
        post(owner, "/api/expenses", e).andExpect(status().isCreated()); // same request: no second expense
        assertEquals(500.0, balance(owner, cash));
        get(owner, "/api/expenses").andExpect(jsonPath("$.total", is(1))).andExpect(jsonPath("$.sum", is(500.0)));

        String id = created.get("id").asText();
        Map<String, Object> edit = expense("Noyabr icarəsi", RENT, "450.00", "CASH");
        edit.put("version", created.get("version").asLong());
        put(owner, "/api/expenses/" + id, edit).andExpect(status().isOk()).andExpect(jsonPath("$.amount", is(450.0)));
        put(owner, "/api/expenses/" + id, edit).andExpect(status().isConflict()); // stale version
        assertEquals(550.0, balance(owner, cash), "reversal of 500 + new 450");

        mvc.perform(delete("/api/expenses/" + id).header("Authorization", "Bearer " + owner)).andExpect(status().isNoContent());
        assertEquals(1000.0, balance(owner, cash));
        get(owner, "/api/expenses").andExpect(jsonPath("$.total", is(0)));
        get(owner, "/api/expenses/" + id).andExpect(status().isNotFound());

        JsonNode moves = body(get(owner, "/api/cash/movements?accountId=" + cash));
        long reversals = 0;
        for (JsonNode mv : moves) if ("EXPENSE_REVERSAL".equals(mv.get("kind").asText())) reversals++;
        assertEquals(2, reversals, "history is kept: nothing is rewritten");
    }

    @Test
    void expenseSummaryAttachmentsAndValidation() throws Exception {
        String owner = register("Xərc İki");
        activateSubscription(owner, "MONTHLY");
        post(owner, "/api/expenses", expense("Benzin", FUEL, "40.00", "CARD")).andExpect(status().isCreated());
        JsonNode e = body(post(owner, "/api/expenses", expense("Dizel", FUEL, "60.00", "CASH")));
        post(owner, "/api/expenses", expense("Ofis", RENT, "100.00", "TRANSFER")).andExpect(status().isCreated());
        post(owner, "/api/expenses", expense("X", FUEL, "0.00", "CASH")).andExpect(status().isBadRequest());
        Map<String, Object> future = expense("Gələcək", FUEL, "5.00", "CASH");
        future.put("expenseDate", LocalDate.now(clock).plusDays(3).toString());
        post(owner, "/api/expenses", future).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("FUTURE_DATE")));

        JsonNode s = body(get(owner, "/api/expenses/summary?groupBy=category"));
        assertEquals(200.0, s.get("total").asDouble());
        assertEquals("Yanacaq", s.get("rows").get(0).get("key").asText());
        assertEquals(100.0, s.get("rows").get(0).get("total").asDouble());
        get(owner, "/api/expenses/summary?groupBy=month").andExpect(jsonPath("$.rows", hasSize(1)));
        get(owner, "/api/expenses/summary?groupBy=nonsense").andExpect(status().isBadRequest());

        String id = e.get("id").asText();
        mvc.perform(multipart("/api/expenses/" + id + "/attachments").file(new MockMultipartFile("file", "qebz.png", "image/png", pngBytes()))
                .header("Authorization", "Bearer " + owner)).andExpect(status().isCreated());
        mvc.perform(multipart("/api/expenses/" + id + "/attachments").file(new MockMultipartFile("file", "x.exe", "image/png", "MZ....".getBytes()))
                .header("Authorization", "Bearer " + owner)).andExpect(status().isBadRequest());
        get(owner, "/api/expenses/" + id + "/attachments").andExpect(jsonPath("$", hasSize(1)));

        post(owner, "/api/expense-categories", Map.of("name", "Kofe")).andExpect(status().isCreated());
        post(owner, "/api/expense-categories", Map.of("name", "icarə")).andExpect(status().isConflict());
        get(owner, "/api/expense-categories").andExpect(jsonPath("$", hasSize(16)));
    }

    @Test
    void recurringExpenseIsPlannedUntilPaidAndEachPeriodOnlyOnce() throws Exception {
        String owner = register("Təkrar");
        activateSubscription(owner, "MONTHLY");
        LocalDate start = LocalDate.now(clock).withDayOfMonth(1);
        Map<String, Object> plan = new HashMap<>(Map.of("title", "İcarə", "categoryId", RENT, "amount", "800.00",
                "frequency", "MONTHLY", "startDate", start.toString()));
        JsonNode r = body(post(owner, "/api/recurring-expenses", plan).andExpect(status().isCreated()));
        assertEquals(start.toString(), r.get("nextDueDate").asText());
        assertTrue(r.get("due").asBoolean());
        get(owner, "/api/expenses").andExpect(jsonPath("$.total", is(0))); // planned, not yet an expense

        String id = r.get("id").asText();
        post(owner, "/api/recurring-expenses/" + id + "/pay", Map.of("periodDate", start.toString(), "paymentMethod", "CASH"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount", is(800.0)));
        post(owner, "/api/recurring-expenses/" + id + "/pay", Map.of("periodDate", start.toString(), "paymentMethod", "CASH"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("ALREADY_PAID")));
        get(owner, "/api/expenses").andExpect(jsonPath("$.total", is(1)));
        JsonNode list = body(get(owner, "/api/recurring-expenses"));
        assertEquals(start.plusMonths(1).toString(), list.get(0).get("nextDueDate").asText());
        assertFalse(list.get(0).get("due").asBoolean());
    }

    @Test
    void budgetWarnsNearAndOverTheLimit() throws Exception {
        String owner = register("Büdcə");
        activateSubscription(owner, "MONTHLY");
        String month = LocalDate.now(clock).withDayOfMonth(1).toString();
        put(owner, "/api/budgets", Map.of("categoryId", FUEL, "month", month, "limit", "200.00")).andExpect(status().isOk());
        post(owner, "/api/expenses", expense("Benzin", FUEL, "170.00", "CASH")).andExpect(status().isCreated());
        get(owner, "/api/budgets").andExpect(jsonPath("$[0].state", is("NEAR"))).andExpect(jsonPath("$[0].percent", is(85)));
        post(owner, "/api/expenses", expense("Benzin 2", FUEL, "40.00", "CASH")).andExpect(status().isCreated());
        get(owner, "/api/budgets").andExpect(jsonPath("$[0].state", is("OVER")))
                .andExpect(jsonPath("$[0].actual", is(210.0))).andExpect(jsonPath("$[0].remaining", is(-10.0)));
        put(owner, "/api/budgets", Map.of("categoryId", FUEL, "month", month, "limit", "300.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].state", is("OK"))).andExpect(jsonPath("$[0].percent", is(70)));
    }

    @Test
    void permissionsAndStoreIsolation() throws Exception {
        String owner = register("İzolyasiya Kassa");
        activateSubscription(owner, "MONTHLY");
        String cash = accountId(owner, "CASH");
        JsonNode e = body(post(owner, "/api/expenses", expense("Su", RENT, "10.00", "CASH")));

        String other = register("Yad Kassa");
        activateSubscription(other, "MONTHLY");
        post(other, "/api/cash/deposit", move(cash, "10.00")).andExpect(status().isNotFound());
        get(other, "/api/expenses/" + e.get("id").asText()).andExpect(status().isNotFound());
        get(other, "/api/expenses").andExpect(jsonPath("$.total", is(0)));

        String email = uniqueEmail("kassir2");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Borc İşçisi",
                "permissions", perms("CUSTOMER_DEBTS"))).andExpect(status().isCreated());
        String noCash = login(email, EMPLOYEE_PASSWORD);
        get(noCash, "/api/cash/accounts").andExpect(status().isForbidden());
        get(noCash, "/api/expenses").andExpect(status().isForbidden());
        post(noCash, "/api/cash/accounts", Map.of("name", "X", "kind", "CASH")).andExpect(status().isForbidden());

        String email2 = uniqueEmail("kassir3");
        post(owner, "/api/employees", Map.of("email", email2, "password", EMPLOYEE_PASSWORD, "fullName", "Kassir",
                "permissions", perms("CASH", "EXPENSES"))).andExpect(status().isCreated());
        String cashier = login(email2, EMPLOYEE_PASSWORD);
        post(cashier, "/api/cash/deposit", move(cash, "20.00")).andExpect(status().isCreated());
        post(cashier, "/api/expenses", expense("Kağız", RENT, "5.00", "CASH")).andExpect(status().isCreated());
        post(cashier, "/api/cash/accounts", Map.of("name", "Yeni", "kind", "CASH")).andExpect(status().isForbidden()); // owner only
    }
}
