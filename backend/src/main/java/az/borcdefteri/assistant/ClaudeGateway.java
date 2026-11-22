package az.borcdefteri.assistant;

import az.borcdefteri.common.ApiException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AssistantGateway} on the Claude API (Messages API with client tools, manual loop). Adaptive thinking is on
 * by default for this model; effort is low because this is a chat over the store's own data. Server-side refusal
 * fallbacks are on. The key is read from ANTHROPIC_API_KEY; without it the assistant is off.
 */
@Component
public class ClaudeGateway implements AssistantGateway {

    private static final Logger log = LoggerFactory.getLogger(ClaudeGateway.class);
    /** Enough for any question about one store; stops a runaway loop. */
    private static final int MAX_STEPS = 8;

    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public ClaudeGateway(@Value("${app.assistant.api-key:}") String apiKey,
                         @Value("${app.assistant.model:claude-opus-5-5}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            }
        }
        return client;
    }

    @Override
    public Reply converse(String stableSystem, String storeContext, List<ToolSpec> tools, List<Turn> history,
                          ToolExecutor executor) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                // the stable instructions are cached; the store / date part follows the breakpoint
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(stableSystem)
                                .cacheControl(CacheControlEphemeral.builder().build()).build(),
                        TextBlockParam.builder().text(storeContext).build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                // when a safety classifier declines, the API retries on a suitable model instead of stopping
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (ToolSpec t : tools) params.addTool(tool(t));
        for (Turn t : history) {
            params.addMessage("assistant".equals(t.role()) ? MessageParam.ofAssistant(t.text()) : MessageParam.ofUser(t.text()));
        }

        List<String> used = new ArrayList<>();
        long in = 0, out = 0;
        try {
            for (int step = 0; step < MAX_STEPS; step++) {
                Message msg = client().messages().create(params.build());
                in += msg.usage().inputTokens();
                out += msg.usage().outputTokens();
                StopReason stop = msg.stopReason().orElse(StopReason.END_TURN);
                if (StopReason.REFUSAL.equals(stop)) {
                    return new Reply("Bu suala cavab verə bilmirəm.", used, in, out, true);
                }
                // keep the whole assistant turn (thinking + tool calls) in the conversation
                params.addMessage(msg);
                List<ToolUseBlock> calls = msg.content().stream().flatMap(b -> b.toolUse().stream()).toList();
                if (!StopReason.TOOL_USE.equals(stop) || calls.isEmpty()) {
                    return new Reply(text(msg), used, in, out, false);
                }
                // every tool result goes back in ONE user message
                List<ContentBlockParam> results = new ArrayList<>();
                for (ToolUseBlock call : calls) {
                    used.add(call.name());
                    ToolResult r = run(executor, call);
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(call.id()).content(r.content()).isError(r.error()).build()));
                }
                params.addMessage(MessageParam.ofUserOfContentBlockParams(results));
            }
            return new Reply("Sual çox geniş oldu, daha konkret soruşun.", used, in, out, false);
        } catch (RateLimitException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_BUSY", "Köməkçi hazırda məşğuldur, bir az sonra yenidən yazın");
        } catch (AnthropicServiceException e) {
            log.warn("Claude API error {}: {}", e.statusCode(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSISTANT_ERROR", "Köməkçi cavab verə bilmədi, bir az sonra yenidən cəhd edin");
        }
    }

    private ToolResult run(ToolExecutor executor, ToolUseBlock call) {
        Map<String, Object> input;
        try {
            Map<String, Object> parsed = call._input().convert(new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            input = parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return new ToolResult("Alətin parametrləri oxunmadı", true);
        }
        return executor.run(call.name(), input);
    }

    private static String text(Message msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.content()) {
            b.text().ifPresent(t -> {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(t.text());
            });
        }
        return sb.isEmpty() ? "Cavab boş gəldi, sualı başqa cür yazın." : sb.toString().trim();
    }

    private static Tool tool(ToolSpec t) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        t.properties().forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(t.name())
                .description(t.description())
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(t.required()).build())
                .build();
    }
}
