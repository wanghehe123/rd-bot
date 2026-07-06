package com.wish.rd.engine;

import com.wish.rd.adapter.model.TicketMessage;
import com.wish.rd.adapter.model.TicketMessageQuery;
import com.wish.rd.adapter.model.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.model.TicketReplyCommand;
import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.adapter.model.TicketUpdateCommand;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.model.TicketUpdateResult;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import com.wish.rd.engine.ticket.impl.InMemoryRepairRecordRepository;
import com.wish.rd.engine.ticket.model.RepairRecord;
import com.wish.rd.engine.ticket.model.RepairRecordArtifact;
import com.wish.rd.engine.ticket.RepairRecordRepository;
import com.wish.rd.engine.ticket.model.RepairRecordStatus;
import com.wish.rd.engine.ticket.TicketFieldMapping;
import com.wish.rd.engine.ticket.model.TicketRepairDecision;
import com.wish.rd.engine.ticket.impl.TicketRepairEngine;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link TicketRepairEngine} 的消费编排：状态推进、决策分支与产物落库。
 */
class TicketRepairEngineTest {

    @Test
    void missingTicketTransitionsToFailed() {
        StubProvider provider = StubProvider.missing();
        RepairRecordRepository repo = newRepo();
        TicketRepairEngine engine = newEngine(provider, repo, false);

        boolean success = engine.handle(message("T-missing", "P1"));

        assertFalse(success);
        Optional<RepairRecord> record = repo.findByTicketId("T-missing");
        assertTrue(record.isPresent());
        assertEquals(RepairRecordStatus.FAILED, record.get().status());
    }

    @Test
    void closedTicketIsIgnoredAsCompleted() {
        StubProvider provider = StubProvider.closed();
        RepairRecordRepository repo = newRepo();
        TicketRepairEngine engine = newEngine(provider, repo, false);

        TicketRepairEngine.RepairOutcome outcome = engine.process(message("T-closed", "P2"));

        assertEquals(TicketRepairDecision.IGNORED_CLOSED, outcome.decision());
        Optional<RepairRecord> record = repo.findByTicketId("T-closed");
        assertTrue(record.isPresent());
        assertEquals(RepairRecordStatus.COMPLETED, record.get().status());
    }

    @Test
    void insufficientFieldsTransitionToWaitingForInfoAndAskUser() {
        StubProvider provider = StubProvider.minimal();
        StubUpdater updater = new StubUpdater();
        RepairRecordRepository repo = newRepo();
        TicketRepairEngine engine = TicketRepairEngine.forTesting(
                provider,
                updater,
                repo,
                newRagEngine(),
                TicketFieldMapping.defaults(),
                true
        );

        TicketRepairEngine.RepairOutcome outcome = engine.process(message("T-min", "P1"));

        assertEquals(TicketRepairDecision.WAITING_FOR_INFO, outcome.decision());
        Optional<RepairRecord> record = repo.findByTicketId("T-min");
        assertTrue(record.isPresent());
        assertEquals(RepairRecordStatus.WAITING_FOR_INFO, record.get().status());
        assertEquals(1, updater.replies.size(), "should ask user for missing info");
    }

    @Test
    void readyTicketRunsRagAndTransitionsToContextReady() {
        StubProvider provider = StubProvider.ready();
        RepairRecordRepository repo = newRepo();
        TicketRepairEngine engine = newEngine(provider, repo, false);

        TicketRepairEngine.RepairOutcome outcome = engine.process(message("T-ready", "P1"));

        assertEquals(TicketRepairDecision.READY_FOR_RAG, outcome.decision());
        Optional<RepairRecord> record = repo.findByTicketId("T-ready");
        assertTrue(record.isPresent());
        assertEquals(RepairRecordStatus.CONTEXT_READY, record.get().status());

        List<RepairRecordArtifact> artifacts = repo.listArtifacts(record.get().id());
        List<String> types = artifacts.stream().map(RepairRecordArtifact::artifactType).toList();
        assertAll(
                () -> assertTrue(types.contains(TicketRepairEngine.ARTIFACT_FEISHU_EVENT)),
                () -> assertTrue(types.contains(TicketRepairEngine.ARTIFACT_FEISHU_TICKET_SNAPSHOT)),
                () -> assertTrue(types.contains(TicketRepairEngine.ARTIFACT_FEISHU_MESSAGES)),
                () -> assertTrue(types.contains(TicketRepairEngine.ARTIFACT_RAG_CONTEXT)),
                () -> assertFalse(outcome.contextSummary().isBlank())
        );
    }

    @Test
    void secondMessageForSameTicketReusesRecord() {
        StubProvider provider = StubProvider.ready();
        RepairRecordRepository repo = newRepo();
        TicketRepairEngine engine = newEngine(provider, repo, false);

        engine.process(message("T-reuse", "P1"));
        Optional<RepairRecord> first = repo.findByTicketId("T-reuse");
        assertTrue(first.isPresent());

        engine.process(message("T-reuse", "P1"));
        Optional<RepairRecord> second = repo.findByTicketId("T-reuse");
        assertTrue(second.isPresent());
        assertEquals(first.get().id(), second.get().id(), "should reuse existing record");
    }

