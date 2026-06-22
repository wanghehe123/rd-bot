package com.wish.rd.bootstrap;

import com.wish.rd.adapter.TicketMessage;
import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.adapter.TicketUpdateCommand;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.TicketUpdateResult;
import com.wish.rd.bootstrap.controller.testchannel.FeishuTicketTestChannelController;
import com.wish.rd.bootstrap.rocketmq.InMemoryRepairQueueAdapter;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import com.wish.rd.engine.ticket.TicketFieldMapping;
import com.wish.rd.engine.ticket.TicketRepairEngine;
import com.wish.rd.engine.ticket.InMemoryRepairRecordRepository;
import com.wish.rd.engine.ticket.RepairRecordRepository;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 {@link FeishuTicketTestChannelController} 的端到端 Mock 流程：
 * ticket-created 入队 -> run 消费 -> 产出 repair_record + 产物。
 */
class FeishuTicketTestChannelControllerTest {

    private MockMvc mockMvc;
    private InMemoryRepairQueueAdapter queueAdapter;
    private StubProvider provider;
    private RepairRecordRepository repo;

    @BeforeEach
    void setUp() {
        queueAdapter = new InMemoryRepairQueueAdapter();
        provider = new StubProvider();
        repo = InMemoryRepairRecordRepository.inMemory();
        RagBugFixEngine ragEngine = new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                ChatQueueLimiter.passThrough()
        );
        TicketRepairEngine repairEngine = TicketRepairEngine.forTesting(
                provider, null, repo, ragEngine, TicketFieldMapping.defaults(), false
        );
        queueAdapter.bindConsumer(repairEngine);
        TicketEventIngestionEngine ingestion = TicketEventIngestionEngine.forTesting(
                queueAdapter,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu"
        );
        FeishuTicketTestChannelController controller = new FeishuTicketTestChannelController(
                ingestion, repairEngine, provider, repo,
                Optional.of(queueAdapter), Optional.empty()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ticketCreatedEventIsPublishedToInMemoryQueue() throws Exception {
        mockMvc.perform(post("/test/feishu/helpdesk/events/ticket-created")
                        .contentType("application/json")
                        .content("""
                                {"ticketId":"T-END-1","priority":"P1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.ticketId").value("T-END-1"))
                .andExpect(jsonPath("$.targetTag").value("P1"));

        mockMvc.perform(get("/test/repair/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("memory"))
                .andExpect(jsonPath("$.messages.length()").value(1));
    }

    @Test
    void runRepairProducesRecordAndArtifacts() throws Exception {
        mockMvc.perform(post("/test/repair/tickets/T-READY/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value("T-READY"))
                .andExpect(jsonPath("$.status").value("CONTEXT_READY"))
                .andExpect(jsonPath("$.repairRecordId").exists())
                .andExpect(jsonPath("$.publishedMessage.ticketId").value("T-READY"))
                .andExpect(jsonPath("$.artifacts.length()", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.ticket.title").value("支付系统下单接口 500"))
                .andExpect(jsonPath("$.contextSummary").exists());
    }

    @Test
    void runRepairOnMissingTicketTransitionsToFailed() throws Exception {
        mockMvc.perform(post("/test/repair/tickets/T-MISSING/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    /** 确定性 Provider：T-READY 满足 RAG，T-MISSING 不存在。 */
    private static final class StubProvider implements TicketProviderPort {
        @Override
        public Optional<TicketSnapshot> findTicket(String ticketId) {
            if ("T-READY".equals(ticketId)) {
                Map<String, String> custom = new LinkedHashMap<>();
                custom.put(TicketFieldMapping.KEY_LOGS, "ERROR orders.amount is null");
                custom.put(TicketFieldMapping.KEY_REPOSITORY, "github.com/org/payment");
                TicketSnapshot snapshot = new TicketSnapshot(
                        "T-READY", "支付系统下单接口 500",
                        "金额为空时 OrderService.create 写入订单失败",
                        List.of("payment"), Instant.EPOCH, "P1", "processing", "", "feishu", "",
                        custom, Instant.EPOCH, Instant.EPOCH
                );
                return Optional.of(snapshot);
            }
            return Optional.empty();
        }

        @Override
        public TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
            if ("T-READY".equals(ticketId)) {
                return new TicketMessages(
                        List.of(new TicketMessage(
                                "m-1", "2", "u-1", "text",
                                "ERROR orders.amount is null at OrderService.create",
                                List.of(), Map.of(), Instant.EPOCH
                        )),
                        1, 50, 1L, false
                );
            }
            return TicketMessages.empty();
        }
    }
}
