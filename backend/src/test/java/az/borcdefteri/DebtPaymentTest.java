package az.borcdefteri;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer debts, company receivables/payables, cash & card payments, balances and ledger. */
class DebtPaymentTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void partialCashThenCardPaymentReachesPaidAndBlocksFurtherPayments() throws Exception {
        String owner = register("Ödəniş Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Rəşad Həsənov", "+994501112200");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "100.00");

        pay(owner, debt, "30.00", "CASH").andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAfter", is(70.0)))
                .andExpect(jsonPath("$.debtStatusAfter", is("PARTIAL")));

        get(owner, "/api/debts/" + debt).andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount", is(30.0)))
                .andExpect(jsonPath("$.remainingAmount", is(70.0)))
                .andExpect(jsonPath("$.status", is("PARTIAL")));

        pay(owner, debt, "70.00", "CARD").andExpect(status().isCreated())
                .andExpect(jsonPath("$.debtStatusAfter", is("PAID")))
                .andExpect(jsonPath("$.paymentMethod", is("CARD")));

        get(owner, "/api/debts/" + debt).andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAmount", is(0.0)))
                .andExpect(jsonPath("$.status", is("PAID")));

        pay(owner, debt, "1.00", "CASH").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("DEBT_ALREADY_PAID")));
    }

    @Test
    void overpaymentIsRejectedAndBalanceNeverGoesNegative() throws Exception {
        String owner = register("Overpay Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Leyla", "+994501112201");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "50.00");

        pay(owner, debt, "60.00", "CASH").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("PAYMENT_EXCEEDS_BALANCE")));
        get(owner, "/api/debts/" + debt).andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAmount", is(50.0)))
                .andExpect(jsonPath("$.paidAmount", is(0.0)));
    }

    @Test
    void zeroAndNegativePaymentsAreInvalid() throws Exception {
        String owner = register("Validasiya Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Vüqar", "+994501112202");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "10.00");

        pay(owner, debt, "0.00", "CASH").andExpect(status().isBadRequest());
        pay(owner, debt, "-5.00", "CARD").andExpect(status().isBadRequest());
        pay(owner, debt, "5.123", "CASH").andExpect(status().isBadRequest());
        get(owner, "/api/debts/" + debt).andExpect(jsonPath("$.remainingAmount", is(10.0)));
    }

    @Test
    void customerDebtsReceivablesAndPayablesAreIndependentSections() throws Exception {
        String owner = register("Üç Bölmə Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID customer = createCustomer(owner, "Müştəri", "+994501112203");
        UUID company = createCompany(owner, "Təchizatçı MMC");

        debtIdOf(owner, "CUSTOMER_DEBT", customer, "100.00");
        debtIdOf(owner, "COMPANY_RECEIVABLE", company, "200.00");
        debtIdOf(owner, "COMPANY_PAYABLE", company, "50.00");

        get(owner, "/api/debts?type=CUSTOMER_DEBT").andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].partyName", is("Müştəri")));
        get(owner, "/api/debts?type=COMPANY_RECEIVABLE").andExpect(jsonPath("$.totals.remaining", is(200.0)));
        get(owner, "/api/debts?type=COMPANY_PAYABLE").andExpect(jsonPath("$.totals.remaining", is(50.0)));
    }

    @Test
    void companyDebtCannotBeCreatedWithCustomerParty() throws Exception {
        String owner = register("Yanlış Tip");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Müştəri X", "+994501112204");
        createDebt(owner, "COMPANY_PAYABLE", customer, "10.00").andExpect(status().isNotFound());
    }

    @Test
    void searchAndStatusFiltersNarrowResults() throws Exception {
        String owner = register("Axtarış Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID alice = createCustomer(owner, "Aysel Quliyeva", "+994501112205");
        UUID bob = createCustomer(owner, "Orxan Məmmədov", "+994501112206");
        UUID debtAlice = debtIdOf(owner, "CUSTOMER_DEBT", alice, "40.00");
        debtIdOf(owner, "CUSTOMER_DEBT", bob, "60.00");
        pay(owner, debtAlice, "40.00", "CASH").andExpect(status().isCreated());

        get(owner, "/api/debts?type=CUSTOMER_DEBT&q=aysel").andExpect(jsonPath("$.total", is(1)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT&status=PAID").andExpect(jsonPath("$.total", is(1)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT&status=UNPAID").andExpect(jsonPath("$.total", is(1)));
        get(owner, "/api/debts?type=CUSTOMER_DEBT&status=BOGUS").andExpect(status().isBadRequest());
    }

    @Test
    void dashboardTotalsComeFromPostgresAndSplitCashAndCard() throws Exception {
        String owner = register("Dashboard Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID customer = createCustomer(owner, "Dashboard Müştəri", "+994501112207");
        UUID company = createCompany(owner, "Dashboard Şirkət");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "100.00");
        debtIdOf(owner, "COMPANY_RECEIVABLE", company, "250.00");
        debtIdOf(owner, "COMPANY_PAYABLE", company, "75.00");
        pay(owner, debt, "30.00", "CASH").andExpect(status().isCreated());
        pay(owner, debt, "20.00", "CARD").andExpect(status().isCreated());

        get(owner, "/api/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.customerDebts", is(50.0)))
                .andExpect(jsonPath("$.summary.companyReceivables", is(250.0)))
                .andExpect(jsonPath("$.summary.companyPayables", is(75.0)))
                .andExpect(jsonPath("$.summary.cashReceived", is(30.0)))
                .andExpect(jsonPath("$.summary.cardReceived", is(20.0)))
                .andExpect(jsonPath("$.summary.todayTotal", is(50.0)))
                .andExpect(jsonPath("$.daily", hasSize(14)))
                .andExpect(jsonPath("$.monthly", not(empty())))
                .andExpect(jsonPath("$.recentTransactions", hasSize(2)))
                .andExpect(jsonPath("$.outstanding", not(empty())));
    }

    @Test
    void ledgerRecordsCreationAndEveryPaymentWithBalanceAfter() throws Exception {
        String owner = register("Ledger Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Ledger Müştəri", "+994501112208");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "90.00");
        pay(owner, debt, "10.00", "CASH").andExpect(status().isCreated());
        pay(owner, debt, "80.00", "CARD").andExpect(status().isCreated());

        get(owner, "/api/debts/" + debt + "/history").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].type", is("DEBT_CREATED")))
                .andExpect(jsonPath("$[1].type", is("PAYMENT_CASH")))
                .andExpect(jsonPath("$[1].balanceAfter", is(80.0)))
                .andExpect(jsonPath("$[2].type", is("PAYMENT_CARD")))
                .andExpect(jsonPath("$[2].balanceAfter", is(0.0)));
    }

    @Test
    void financialLedgerAndPaymentsAreAppendOnlyAtDatabaseLevel() throws Exception {
        String owner = register("Append Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Append Müştəri", "+994501112209");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "15.00");
        pay(owner, debt, "5.00", "CASH").andExpect(status().isCreated());

        assertThrows(Exception.class, () -> jdbc.sql(
                "UPDATE financial_transactions SET amount = 0 WHERE debt_id = :d").param("d", debt).update());
        assertThrows(Exception.class, () -> jdbc.sql(
                "DELETE FROM payments WHERE debt_id = :d").param("d", debt).update());
    }

    @Test
    void editingAmountBelowPaidIsRejectedAndValidVersionUpdatesBalance() throws Exception {
        String owner = register("Redaktə Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID customer = createCustomer(owner, "Redaktə Müştəri", "+994501112210");
        UUID debt = debtIdOf(owner, "CUSTOMER_DEBT", customer, "100.00");
        pay(owner, debt, "40.00", "CASH").andExpect(status().isCreated());

        long version = body(get(owner, "/api/debts/" + debt)).get("version").asLong();
        put(owner, "/api/debts/" + debt, Map.of("amount", "30.00", "debtDate", "2026-01-10", "version", version))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("AMOUNT_BELOW_PAID")));

        put(owner, "/api/debts/" + debt, Map.of("amount", "120.00", "debtDate", "2026-01-10", "version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAmount", is(80.0)))
                .andExpect(jsonPath("$.status", is("PARTIAL")));

        put(owner, "/api/debts/" + debt, Map.of("amount", "120.00", "debtDate", "2026-01-10", "version", version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("VERSION_CONFLICT")));
    }
}
