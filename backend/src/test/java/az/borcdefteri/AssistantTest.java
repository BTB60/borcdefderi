package az.borcdefteri;

import az.borcdefteri.assistant.AssistantGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Köməkçi (AI) with a scripted model instead of Claude: the tools run in the user's own request (so permissions and
 * the store apply), limits and the chat shape are enforced, usage is counted.
 */
@Import(AssistantTest.FakeModel.class)
@TestPropertySource(properties = "app.assistant.daily-limit=4")
class AssistantTest extends IntegrationTestBase {

    /** Calls the scripted tools in order, keeps their results, answers "ok". */
    @TestConfiguration
    static class FakeModel {
        static final List<Object[]> script = new ArrayList<>();
        static final List<AssistantGateway.ToolResult> results = new ArrayList<>();
        static String lastContext;
        static List<String> lastTools;

        @Bean
        @Primary
        AssistantGateway fakeAssistantGateway() {
            return new AssistantGateway() {
                @Override
                public boolean configured() {
                    return true;
                }

                @Override
                @SuppressWarnings("unchecked")
                public Reply converse(String system, String context, List<ToolSpec> tools, List<Turn> history, ToolExecutor exec) {
                    lastContext = context;
                    lastTools = tools.stream().map(ToolSpec::name).toList();
                    List<String> used = new ArrayList<>();
                    for (Object[] call : script) {
                        used.add((String) call[0]);
                        results.add(exec.run((String) call[0], (Map<String, Object>) call[1]));
                    }
                    return new Reply("ok: " + history.get(history.size() - 1).text(), used, 1200, 80, false);
                }
            };
        }
    }

    @BeforeEach
    void reset() {
        FakeModel.script.clear();
        FakeModel.results.clear();
    }

    private static Map<String, Object> ask(String question) {
        return Map.of("messages", List.of(Map.of("role", "user", "text", question)));
    }

    @Test
    void toolsReadTheStoreWithTheUsersPermissions() throws Exception {
        String owner = register("Köməkçi Bir");
        activateSubscription(owner, "MONTHLY");
        createCustomer(owner, "Əli Məmmədov", "+994501112299");
        Map<String, Object> p = new HashMap<>(Map.of("name", "Kola 1 l", "purchasePrice", "1.00", "salePrice", "1.80", "minStock", "10"));
        p.put("initialStock", "3");
        post(owner, "/api/products", p).andExpect(status().isCreated());

        get(owner, "/api/assistant").andExpect(jsonPath("$.enabled", is(true))).andExpect(jsonPath("$.usedToday", is(0)));

        FakeModel.script.add(new Object[]{"store_summary", Map.of()});
        FakeModel.script.add(new Object[]{"find_customers", Map.of("query", "Əli")});
        FakeModel.script.add(new Object[]{"find_products", Map.of("stock", "LOW")});
        FakeModel.script.add(new Object[]{"sales_report", Map.of()});
        FakeModel.script.add(new Object[]{"no_such_tool", Map.of()});
        post(owner, "/api/assistant/chat", ask("Kimin borcu var?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", is("ok: Kimin borcu var?")))
                .andExpect(jsonPath("$.toolsUsed", hasItems("store_summary", "find_customers")))
                .andExpect(jsonPath("$.usedToday", is(1))).andExpect(jsonPath("$.dailyLimit", is(4)));
        assertFalse(FakeModel.results.get(0).error(), FakeModel.results.get(0).content());
        assertTrue(FakeModel.results.get(1).content().contains("Əli Məmmədov"));
        assertTrue(FakeModel.results.get(2).content().contains("Kola 1 l"), "low stock product found");
        assertFalse(FakeModel.results.get(3).error(), FakeModel.results.get(3).content());
        assertTrue(FakeModel.results.get(4).error());
        assertTrue(FakeModel.lastContext.contains("Köməkçi Bir") && FakeModel.lastContext.contains("shop owner"));
        assertTrue(FakeModel.lastTools.contains("customer_profile"));

        // employees (sellers, cashiers…) do not get the assistant at all: only the shop owner
        String email = uniqueEmail("kassir");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Kassir",
                "permissions", perms("SALES", "REPORTS", "CUSTOMER_DEBTS"))).andExpect(status().isCreated());
        String kassir = login(email, EMPLOYEE_PASSWORD);
        FakeModel.script.clear();
        get(kassir, "/api/assistant").andExpect(status().isForbidden()).andExpect(jsonPath("$.error", is("OWNER_ONLY")));
        post(kassir, "/api/assistant/chat", ask("Borclar?")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("OWNER_ONLY")));
        assertEquals(5, FakeModel.results.size(), "nothing ran for the employee");

        // the counter is per store and day; the limit stops the 5th question
        post(owner, "/api/assistant/chat", ask("2")).andExpect(status().isOk()).andExpect(jsonPath("$.usedToday", is(2)));
        post(owner, "/api/assistant/chat", ask("3")).andExpect(status().isOk()).andExpect(jsonPath("$.usedToday", is(3)));
        post(owner, "/api/assistant/chat", ask("4")).andExpect(status().isOk());
        post(owner, "/api/assistant/chat", ask("5")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", is("ASSISTANT_LIMIT")));
        // another store is not affected
        String other = register("Köməkçi İki");
        activateSubscription(other, "MONTHLY");
        post(other, "/api/assistant/chat", ask("salam")).andExpect(status().isOk()).andExpect(jsonPath("$.usedToday", is(1)));
    }

    @Test
    void chatShapeIsChecked() throws Exception {
        String owner = register("Köməkçi Üç");
        activateSubscription(owner, "MONTHLY");
        post(owner, "/api/assistant/chat", Map.of("messages", List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("EMPTY_QUESTION")));
        post(owner, "/api/assistant/chat", Map.of("messages", List.of(
                Map.of("role", "user", "text", "salam"), Map.of("role", "assistant", "text", "salam")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_CHAT")));
        post(owner, "/api/assistant/chat", ask("x".repeat(2001))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("QUESTION_TOO_LONG")));
        // a longer chat: earlier turns go along, the answer is about the last question
        JsonNode r = body(post(owner, "/api/assistant/chat", Map.of("messages", List.of(
                Map.of("role", "user", "text", "Bu gün nə qədər satdım?"),
                Map.of("role", "assistant", "text", "0,00 ₼"),
                Map.of("role", "user", "text", "Bəs dünən?")))).andExpect(status().isOk()));
        assertEquals("ok: Bəs dünən?", r.get("reply").asText());
        // without a subscription the assistant is closed like every business module
        String expired = register("Köməkçi Dörd");
        post(expired, "/api/assistant/chat", ask("salam")).andExpect(status().isPaymentRequired());
    }
}
