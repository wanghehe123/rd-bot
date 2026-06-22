package com.wish.rd.bootstrap.controller.testchannel;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.bugfix.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.RdBotFixCommand;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.RdBotFixResult;
import com.wish.rd.engine.rag.BugFixMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bug 修复执行测试通道（仅 {@code /test/...} 前缀）。
 *
 * <p>该入口直接调用 {@link RdBotFixEngine}，用于本地端到端验证 RAG + Prompt +
 * 执行器端口，不改变正式工单入口只准备 RAG 上下文的职责边界。
 */
@RestController
public class BugFixExecutionTestChannelController {

    private final RdBotFixEngine fixEngine;

    public BugFixExecutionTestChannelController(RdBotFixEngine fixEngine) {
        this.fixEngine = fixEngine;
    }

    /**
     * 运行一次完整 Bug 修复编排。
     *
     * @param request 修复测试请求
     * @return 修复执行结果视图
     */
    @PostMapping("/test/repair/bugfix/run")
    public ResponseEntity<BugFixRunResponse> runBugFix(@RequestBody(required = false) BugFixRunRequest request) {
        RdBotFixResult result = fixEngine.runBugFix(toCommand(request));
        return ResponseEntity.ok(toResponse(result));
    }

    private RdBotFixCommand toCommand(BugFixRunRequest request) {
        BugFixRunRequest safe = request == null ? BugFixRunRequest.defaults() : request;
        return new RdBotFixCommand(
                new TicketSnapshot(
                        safe.ticketId(),
                        safe.title(),
                        safe.description(),
                        safe.labels(),
                        Instant.now()
                ),
                safe.logs(),
                safe.deepThinking(),
                safe.priority()
        );
    }

    private BugFixRunResponse toResponse(RdBotFixResult result) {
        BugFixMessage ragMessage = result.ragMessage();
        BugFixExecutionResult executionResult = result.executionResult();
        return new BugFixRunResponse(
                result.taskId(),
                result.status().name(),
                result.rejected(),
                ragMessage == null ? RagView.empty() : new RagView(
                        ragMessage.ticketId(),
                        ragMessage.ticketTitle(),
                        ragMessage.contextSummary(),
                        ragMessage.searchChannels(),
                        ragMessage.evidenceChunkIds(),
                        ragMessage.retrievedChunks().size()
                ),
                new ExecutionView(
                        executionResult.bugDescription(),
                        executionResult.solution(),
                        executionResult.pullRequestUrl(),
                        executionResult.resultJson()
                )
        );
    }

    /** Bug 修复测试请求。 */
    public record BugFixRunRequest(
            String ticketId,
            String title,
            String description,
            List<String> labels,
            List<String> logs,
            boolean deepThinking,
            String priority
    ) {

        public BugFixRunRequest {
            ticketId = ticketId == null || ticketId.isBlank() ? "ticket-" + UUID.randomUUID() : ticketId.strip();
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            labels = labels == null ? List.of() : List.copyOf(labels);
            logs = logs == null ? List.of() : List.copyOf(logs);
            priority = priority == null || priority.isBlank() ? "P2" : priority.strip().toUpperCase();
        }

        static BugFixRunRequest defaults() {
            return new BugFixRunRequest(
                    "mock-ticket",
                    "支付系统下单接口 500",
                    "金额为空时 OrderService.create 写入订单失败",
                    List.of("payment", "orders.amount"),
                    List.of("ERROR orders.amount is null at OrderService.create"),
                    false,
                    "P2"
            );
        }
    }

    /** RAG 上下文视图。 */
    public record RagView(
            String ticketId,
            String ticketTitle,
            String contextSummary,
            List<String> searchChannels,
            List<String> evidenceChunkIds,
            int retrievedChunkCount
    ) {

        public RagView {
            ticketId = ticketId == null ? "" : ticketId;
            ticketTitle = ticketTitle == null ? "" : ticketTitle;
            contextSummary = contextSummary == null ? "" : contextSummary;
            searchChannels = searchChannels == null ? List.of() : List.copyOf(searchChannels);
            evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
            retrievedChunkCount = Math.max(0, retrievedChunkCount);
        }

        static RagView empty() {
            return new RagView("", "", "", List.of(), List.of(), 0);
        }
    }

    /** 执行器结果视图。 */
    public record ExecutionView(
            String bugDescription,
            String solution,
            String pullRequestUrl,
            String resultJson
    ) {

        public ExecutionView {
            bugDescription = bugDescription == null ? "" : bugDescription;
            solution = solution == null ? "" : solution;
            pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl;
            resultJson = resultJson == null ? "" : resultJson;
        }
    }

    /** Bug 修复执行响应。 */
    public record BugFixRunResponse(
            String taskId,
            String status,
            boolean rejected,
            RagView rag,
            ExecutionView execution
    ) {
    }
}
