package com.wish.rd.engine;

import com.wish.rd.adapter.TicketMessage;
import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.adapter.TicketUpdateCommand;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.TicketUpdateResult;
import com.wish.rd.engine.bugfix.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.BugFixExecutor;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.engine.ticket.InMemoryRepairRecordRepository;
import com.wish.rd.engine.ticket.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.RepairRecordRepository;
import com.wish.rd.engine.ticket.RepairRecordStatus;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import com.wish.rd.engine.ticket.TicketFieldMapping;
import com.wish.rd.engine.ticket.TicketRepairEngine;
import com.wish.rd.engine.ticket.TicketRepairExecutionConsumer;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证正式修复队列消费编排：工单先准备 RAG 上下文，再按配置触发 Docker/PR 执行入口。
 */
class TicketRepairExecutionConsumerTest {

    @Test
    void readyTicketShouldTriggerBugFixExecutionWhenAutoExecuteEnabled() {
        ReadyProvider provider = new ReadyProvider();
        RepairRecordRepository repository = InMemoryRepairRecordRepository.inMemory();
        TicketRepairEngine ticketRepairEngine = TicketRepairEngine.forTesting(
                provider,
                null,
                repository,
                ragEngine(),
                TicketFieldMapping.defaults(),
                false
        );
        RecordingBugFixExecutor executor = new RecordingBugFixExecutor();
        RdBotFixEngine fixEngine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(),
                taskRegistry(),
                com.wish.rd.engine.bugfix.BugFixPromptBuilder.defaultBuilder(),
                executor
        );
        TicketRepairExecutionConsumer consumer = new TicketRepairExecutionConsumer(
                ticketRepairEngine,
                provider,
                fixEngine,
                TicketFieldMapping.defaults(),
                true,
                null,
                repository,
                false
        );

        boolean success = consumer.handle(message("FS-READY"));

