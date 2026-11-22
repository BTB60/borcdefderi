package az.borcdefteri.assistant;

import az.borcdefteri.assistant.AssistantGateway.Turn;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Köməkçi (AI): status and one chat turn. The client keeps the conversation and sends its recent turns. */
@RestController
public class AssistantController {

    public record ChatRequest(@NotNull List<Turn> messages) {}

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @GetMapping("/api/assistant")
    public AssistantService.Status status() {
        return assistant.status();
    }

    @PostMapping("/api/assistant/chat")
    public AssistantService.ChatResponse chat(@RequestBody ChatRequest req) {
        return assistant.chat(req.messages());
    }
}
