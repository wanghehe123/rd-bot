package com.wish.rd.bootstrap.controller.testchannel;

import com.wish.rd.adapter.model.TicketMessage;
import com.wish.rd.adapter.model.TicketMessageQuery;
import com.wish.rd.adapter.model.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.bootstrap.feishu.ticket.impl.MockFeishuTicketAdapter;
import com.wish.rd.bootstrap.queue.impl.InMemoryRepairQueueAdapter;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairRecord;
import com.wish.rd.engine.ticket.model.RepairRecordArtifact;
import com.wish.rd.engine.ticket.RepairRecordRepository;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import com.wish.rd.engine.ticket.model.TicketEventInput;
import com.wish.rd.engine.ticket.impl.TicketRepairEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 飞书工单接入测试通道控制器（仅 {@code /test/...} 前缀，与正式接口隔离）。
 *
 * <p>提供端到端联调能力：
 * <ul>
 *   <li>{@code POST /test/feishu/helpdesk/events/ticket-created}：触发 Mock 工单事件入队。</li>
 *   <li>{@code POST /test/repair/tickets/{ticketId}/run}：消费一条工单并返回完整修复记录视图。</li>
 *   <li>{@code GET /test/repair/queue}：查看内存队列快照。</li>
 * </ul>
 *
 * <p>不要求 PostgreSQL / 飞书 / Redis Stream，全部走内存实现。
 */
@RestController
public class FeishuTicketTestChannelController {

    private static final Logger log = LoggerFactory.getLogger(FeishuTicketTestChannelController.class);

    private final TicketEventIngestionEngine ingestionEngine;
    private final TicketRepairEngine repairEngine;
    private final TicketProviderPort providerPort;
    private final RepairRecordRepository recordRepository;
    private final InMemoryRepairQueueAdapter inMemoryQueue;
    private final MockFeishuTicketAdapter mockAdapter;

    public FeishuTicketTestChannelController(
            TicketEventIngestionEngine ingestionEngine,
            TicketRepairEngine repairEngine,
            TicketProviderPort providerPort,
            RepairRecordRepository recordRepository,
            Optional<InMemoryRepairQueueAdapter> inMemoryQueue,
            Optional<MockFeishuTicketAdapter> mockAdapter
    ) {
        this.ingestionEngine = ingestionEngine;
        this.repairEngine = repairEngine;
        this.providerPort = providerPort;
        this.recordRepository = recordRepository;
        this.inMemoryQueue = inMemoryQueue.orElse(null);
        this.mockAdapter = mockAdapter.orElse(null);
    }

    /**
     * 触发 Mock 工单创建事件入队。
     *
     * @param request 事件请求（ticketId 缺省时用 {@code FS-MOCK-1}）
     * @return 入队结果
     */
    @PostMapping("/test/feishu/helpdesk/events/ticket-created")
    public ResponseEntity<TicketCreatedEventResponse> publishTicketCreated(
            @RequestBody(required = false) TicketCreatedEventRequest request
    ) {
        String ticketId = request == null || request.ticketId() == null || request.ticketId().isBlank()
                ? "FS-MOCK-1"
                : request.ticketId();
        TicketEventInput event = new TicketEventInput(
                ticketId,
                "evt-" + UUID.randomUUID(),
                TicketEventInput.TYPE_TICKET_CREATED,
                "feishu",
                request == null ? "" : request.priority(),
                request == null || request.traceId() == null ? "" : request.traceId(),
                Instant.now(),
                Map.of()
        );
        RepairQueuePublishResult result = ingestionEngine.ingest(event);
        log.info("test channel published ticket-created, ticketId={}, success={}",
                ticketId, result.success());
        return ResponseEntity.ok(new TicketCreatedEventResponse(
                result.success(),
                result.messageId(),
                result.targetTopic(),
                result.targetTag(),
                result.errorMessage(),
                ticketId
        ));
    }

    /**
     * 消费一条工单修复消息并返回完整修复记录视图。
     *
     * @param ticketId 工单 ID
     * @return 修复运行结果
     */
    @PostMapping("/test/repair/tickets/{ticketId}/run")
    public ResponseEntity<RepairRunResponse> runRepair(@PathVariable("ticketId") String ticketId) {
        // 如果队列里没有对应消息，则补发一条，使端到端可在任意 ticketId 上触发
        RepairTicketMessage message = ensureMessagePublished(ticketId);

        TicketRepairEngine.RepairOutcome outcome = repairEngine.process(message);
        Optional<RepairRecord> record = recordRepository.findById(outcome.repairRecordId());
        if (record.isEmpty()) {
            return ResponseEntity.internalServerError().build();
        }
        List<RepairRecordArtifact> artifacts = recordRepository.listArtifacts(outcome.repairRecordId());
        TicketSnapshot snapshot = providerPort.findTicket(ticketId).orElse(null);
        TicketMessages messages = providerPort.findMessages(ticketId, TicketMessageQuery.defaults());

        return ResponseEntity.ok(new RepairRunResponse(
                ticketId,
                record.get().id(),
                record.get().status().name(),
                outcome.contextSummary(),
                record.get().ragSummary(),
                new TicketSnapshotView(
                        snapshot == null ? "" : snapshot.ticketId(),
                        snapshot == null ? "" : snapshot.title(),
                        snapshot == null ? "" : snapshot.description(),
                        snapshot == null ? "" : snapshot.priority(),
                        snapshot == null ? "" : snapshot.status(),
                        snapshot == null ? "" : snapshot.source()
                ),
                messages.messages().stream()
                        .map(msg -> new TicketMessageView(
                                msg.messageId(), msg.senderType(), msg.content(), msg.createdAt().toString()
                        ))
                        .toList(),
                artifacts.stream()
                        .map(artifact -> new ArtifactView(
                                artifact.id(), artifact.artifactType(), artifact.summary()
                        ))
                        .toList(),
                message
        ));
    }

