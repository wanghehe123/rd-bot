package com.wish.rd.bootstrap.controller.rag;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.engine.rag.model.BugFixMessage;
import com.wish.rd.engine.rag.model.BugFixStopResult;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/rag/v3/stop")
    public BugFixStopResult stop(@RequestParam("taskId") String taskId) {
        return bugFixEngine.stop(taskId);
    }

    @GetMapping("/rag/v3/tasks/{taskId}")
    public RdBugFixTask task(@PathVariable("taskId") String taskId) {
        return bugFixEngine.task(taskId);
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
