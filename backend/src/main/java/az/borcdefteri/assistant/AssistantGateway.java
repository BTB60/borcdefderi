package az.borcdefteri.assistant;

import java.util.List;
import java.util.Map;

/**
 * The language model behind the assistant. One call runs a whole turn: the model may call tools any number of times
 * (through {@link ToolExecutor}) before it answers. The real implementation is {@link ClaudeGateway}; tests use a
 * scripted fake so the tools, limits and permissions are tested without the network.
 */
public interface AssistantGateway {

    /** One earlier message of the chat, as the user saw it. */
    record Turn(String role, String text) {}

    /** A tool the model may call: JSON-schema properties and the required ones. */
    record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {}

    record ToolResult(String content, boolean error) {}

    @FunctionalInterface
    interface ToolExecutor {
        ToolResult run(String name, Map<String, Object> input);
    }

    /** The answer, the tools used for it and the tokens spent (for the usage table). */
    record Reply(String text, List<String> toolsUsed, long inputTokens, long outputTokens, boolean refused) {}

    boolean configured();

    /**
     * @param stableSystem instructions that are the same for every store (cached by the API)
     * @param storeContext the per-request part: store, user, today's date
     */
    Reply converse(String stableSystem, String storeContext, List<ToolSpec> tools, List<Turn> history, ToolExecutor executor);
}
