package com.wish.rd.engine.bugfix;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * RD 机器人 Bug 修复全流程编排引擎。
 *
 * <p>供工单入口调用，从工单创建任务，经过限流队列、RAG 检索、Prompt 构建、执行器端口和任务状态推进。
 */
@Service
public class RdBotFixEngine {

    private final ChatQueueLimiter chatQueueLimiter;
    private final RagBugFixEngine ragBugFixEngine;
    private final RagStreamTaskRegistry taskRegistry;
    private final BugFixPromptBuilder promptBuilder;
    private final BugFixExecutor bugFixExecutor;

    @Autowired
    public RdBotFixEngine(
            ObjectProvider<ChatQueueLimiter> chatQueueLimiterProvider,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            ObjectProvider<BugFixExecutor> bugFixExecutorProvider
    ) {
        this(
                chatQueueLimiterProvider.getIfAvailable(ChatQueueLimiter::passThrough),
                ragBugFixEngine,
                taskRegistry,
                promptBuilder,
                bugFixExecutorProvider.getIfAvailable(BugFixExecutor::mock)
        );
    }

    public RdBotFixEngine(
            ChatQueueLimiter chatQueueLimiter,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            BugFixExecutor bugFixExecutor
    ) {
        this.chatQueueLimiter = chatQueueLimiter == null ? ChatQueueLimiter.passThrough() : chatQueueLimiter;
        this.ragBugFixEngine = ragBugFixEngine;
        this.taskRegistry = taskRegistry == null ? RagStreamTaskRegistry.inMemory() : taskRegistry;
        this.promptBuilder = promptBuilder == null ? BugFixPromptBuilder.defaultBuilder() : promptBuilder;
        this.bugFixExecutor = bugFixExecutor == null ? BugFixExecutor.mock() : bugFixExecutor;
    }

    /**
     * 使用 mock 工单运行一次 Bug 修复流程。
     *
     * @return 修复流程结果
     */
    public RdBotFixResult runBugFix() {
        return runBugFix(new RdBotFixCommand(mockTicket(), List.of(), false, "P2"));
    }

    /**
     * 运行 Bug 修复全流程。
     *
     * @param command 修复流程命令
     * @return 修复流程结果
     */
    public RdBotFixResult runBugFix(RdBotFixCommand command) {
        RdBotFixCommand safeCommand = command == null
                ? new RdBotFixCommand(mockTicket(), List.of(), false, "P2")
                : command;
        RdBugFixTask created = taskRegistry.createBugFixTask(safeCommand.ticket(), safeCommand.priority());
        ChatQueueLimiter.ChatQueueRequest queueRequest = new ChatQueueLimiter.ChatQueueRequest(
                userQuestion(safeCommand.ticket()),
                created.taskId(),
                safeCommand.priority()
        );
        return chatQueueLimiter.enqueue(
                queueRequest,
                () -> runAfterAcquire(created.taskId(), safeCommand),
                () -> reject(created.taskId())
        );
    }

    private RdBotFixResult runAfterAcquire(String taskId, RdBotFixCommand command) {
        taskRegistry.markSearching(taskId, "RAG 检索中");
        BugFixMessage ragMessage = ragBugFixEngine.findBugFixMessgaesForAgent(
                command.ticket(),
                command.logs(),
                command.deepThinking(),
                taskId
        );
        String prompt = promptBuilder.build(ragMessage);
        taskRegistry.markExecuting(taskId, prompt);
        BugFixExecutionResult executionResult = bugFixExecutor.execute(new BugFixExecutionRequest(
                taskId,
                prompt,
                ragMessage
        ));
        RdBugFixTask committed = taskRegistry.markCommitted(
                taskId,
                executionResult.pullRequestUrl(),
                executionResult.resultJson()
        );
        return new RdBotFixResult(taskId, committed.status(), ragMessage, prompt, executionResult, false);
    }

    private RdBotFixResult reject(String taskId) {
        RdBugFixTask rejected = taskRegistry.markRejected(taskId, "系统繁忙，请稍后再试");
        return new RdBotFixResult(
                taskId,
                rejected.status(),
                null,
                "",
                BugFixExecutionResult.empty(taskId),
                true
        );
    }

    private TicketSnapshot mockTicket() {
        return new TicketSnapshot(
                "mock-ticket",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment", "orders.amount"),
                Instant.now()
        );
    }

    private String userQuestion(TicketSnapshot ticket) {
        if (ticket == null) {
            return "";
        }
        if (ticket.description() != null && !ticket.description().isBlank()) {
            return ticket.description().strip();
        }
        if (ticket.title() != null && !ticket.title().isBlank()) {
            return ticket.title().strip();
        }
        return ticket.ticketId() == null ? "" : ticket.ticketId();
    }
}