    @Test
    void unexpectedExceptionMarksFailedAndReturnsFalse() {
        StubProvider provider = new StubProvider() {
            @Override
            public Optional<TicketSnapshot> findTicket(String ticketId) {
                throw new IllegalStateException("provider boom");
            }
        };
        RepairRecordRepository repo = newRepo();
        TicketRepairEngine engine = TicketRepairEngine.forTesting(
                provider,
                null,
                repo,
                newRagEngine(),
                TicketFieldMapping.defaults(),
                false
        );

        boolean success = engine.handle(message("T-boom", "P1"));

        assertFalse(success);
        Optional<RepairRecord> record = repo.findByTicketId("T-boom");
        assertTrue(record.isPresent());
        assertEquals(RepairRecordStatus.FAILED, record.get().status());
        assertTrue(record.get().ragSummary().contains("provider boom"));
    }

    @Test
    void writeBackDisabledDoesNotSendReply() {
        StubProvider provider = StubProvider.ready();
        StubUpdater updater = new StubUpdater();
        RepairRecordRepository repo = newRepo();
        TicketRepairEngine engine = TicketRepairEngine.forTesting(
                provider,
                updater,
                repo,
                newRagEngine(),
                TicketFieldMapping.defaults(),
                false
        );

        engine.process(message("T-noreply", "P0"));

        assertTrue(updater.replies.isEmpty(), "no reply when write-back disabled");
    }

    private TicketRepairEngine newEngine(StubProvider provider, RepairRecordRepository repo, boolean writeBack) {
        return TicketRepairEngine.forTesting(
                provider,
                new StubUpdater(),
                repo,
                newRagEngine(),
                TicketFieldMapping.defaults(),
                writeBack
        );
    }

    private RepairRecordRepository newRepo() {
        return InMemoryRepairRecordRepository.inMemory();
    }

    private RagBugFixEngine newRagEngine() {
        return new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                ChatQueueLimiter.passThrough()
        );
    }

    private RepairTicketMessage message(String ticketId, String priority) {
        return new RepairTicketMessage(
                ticketId,
                priority,
                "trace-" + ticketId,
                1,
                "feishu",
                "evt-" + ticketId,
                "helpdesk.ticket.created_v1",
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private static class StubProvider implements TicketProviderPort {
        final TicketSnapshot snapshot;
        final TicketMessages messages;
        final boolean missing;

        private StubProvider(TicketSnapshot snapshot, TicketMessages messages, boolean missing) {
            this.snapshot = snapshot;
            this.messages = messages;
            this.missing = missing;
        }

        /** 用于匿名子类覆盖方法。 */
        protected StubProvider() {
            this(null, TicketMessages.empty(), false);
        }

        static StubProvider missing() {
            return new StubProvider(null, TicketMessages.empty(), true);
        }

        static StubProvider closed() {
            TicketSnapshot closed = new TicketSnapshot(
                    "T-closed", "closed ticket", "desc",
                    List.of(), Instant.EPOCH, "P2", "closed", "", "feishu", "",
                    Map.of(), Instant.EPOCH, Instant.now()
            );
            return new StubProvider(closed, TicketMessages.empty(), false);
        }

        static StubProvider minimal() {
            TicketSnapshot minimal = new TicketSnapshot(
                    "T-min", "need more info", "",
                    List.of(), Instant.EPOCH, "P1", "processing", "", "feishu", "",
                    Map.of(), Instant.EPOCH, Instant.EPOCH
            );
            return new StubProvider(minimal, TicketMessages.empty(), false);
        }

        static StubProvider ready() {
            Map<String, String> custom = new LinkedHashMap<>();
            custom.put(TicketFieldMapping.KEY_LOGS, "ERROR orders.amount is null");
            TicketSnapshot ready = new TicketSnapshot(
                    "T-ready", "支付系统下单接口 500",
                    "金额为空时 OrderService.create 写入订单失败",
                    List.of("payment"), Instant.EPOCH, "P1", "processing", "", "feishu", "",
                    custom, Instant.EPOCH, Instant.EPOCH
            );
            TicketMessage userMsg = new TicketMessage(
                    "m-1", "2", "u-1", "text",
                    "ERROR orders.amount is null at OrderService.create",
                    List.of(), Map.of(), Instant.EPOCH
            );
            TicketMessages msgs = new TicketMessages(List.of(userMsg), 1, 50, 1L, false);
            return new StubProvider(ready, msgs, false);
        }

        @Override
        public Optional<TicketSnapshot> findTicket(String ticketId) {
            return missing ? Optional.empty() : Optional.of(snapshot);
        }

        @Override
        public TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
            return messages;
        }
    }

    private static class StubUpdater implements TicketUpdatePort {
        final List<TicketReplyCommand> replies = new ArrayList<>();

        @Override
        public TicketUpdateResult sendMessage(TicketReplyCommand command) {
            replies.add(command);
            return TicketUpdateResult.success("mid", "ok");
        }

        @Override
        public TicketUpdateResult updateTicket(TicketUpdateCommand command) {
            return TicketUpdateResult.success("tid", "ok");
        }
    }
}