    /**
     * 查看内存队列发布快照（仅内存模式可用）。
     *
     * @return 队列快照
     */
    @GetMapping("/test/repair/queue")
    public ResponseEntity<Object> queueSnapshot() {
        if (inMemoryQueue == null) {
            return ResponseEntity.ok(Map.of("mode", "not-in-memory", "messages", List.of()));
        }
        return ResponseEntity.ok(Map.of(
                "mode", "memory",
                "messages", inMemoryQueue.snapshot()
        ));
    }

    /**
     * 注册 Mock 工单与消息（便于回放特定场景）。
     *
     * @param request Mock 工单注册请求
     * @return 注册结果
     */
    @PostMapping("/test/feishu/helpdesk/mock-ticket")
    public ResponseEntity<Map<String, Object>> registerMockTicket(@RequestBody MockTicketRegistrationRequest request) {
        if (mockAdapter == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "mock adapter not available"));
        }
        Map<String, String> custom = request.customFields() == null ? Map.of() : request.customFields();
        TicketSnapshot snapshot = new TicketSnapshot(
                request.ticketId(),
                request.title(),
                request.description(),
                request.labels() == null ? List.of() : request.labels(),
                Instant.now(),
                request.priority(),
                request.status() == null ? "processing" : request.status(),
                "",
                "feishu",
                request.chatId() == null ? "" : request.chatId(),
                custom,
                Instant.now(),
                Instant.EPOCH
        );
        mockAdapter.register(snapshot);
        List<TicketMessage> messages = request.messages() == null
                ? List.<TicketMessage>of()
                : request.messages().stream()
                        .map(m -> new TicketMessage(
                                m.messageId(), m.senderType(), m.senderId(), "text",
                                m.content(), List.of(), Map.of(), Instant.now()
                        ))
                        .toList();
        mockAdapter.registerMessages(request.ticketId(), messages);
        return ResponseEntity.ok(Map.of("registered", true, "ticketId", request.ticketId()));
    }

    private RepairTicketMessage ensureMessagePublished(String ticketId) {
        if (inMemoryQueue != null) {
            List<RepairTicketMessage> snapshot = inMemoryQueue.snapshot();
            Optional<RepairTicketMessage> existing = snapshot.stream()
                    .filter(message -> message.ticketId().equals(ticketId))
                    .reduce((first, second) -> second);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        RepairTicketMessage fresh = new RepairTicketMessage(
                ticketId, "P1", "trace-" + ticketId, 1, "feishu",
                "evt-" + UUID.randomUUID(), TicketEventInput.TYPE_TICKET_CREATED, Instant.now()
        );
        return fresh;
    }

    /** ticket-created 事件请求。 */
    public record TicketCreatedEventRequest(String ticketId, String priority, String traceId) {
    }

    /** ticket-created 事件响应。 */
    public record TicketCreatedEventResponse(
            boolean success,
            String messageId,
            String targetTopic,
            String targetTag,
            String errorMessage,
            String ticketId
    ) {
    }

    /** 工单快照视图。 */
    public record TicketSnapshotView(
            String ticketId,
            String title,
            String description,
            String priority,
            String status,
            String source
    ) {
    }

    /** 工单消息视图。 */
    public record TicketMessageView(
            String messageId,
            String senderType,
            String content,
            String createdAt
    ) {
    }

    /** 产物视图。 */
    public record ArtifactView(String id, String artifactType, String summary) {
    }

    /** 修复运行响应。 */
    public record RepairRunResponse(
            String ticketId,
            String repairRecordId,
            String status,
            String contextSummary,
            String ragSummary,
            TicketSnapshotView ticket,
            List<TicketMessageView> messages,
            List<ArtifactView> artifacts,
            RepairTicketMessage publishedMessage
    ) {
    }

    /** Mock 工单注册请求。 */
    public record MockTicketRegistrationRequest(
            String ticketId,
            String title,
            String description,
            List<String> labels,
            String priority,
            String status,
            String chatId,
            Map<String, String> customFields,
            List<MockMessageRegistrationRequest> messages
    ) {
    }

    /** Mock 消息注册请求。 */
    public record MockMessageRegistrationRequest(
            String messageId,
            String senderType,
            String senderId,
            String content
    ) {
    }
}
