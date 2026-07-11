package com.wish.rd.engine.bugfix;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlan;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanGenerationCommand;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanGenerationResult;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanGeneratorPort;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanStatus;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanValidationResult;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanValidator;
import com.wish.rd.engine.bugfix.observability.BugFixStageRecorder;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.model.AgentWorkflowAlert;
import com.wish.rd.engine.agent.model.AgentWorkflowAlertType;
import com.wish.rd.engine.rag.model.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.wish.rd.engine.bugfix.model.BugFixExecutionRequest;
import com.wish.rd.engine.bugfix.model.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.model.RdBotFixCommand;
import com.wish.rd.engine.bugfix.model.RdBotFixResult;

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
    private final AcceptancePlanGeneratorPort acceptancePlanGenerator;
    private final AcceptancePlanValidator acceptancePlanValidator;
    private final BugFixStageRecorder stageRecorder;
    private final AgentWorkflowAlertSinkPort alertSink;
    private final Function<String, List<String>> projectKnowledgeBaseResolver;

    @Autowired
    public RdBotFixEngine(
            ObjectProvider<ChatQueueLimiter> chatQueueLimiterProvider,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            ObjectProvider<BugFixExecutor> bugFixExecutorProvider,
            ObjectProvider<AcceptancePlanGeneratorPort> acceptancePlanGeneratorProvider,
            ObjectProvider<BugFixStageRecorder> stageRecorderProvider,
            ObjectProvider<AgentWorkflowAlertSinkPort> alertSinkProvider,
            ObjectProvider<RdProjectService> projectServiceProvider
    ) {
        this(
                chatQueueLimiterProvider.getIfAvailable(ChatQueueLimiter::passThrough),
                ragBugFixEngine,
                taskRegistry,
                promptBuilder,
                bugFixExecutorProvider.getIfAvailable(BugFixExecutor::mock),
                acceptancePlanGeneratorProvider.getIfAvailable(AcceptancePlanGeneratorPort::disabled),
                stageRecorderProvider.getIfAvailable(BugFixStageRecorder::inMemory),
                alertSinkProvider.getIfAvailable(AgentWorkflowAlertSinkPort::noop),
                projectId -> resolveProjectKnowledgeBaseIds(projectServiceProvider.getIfAvailable(), projectId)
        );
    }

    public RdBotFixEngine(
            ChatQueueLimiter chatQueueLimiter,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            BugFixExecutor bugFixExecutor
    ) {
        this(
                chatQueueLimiter,
                ragBugFixEngine,
                taskRegistry,
                promptBuilder,
                bugFixExecutor,
                AcceptancePlanGeneratorPort.disabled(),
                BugFixStageRecorder.inMemory(),
                AgentWorkflowAlertSinkPort.noop(),
                projectId -> List.of()
        );
    }

    public RdBotFixEngine(
            ChatQueueLimiter chatQueueLimiter,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            BugFixExecutor bugFixExecutor,
            AcceptancePlanGeneratorPort acceptancePlanGenerator
    ) {
        this(
                chatQueueLimiter,
                ragBugFixEngine,
                taskRegistry,
                promptBuilder,
                bugFixExecutor,
                acceptancePlanGenerator,
                BugFixStageRecorder.inMemory(),
                AgentWorkflowAlertSinkPort.noop(),
                projectId -> List.of()
        );
    }

    public RdBotFixEngine(
            ChatQueueLimiter chatQueueLimiter,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            BugFixExecutor bugFixExecutor,
            AcceptancePlanGeneratorPort acceptancePlanGenerator,
            BugFixStageRecorder stageRecorder
    ) {
        this(
                chatQueueLimiter, ragBugFixEngine, taskRegistry, promptBuilder, bugFixExecutor,
                acceptancePlanGenerator, stageRecorder, AgentWorkflowAlertSinkPort.noop(), projectId -> List.of()
        );
    }

    public RdBotFixEngine(
            ChatQueueLimiter chatQueueLimiter,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            BugFixExecutor bugFixExecutor,
            AcceptancePlanGeneratorPort acceptancePlanGenerator,
            BugFixStageRecorder stageRecorder,
            AgentWorkflowAlertSinkPort alertSink
    ) {
        this(
                chatQueueLimiter,
                ragBugFixEngine,
                taskRegistry,
                promptBuilder,
                bugFixExecutor,
                acceptancePlanGenerator,
                stageRecorder,
                alertSink,
                projectId -> List.of()
        );
    }

    private RdBotFixEngine(
            ChatQueueLimiter chatQueueLimiter,
            RagBugFixEngine ragBugFixEngine,
            RagStreamTaskRegistry taskRegistry,
            BugFixPromptBuilder promptBuilder,
            BugFixExecutor bugFixExecutor,
            AcceptancePlanGeneratorPort acceptancePlanGenerator,
            BugFixStageRecorder stageRecorder,
            AgentWorkflowAlertSinkPort alertSink,
            Function<String, List<String>> projectKnowledgeBaseResolver
    ) {
        this.chatQueueLimiter = chatQueueLimiter == null ? ChatQueueLimiter.passThrough() : chatQueueLimiter;
        this.ragBugFixEngine = ragBugFixEngine;
        this.taskRegistry = taskRegistry == null ? RagStreamTaskRegistry.inMemory() : taskRegistry;
        this.promptBuilder = promptBuilder == null ? BugFixPromptBuilder.defaultBuilder() : promptBuilder;
        this.bugFixExecutor = bugFixExecutor == null ? BugFixExecutor.mock() : bugFixExecutor;
        this.acceptancePlanGenerator = acceptancePlanGenerator == null
                ? AcceptancePlanGeneratorPort.disabled()
                : acceptancePlanGenerator;
        this.acceptancePlanValidator = AcceptancePlanValidator.defaultValidator();
        this.stageRecorder = stageRecorder == null ? BugFixStageRecorder.inMemory() : stageRecorder;
        this.alertSink = alertSink == null ? AgentWorkflowAlertSinkPort.noop() : alertSink;
        this.projectKnowledgeBaseResolver = projectKnowledgeBaseResolver == null ? projectId -> List.of() : projectKnowledgeBaseResolver;
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
        RdBugFixTask created = taskRegistry.createOrReuseBugFixTask(safeCommand.ticket(), safeCommand.priority());
        if (shouldReturnExisting(created)) {
            return toExistingResult(created);
        }
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

    private boolean shouldReturnExisting(RdBugFixTask task) {
        return task.status() == RdTaskStatus.SEARCHING
                || task.status() == RdTaskStatus.EXECUTING
                || task.status() == RdTaskStatus.COMMITTED
                || task.status() == RdTaskStatus.MERGED;
    }

    private RdBotFixResult toExistingResult(RdBugFixTask task) {
        return new RdBotFixResult(
                task.taskId(),
                task.status(),
                null,
                task.promptSnapshot(),
                new BugFixExecutionResult(
                        task.taskId(),
                        task.ticketTitle(),
                        "",
                        task.pullRequestUrl(),
                        task.executionResultJson()
                ),
                false
        );
    }

    private RdBotFixResult runAfterAcquire(String taskId, RdBotFixCommand command) {
        AgentStageRun evidenceStage = stageRecorder.start(
                taskId,
                AgentRole.BUG_EVIDENCE_COLLECTOR,
                evidenceInput(command)
        );
        stageRecorder.succeed(evidenceStage, "ticket and runtime evidence collected", "", "[]");

        taskRegistry.markSearching(taskId, "RAG 检索中");
        AgentStageRun ragStage = stageRecorder.start(taskId, AgentRole.BUG_RAG_RETRIEVER, evidenceInput(command));
        BugFixMessage ragMessage;
        try {
            ragMessage = ragBugFixEngine.findBugFixMessgaesForAgent(
                    command.ticket(),
                    command.logs(),
                    command.deepThinking(),
                    taskId,
                    projectKnowledgeBaseIds(taskId)
            );
            stageRecorder.succeed(ragStage, ragMessage.answer(), "", "[]");
        } catch (RuntimeException exception) {
            stageRecorder.failRetryable(ragStage, "RAG_RETRIEVAL_FAILED", exception.getMessage());
            taskRegistry.markFailedRetryable(taskId, exception.getMessage());
            publishTaskAlert(taskId, ragStage.stageRunId(), AgentWorkflowAlertType.TASK_FAILED,
                    exception.getMessage(), "检查 RAG 与知识库状态");
            throw exception;
        }

        AgentStageRun acceptanceStage = stageRecorder.start(
                taskId,
                AgentRole.BUG_ACCEPTANCE_PLANNER,
                ragMessage.answer()
        );
        AcceptancePlan acceptancePlan;
        try {
            acceptancePlan = generateAcceptancePlan(taskId, ragMessage);
            stageRecorder.succeed(
                    acceptanceStage,
                    acceptancePlan.toJson(),
                    acceptancePlan.source(),
                    "[]"
            );
        } catch (RuntimeException exception) {
            stageRecorder.failRetryable(acceptanceStage, "ACCEPTANCE_PLAN_FAILED", exception.getMessage());
            taskRegistry.markFailedRetryable(taskId, exception.getMessage());
            publishTaskAlert(taskId, acceptanceStage.stageRunId(), AgentWorkflowAlertType.TASK_FAILED,
                    exception.getMessage(), "检查验收计划生成结果");
            throw exception;
        }
        String prompt = promptBuilder.build(ragMessage, acceptancePlan);
        taskRegistry.markExecuting(taskId, prompt);
        AgentStageRun codingStage = stageRecorder.start(taskId, AgentRole.BUG_CODING_AGENT, prompt);
        BugFixExecutionResult executionResult;
        try {
            executionResult = bugFixExecutor.execute(new BugFixExecutionRequest(
                    taskId,
                    prompt,
                    ragMessage,
                    acceptancePlan
            ));
        } catch (RuntimeException exception) {
            stageRecorder.failRetryable(codingStage, "BUG_EXECUTOR_EXCEPTION", exception.getMessage());
            taskRegistry.markFailedRetryable(taskId, exception.getMessage());
            publishTaskAlert(taskId, codingStage.stageRunId(), AgentWorkflowAlertType.TASK_FAILED,
                    exception.getMessage(), "检查执行容器与 provider 日志");
            throw exception;
        }
        executionResult = normalizeExecutionResult(taskId, executionResult);
        ExecutionOutcome outcome = classifyExecutionResult(executionResult);
        if (!outcome.success()) {
            stageRecorder.failNeedsHuman(codingStage, "BUG_EXECUTION_REJECTED", outcome.failureReason());
            RdBugFixTask rejected = taskRegistry.markRejected(
                    taskId,
                    outcome.failureReason(),
                    executionResult.resultJson()
            );
            publishTaskAlert(taskId, codingStage.stageRunId(), AgentWorkflowAlertType.TASK_FAILED,
                    outcome.failureReason(), "人工复核执行结果");
            return new RdBotFixResult(
                    taskId,
                    rejected.status(),
                    ragMessage,
                    prompt,
                    executionResult,
                    acceptancePlan,
                    true
            );
        }
        stageRecorder.succeed(
                codingStage,
                executionResult.resultJson(),
                jsonTextField(executionResult.resultJson(), "providerName"),
                jsonArrayField(executionResult.resultJson(), "providerAttempts")
        );
        RdBugFixTask committed = taskRegistry.markCommitted(
                taskId,
                executionResult.pullRequestUrl(),
                executionResult.resultJson()
        );
        publishTaskAlert(taskId, codingStage.stageRunId(), AgentWorkflowAlertType.TASK_COMPLETED,
                "Bug 修复已提交", "打开任务详情并检查 PR");
        return new RdBotFixResult(taskId, committed.status(), ragMessage, prompt, executionResult, acceptancePlan, false);
    }

    private void publishTaskAlert(
            String taskId,
            String stageRunId,
            AgentWorkflowAlertType type,
            String message,
            String nextAction
    ) {
        alertSink.publish(new AgentWorkflowAlert(
                taskId, stageRunId, type, message,
                Map.of("nextAction", nextAction, "status", type.name()),
                System.currentTimeMillis()
        ));
    }

    private List<String> projectKnowledgeBaseIds(String taskId) {
        return projectKnowledgeBaseResolver.apply(taskRegistry.get(taskId).projectId());
    }

    private static List<String> resolveProjectKnowledgeBaseIds(RdProjectService projectService, String projectId) {
        if (projectService == null || projectId == null || projectId.isBlank()) {
            return List.of();
        }
        try {
            String knowledgeBaseId = projectService.get(projectId).knowledgeBaseId();
            return knowledgeBaseId == null || knowledgeBaseId.isBlank() ? List.of() : List.of(knowledgeBaseId);
        } catch (NoSuchElementException ignored) {
            return List.of();
        }
    }

    private String evidenceInput(RdBotFixCommand command) {
        return "ticketId=" + command.ticket().ticketId()
                + "\ntitle=" + command.ticket().title()
                + "\ndescription=" + command.ticket().description()
                + "\nlogs=" + String.join("\n", command.logs());
    }

    private String jsonArrayField(String json, String fieldName) {
        if (!hasText(json) || !hasText(fieldName)) {
            return "[]";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode value = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(json)
                    .path(fieldName);
            return value.isArray() ? value.toString() : "[]";
        } catch (Exception exception) {
            return "[]";
        }
    }

    private BugFixExecutionResult normalizeExecutionResult(String taskId, BugFixExecutionResult executionResult) {
        if (executionResult != null) {
            return executionResult;
        }
        return new BugFixExecutionResult(
                taskId,
                "修复执行器未返回结果",
                "",
                "",
                "{\"status\":\"FAILED\",\"errorMessage\":\"bug fix executor returned null\"}"
        );
    }

    private ExecutionOutcome classifyExecutionResult(BugFixExecutionResult executionResult) {
        String status = jsonTextField(executionResult.resultJson(), "status");
        String normalizedStatus = status.strip().toUpperCase(Locale.ROOT);
        if ("SUCCESS".equals(normalizedStatus)) {
            if (hasText(executionResult.pullRequestUrl())) {
                return ExecutionOutcome.succeeded();
            }
            return ExecutionOutcome.failed("修复执行失败: 执行器返回 SUCCESS 但未生成 PR 链接");
        }
        if (normalizedStatus.isBlank() && hasText(executionResult.pullRequestUrl())) {
            return ExecutionOutcome.succeeded();
        }
        return ExecutionOutcome.failed(executionFailureReason(executionResult, normalizedStatus));
    }

    private String executionFailureReason(BugFixExecutionResult executionResult, String normalizedStatus) {
        String status = normalizedStatus.isBlank() ? "UNKNOWN" : normalizedStatus;
        String reason = firstNonBlank(
                jsonTextField(executionResult.resultJson(), "errorMessage"),
                jsonTextField(executionResult.resultJson(), "blockingReason"),
                jsonTextField(executionResult.resultJson(), "summary"),
                executionResult.solution(),
                executionResult.bugDescription()
        );
        if (reason.isBlank()) {
            return "修复执行失败: status=" + status;
        }
        return "修复执行失败: status=%s, reason=%s".formatted(status, reason);
    }

    private String jsonTextField(String json, String fieldName) {
        if (!hasText(json) || !hasText(fieldName)) {
            return "";
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName)
                + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return unescapeJsonText(matcher.group(1));
    }

    private String unescapeJsonText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (index + 4 < value.length()) {
                        String hex = value.substring(index + 1, index + 5);
                        try {
                            result.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException exception) {
                            result.append("\\u").append(hex);
                        }
                        index += 4;
                    } else {
                        result.append("\\u");
                    }
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.strip();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ExecutionOutcome(boolean success, String failureReason) {

        private static ExecutionOutcome succeeded() {
            return new ExecutionOutcome(true, "");
        }

        private static ExecutionOutcome failed(String failureReason) {
            return new ExecutionOutcome(false, failureReason == null ? "" : failureReason);
        }
    }

    private AcceptancePlan generateAcceptancePlan(String taskId, BugFixMessage ragMessage) {
        String ticketId = ragMessage == null ? "" : ragMessage.ticketId();
        AcceptancePlanGenerationResult result = acceptancePlanGenerator.generate(
                new AcceptancePlanGenerationCommand(taskId, ragMessage)
        );
        AcceptancePlan plan = normalizeGeneratedPlan(taskId, ticketId, result == null ? null : result.plan());
        AcceptancePlanValidationResult validation = acceptancePlanValidator.validate(
                plan,
                taskId,
                ticketId
        );
        if (validation.valid()) {
            return plan;
        }
        return new AcceptancePlan(
                taskId,
                ticketId,
                AcceptancePlanStatus.INVALID,
                plan == null ? "" : plan.source(),
                String.join("; ", validation.errors()),
                List.of(),
                List.of(),
                ragMessage == null ? List.of() : ragMessage.evidenceChunkIds()
        );
    }

    private AcceptancePlan normalizeGeneratedPlan(String taskId, String ticketId, AcceptancePlan plan) {
        if (plan == null) {
            return AcceptancePlan.disabled(taskId, ticketId, "acceptance planner returned no plan");
        }
        if (plan.status() != AcceptancePlanStatus.READY
                && plan.taskId().isBlank()
                && plan.ticketId().isBlank()) {
            return new AcceptancePlan(
                    taskId,
                    ticketId,
                    plan.status(),
                    plan.source(),
                    plan.reason(),
                    plan.steps(),
                    plan.assertions(),
                    plan.evidenceChunkIds()
            );
        }
        return plan;
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
