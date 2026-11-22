package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without ANTHROPIC_API_KEY the assistant is off: the app says so and nothing is sent anywhere. */
class AssistantOffTest extends IntegrationTestBase {

    @Test
    void withoutAKeyTheAssistantIsOff() throws Exception {
        String owner = register("Köməkçisiz");
        activateSubscription(owner, "MONTHLY");
        get(owner, "/api/assistant").andExpect(status().isOk()).andExpect(jsonPath("$.enabled", is(false)));
        post(owner, "/api/assistant/chat", Map.of("messages", List.of(Map.of("role", "user", "text", "salam"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error", is("ASSISTANT_OFF")));
    }
}
