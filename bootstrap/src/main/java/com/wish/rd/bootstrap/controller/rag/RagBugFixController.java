package com.wish.rd.bootstrap.controller.rag;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.BugFixStopResult;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.rag.runtime.RagStreamTask;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bug 修复 RAG 控制器。
 */
@RestController
public class RagBugFixController {

    private final RagBugFixEngine bugFixEngine;

    public RagBugFixController(RagBugFixEngine bugFixEngine) {
        this.bugFixEngine = bugFixEngine;
    }

    @PostMapping("/rag/bugfix/messages")
    public BugFixMessage findBugFixMessage(@RequestBody BugFixMessageRequest request) {
        return bugFixEngine.findBugFixMessgaesForAgent(
                request.toTicket(),
                request.logs(),
                request.deepThinking()
        );
    }

    @GetMapping(value = "/rag/v3/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> chat(
            @RequestParam("question") String question,
            @RequestParam(value = "deepThinking", defaultValue = "false") boolean deepThinking
    ) {
        BugFixMessage message = bugFixEngine.findBugFixMessgaesForAgent(
                ticketFrom(question),
                List.of(),
                deepThinking
        );
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .body(toSse(message));
    }

    @PostMapping("/rag/v3/stop")
    public BugFixStopResult stop(@RequestParam("taskId") String taskId) {
        return bugFixEngine.stop(taskId);
    }

    @GetMapping("/rag/v3/tasks/{taskId}")
    public RagStreamTask task(@PathVariable("taskId") String taskId) {
        return bugFixEngine.task(taskId);
    }

    private TicketSnapshot ticketFrom(String question) {
        String ticketId = "ticket-" + UUID.randomUUID();
        String safeQuestion = question == null ? "" : question;
        return new TicketSnapshot(ticketId, safeQuestion, safeQuestion, List.of(), Instant.now());
    }

    private String toSse(BugFixMessage message) {
        if (message.rejected()) {
            return event("meta", """
                    {"taskId":"%s"}
                    """.formatted(json(message.taskId())).strip())
                    + event("reject", message.answer())
                    + event("finish", """
                    {"title":"%s"}
                    """.formatted(json(titleFrom(message.ticketDescription()))).strip())
                    + event("done", "{\"status\":\"DONE\"}");
        }
        return event("meta", """
                {"taskId":"%s","intentSystemId":"%s","intentName":"%s","promptSections":"%s","agentUserMessage":"%s"}
                """.formatted(
                        json(message.taskId()),
                        json(message.primaryIntentSystemId()),
                        json(message.primaryIntentName()),
                        json(String.join(",", message.promptSections())),
                        json(message.agentUserMessage())
                ).strip())
                + event("delta", message.answer())
                + event("done", "{\"status\":\"DONE\"}");
    }

    private String event(String event, String data) {
        return "event: " + event + "\n" + "data: " + data + "\n\n";
    }

    private String json(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String titleFrom(String question) {
        String raw = question == null ? "" : question.strip();
        if (raw.isBlank()) {
            return "";
        }
        return raw.length() <= 30 ? raw : raw.substring(0, 30);
    }

    public record BugFixMessageRequest(
            String ticketId,
            String title,
            String description,
            List<String> labels,
            List<String> logs,
            boolean deepThinking
    ) {

        public BugFixMessageRequest {
            labels = labels == null ? List.of() : List.copyOf(labels);
            logs = logs == null ? List.of() : List.copyOf(logs);
        }

        TicketSnapshot toTicket() {
            String actualTicketId = ticketId == null || ticketId.isBlank()
                    ? "ticket-" + UUID.randomUUID()
                    : ticketId;
            return new TicketSnapshot(actualTicketId, title, description, labels, Instant.now());
        }
    }
}