        assertTrue(success);
        assertEquals(1, executor.requests.size());
        assertEquals("FS-READY", executor.requests.getFirst().ragMessage().ticketId());
        assertTrue(executor.requests.getFirst().ragMessage().contextSummary().contains("OrderService.create"));
        assertEquals(RepairRecordStatus.COMMITTED, repository.findByTicketId("FS-READY").orElseThrow().status());
        assertTrue(repository.findByTicketId("FS-READY").orElseThrow().ragSummary()
                .contains("https://github.example.local/acme/order/pull/1"));
    }

    @Test
    void readyTicketShouldWriteBackExecutionResultWhenEnabled() {
        ReadyProvider provider = new ReadyProvider();
        RepairRecordRepository repository = InMemoryRepairRecordRepository.inMemory();
        TicketRepairEngine ticketRepairEngine = TicketRepairEngine.forTesting(
                provider,
                null,
                repository,
                ragEngine(),
                TicketFieldMapping.defaults(),
                false
        );
        RecordingBugFixExecutor executor = new RecordingBugFixExecutor();
        RecordingUpdater updater = new RecordingUpdater();
        RdBotFixEngine fixEngine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(),
                taskRegistry(),
                com.wish.rd.engine.bugfix.BugFixPromptBuilder.defaultBuilder(),
                executor
        );
        TicketRepairExecutionConsumer consumer = new TicketRepairExecutionConsumer(
                ticketRepairEngine,
                provider,
                fixEngine,
                TicketFieldMapping.defaults(),
                true,
                updater,
                true
        );

        boolean success = consumer.handle(message("FS-READY"));

        assertTrue(success);
        assertEquals(1, updater.replies.size());
        String content = updater.replies.getFirst().content();
        assertTrue(content.contains("内部工单：FS-READY"));
        assertTrue(content.contains("RAG 状态：CONTEXT_READY"));
        assertTrue(content.contains("执行状态：COMMITTED"));
        assertTrue(content.contains("PR：https://github.example.local/acme/order/pull/1"));
    }

    @Test
    void rejectedExecutionShouldWriteBackFailureResultClearly() {
        ReadyProvider provider = new ReadyProvider();
        RepairRecordRepository repository = InMemoryRepairRecordRepository.inMemory();
        TicketRepairEngine ticketRepairEngine = TicketRepairEngine.forTesting(
                provider,
                null,
                repository,
                ragEngine(),
                TicketFieldMapping.defaults(),
                false
        );
        FailingBugFixExecutor executor = new FailingBugFixExecutor();
        RecordingUpdater updater = new RecordingUpdater();
        RdBotFixEngine fixEngine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(),
                taskRegistry(),
                com.wish.rd.engine.bugfix.BugFixPromptBuilder.defaultBuilder(),
                executor
        );
        TicketRepairExecutionConsumer consumer = new TicketRepairExecutionConsumer(
                ticketRepairEngine,
                provider,
                fixEngine,
                TicketFieldMapping.defaults(),
                true,
                updater,
                true
        );

        boolean success = consumer.handle(message("FS-READY"));

        assertTrue(!success);
        assertEquals(1, updater.replies.size());
        String content = updater.replies.getFirst().content();
        assertTrue(content.contains("RD-Bot 自动修复失败"));
        assertTrue(content.contains("执行状态：REJECTED"));
        assertTrue(content.contains("异常原因：git command failed exitCode=128"));
        assertTrue(content.contains("处理建议：请人工检查执行环境后重试"));
        assertTrue(!content.contains("RD-Bot 自动修复已完成"));
        assertTrue(content.contains("PR：未生成"));
    }

    @Test
    void mqRetryShouldReuseExistingBugFixTaskInsteadOfCreatingAnotherTask() {
        ReadyProvider provider = new ReadyProvider();
        RepairRecordRepository repository = InMemoryRepairRecordRepository.inMemory();
        TicketRepairEngine ticketRepairEngine = TicketRepairEngine.forTesting(
                provider,
                null,
                repository,
                ragEngine(),
                TicketFieldMapping.defaults(),
                false
        );
        FailThenSucceedBugFixExecutor executor = new FailThenSucceedBugFixExecutor();
        RagStreamTaskRegistry registry = taskRegistry();
        RdBotFixEngine fixEngine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                com.wish.rd.engine.bugfix.BugFixPromptBuilder.defaultBuilder(),
                executor
        );
        TicketRepairExecutionConsumer consumer = new TicketRepairExecutionConsumer(
                ticketRepairEngine,
                provider,
                fixEngine,
                TicketFieldMapping.defaults(),
                true,
                null,
                repository,
                false
        );

        boolean first = consumer.handle(message("FS-READY", 1));
        boolean retry = consumer.handle(message("FS-READY", 2));

        assertTrue(!first);
        assertTrue(retry);
        assertEquals(1, registry.listBugFixTasks().size());
        assertEquals(
                executor.requests.getFirst().taskId(),
                executor.requests.get(1).taskId(),
                "RocketMQ retry must continue the same RD task"
        );
        assertEquals(RepairRecordStatus.COMMITTED, repository.findByTicketId("FS-READY").orElseThrow().status());
    }

    @Test
    void readyTicketShouldOnlyPrepareRagWhenAutoExecuteDisabled() {
        ReadyProvider provider = new ReadyProvider();
        TicketRepairEngine ticketRepairEngine = TicketRepairEngine.forTesting(
                provider,
                null,
                InMemoryRepairRecordRepository.inMemory(),
                ragEngine(),
                TicketFieldMapping.defaults(),
                false
        );
        RecordingBugFixExecutor executor = new RecordingBugFixExecutor();
        RdBotFixEngine fixEngine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(),
                taskRegistry(),
                com.wish.rd.engine.bugfix.BugFixPromptBuilder.defaultBuilder(),
                executor
        );
        TicketRepairExecutionConsumer consumer = new TicketRepairExecutionConsumer(
                ticketRepairEngine,
                provider,
                fixEngine,
                TicketFieldMapping.defaults(),
                false
        );

        boolean success = consumer.handle(message("FS-READY"));

        assertTrue(success);
        assertTrue(executor.requests.isEmpty());
    }

    @Test
    void shouldMoveMessageToDeadLetterWhenRetryLimitExceeded() {
        ReadyProvider provider = new ReadyProvider();
        TicketRepairEngine ticketRepairEngine = TicketRepairEngine.forTesting(
                provider,
                null,
                InMemoryRepairRecordRepository.inMemory(),
                ragEngine(),
                TicketFieldMapping.defaults(),
                false
        );
        RecordingBugFixExecutor executor = new RecordingBugFixExecutor();
        RecordingDeadLetterRepository deadLetters = new RecordingDeadLetterRepository();
        RdBotFixEngine fixEngine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(),
                taskRegistry(),
                com.wish.rd.engine.bugfix.BugFixPromptBuilder.defaultBuilder(),
                executor
        );
        TicketRepairExecutionConsumer consumer = new TicketRepairExecutionConsumer(
                ticketRepairEngine,
                provider,
                fixEngine,
                TicketFieldMapping.defaults(),
                true,
                null,
                null,
                false,
                null,
                deadLetters,
                3
        );

        boolean consumed = consumer.handle(message("FS-READY", 4));

        assertTrue(consumed);
        assertEquals(1, deadLetters.list().size());
        assertEquals(4, deadLetters.list().getFirst().originalAttempt());
        assertTrue(deadLetters.list().getFirst().reason().contains("retry attempts exceeded"));
        assertTrue(executor.requests.isEmpty());
    }

    private static RagBugFixEngine ragEngine() {
        return ragEngine(RagStreamTaskRegistry.inMemory());
    }

    private static RagBugFixEngine ragEngine(RagStreamTaskRegistry registry) {
        return new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                registry,
                ChatQueueLimiter.passThrough()
        );
    }

    private static RagStreamTaskRegistry taskRegistry() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement)
        );
    }

    private static RepairTicketMessage message(String ticketId) {
        return message(ticketId, 1);
    }

    private static RepairTicketMessage message(String ticketId, int attempt) {
        return new RepairTicketMessage(
                ticketId,
                "P1",
                "trace-" + ticketId,
                attempt,
                "feishu",
                "evt-" + ticketId,
                "helpdesk.ticket.created_v1",
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private static final class RecordingDeadLetterRepository implements RepairQueueDeadLetterRepository {

        private final List<RepairQueueDeadLetter> records = new ArrayList<>();

        @Override
        public RepairQueueDeadLetter save(RepairTicketMessage message, String reason) {
            RepairQueueDeadLetter deadLetter = new RepairQueueDeadLetter(
                    "dead-letter-" + (records.size() + 1),
                    message.ticketId(),
                    message.traceId(),
                    message.source(),
                    message.eventId(),
                    message.eventType(),
                    message.attempt(),
                    reason,
                    Map.of("priority", message.priority()),
                    false,
                    1_780_000_000_000L,
                    0L
            );
            records.add(deadLetter);
            return deadLetter;
        }

        @Override
        public List<RepairQueueDeadLetter> list() {
            return List.copyOf(records);
        }

        @Override
        public Optional<RepairQueueDeadLetter> findById(String id) {
            return records.stream().filter(record -> record.id().equals(id)).findFirst();
        }

        @Override
        public RepairQueueDeadLetter markReplayed(String id) {
            throw new NoSuchElementException("dead letter not found: " + id);
        }
    }

    private static final class ReadyProvider implements TicketProviderPort {

        @Override
        public Optional<TicketSnapshot> findTicket(String ticketId) {
            return Optional.of(new TicketSnapshot(
                    ticketId,
                    "支付系统下单接口 500",
                    "金额为空时 OrderService.create 写入订单失败",
                    List.of("payment", "orders.amount"),
                    Instant.parse("2026-06-21T00:00:00Z"),
                    "P1",
                    "processing",
                    "",
                    "feishu",
                    "",
                    Map.of(TicketFieldMapping.KEY_LOGS, "ERROR orders.amount is null"),
                    Instant.EPOCH,
                    Instant.EPOCH
            ));
        }

        @Override
        public TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
            return new TicketMessages(List.of(new TicketMessage(
                    "m-1",
                    "2",
                    "u-1",
                    "text",
                    "ERROR orders.amount is null at OrderService.create",
                    List.of(),
                    Map.of(),
                    Instant.parse("2026-06-21T00:01:00Z")
            )), 1, 50, 1, false);
        }
    }

    private static final class RecordingBugFixExecutor implements BugFixExecutor {
        private final List<com.wish.rd.engine.bugfix.BugFixExecutionRequest> requests = new ArrayList<>();

        @Override
        public BugFixExecutionResult execute(com.wish.rd.engine.bugfix.BugFixExecutionRequest request) {
            requests.add(request);
            return new BugFixExecutionResult(
                    request.taskId(),
                    request.ragMessage().ticketTitle(),
                    "executed",
                    "https://github.example.local/acme/order/pull/1",
                    "{\"status\":\"SUCCESS\"}"
            );
        }
    }

    private static final class FailingBugFixExecutor implements BugFixExecutor {
        private final List<com.wish.rd.engine.bugfix.BugFixExecutionRequest> requests = new ArrayList<>();

        @Override
        public BugFixExecutionResult execute(com.wish.rd.engine.bugfix.BugFixExecutionRequest request) {
            requests.add(request);
            return new BugFixExecutionResult(
                    request.taskId(),
                    request.ragMessage().ticketTitle(),
                    "git command failed exitCode=128",
                    "",
                    "{\"status\":\"FAILED\",\"errorMessage\":\"git command failed exitCode=128\"}"
            );
        }
    }

    private static final class FailThenSucceedBugFixExecutor implements BugFixExecutor {
        private final List<com.wish.rd.engine.bugfix.BugFixExecutionRequest> requests = new ArrayList<>();

        @Override
        public BugFixExecutionResult execute(com.wish.rd.engine.bugfix.BugFixExecutionRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return new BugFixExecutionResult(
                        request.taskId(),
                        request.ragMessage().ticketTitle(),
                        "git clone failed",
                        "",
                        "{\"status\":\"FAILED\",\"errorMessage\":\"git clone failed\"}"
                );
            }
            return new BugFixExecutionResult(
                    request.taskId(),
                    request.ragMessage().ticketTitle(),
                    "executed",
                    "https://github.example.local/acme/order/pull/2",
                    "{\"status\":\"SUCCESS\"}"
            );
        }
    }

    private static final class RecordingUpdater implements TicketUpdatePort {
        private final List<TicketReplyCommand> replies = new ArrayList<>();

        @Override
        public TicketUpdateResult sendMessage(TicketReplyCommand command) {
            replies.add(command);
            return TicketUpdateResult.success("reply-" + replies.size(), "ok");
        }

        @Override
        public TicketUpdateResult updateTicket(TicketUpdateCommand command) {
            return TicketUpdateResult.success("update", "ok");
        }
    }
}
