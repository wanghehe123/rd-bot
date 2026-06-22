package com.wish.rd.engine.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 工单事件接入引擎：把标准化事件转成 {@link RepairTicketMessage} 并发布到队列。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只依赖 {@link RepairQueuePublisher}，不调用 Feishu/RAG/修复执行。</li>
 *   <li>派生 {@code priority}/{@code traceId}/{@code attempt}，并做事件级幂等记录。</li>
 *   <li>飞书原始 envelope 解析留在 bootstrap，引擎只接收 {@link TicketEventInput}。</li>
 * </ul>
 */
@Service
public class TicketEventIngestionEngine {

    private static final Logger log = LoggerFactory.getLogger(TicketEventIngestionEngine.class);

    private final RepairQueuePublisher publisher;
    private final DeduplicationStore deduplicationStore;
    private final String defaultSource;

    public TicketEventIngestionEngine(
            RepairQueuePublisher publisher,
            @Value("${rd.repair.ticket.ingestion.default-source:feishu}") String defaultSource
    ) {
        this(publisher, new InMemoryDeduplicationStore(), defaultSource);
    }

    @Autowired
    public TicketEventIngestionEngine(
            RepairQueuePublisher publisher,
            ObjectProvider<DeduplicationStore> deduplicationStoreProvider,
            @Value("${rd.repair.ticket.ingestion.default-source:feishu}") String defaultSource
    ) {
        this(
                publisher,
                deduplicationStoreProvider.getIfAvailable(InMemoryDeduplicationStore::new),
                defaultSource
        );
    }

    TicketEventIngestionEngine(
            RepairQueuePublisher publisher,
            DeduplicationStore deduplicationStore,
            String defaultSource
    ) {
        this.publisher = publisher;
        this.deduplicationStore = deduplicationStore;
        this.defaultSource = defaultSource == null || defaultSource.isBlank() ? "feishu" : defaultSource.trim();
    }

    /**
     * 测试/直接装配入口：显式传入去重存储与默认来源。
     *
     * @param publisher         队列发布端口
     * @param deduplicationStore 去重存储
     * @param defaultSource     默认来源渠道
     * @return 接入引擎实例
     */
    public static TicketEventIngestionEngine forTesting(
            RepairQueuePublisher publisher,
            DeduplicationStore deduplicationStore,
            String defaultSource
    ) {
        return new TicketEventIngestionEngine(publisher, deduplicationStore, defaultSource);
    }

    /**
     * 接入一条事件，派生路由元数据并发布到队列。
     *
     * @param event 标准化事件输入
     * @return 队列发布结果；若重复事件则返回跳过结果
     */
    public RepairQueuePublishResult ingest(TicketEventInput event) {
        if (event == null) {
            return RepairQueuePublishResult.failure("", "", "event must not be null");
        }
        String deduplicationKey = deduplicationKey(event);
        if (deduplicationStore.alreadySeen(deduplicationKey)) {
            log.info("duplicate ticket event skipped, key={}", deduplicationKey);
            return RepairQueuePublishResult.failure("", "", "duplicate event: " + deduplicationKey);
        }
        deduplicationStore.markSeen(deduplicationKey);

        RepairTicketMessage message = toMessage(event);
        RepairQueuePublishResult result = publisher.publish(message);
        if (result.success()) {
            log.info(
                    "ticket event ingested, ticketId={}, type={}, priority={}, messageId={}",
                    message.ticketId(),
                    message.eventType(),
                    message.priority(),
                    result.messageId()
            );
        } else {
            log.warn(
                    "ticket event publish failed, ticketId={}, type={}, reason={}",
                    message.ticketId(),
                    message.eventType(),
                    result.errorMessage()
            );
        }
        return result;
    }

    private RepairTicketMessage toMessage(TicketEventInput event) {
        String traceId = event.traceId().isBlank() ? generateTraceId(event) : event.traceId();
        String source = event.source().isBlank() ? defaultSource : event.source();
        return new RepairTicketMessage(
                event.ticketId(),
                event.priority(),
                traceId,
                RepairTicketMessage.FIRST_ATTEMPT,
                source,
                event.eventId(),
                event.eventType(),
                event.occurredAt() == null ? Instant.now() : event.occurredAt()
        );
    }

    private String generateTraceId(TicketEventInput event) {
        return "trace-" + event.ticketId() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String deduplicationKey(TicketEventInput event) {
        return event.eventId() + "|" + event.ticketId() + "|" + event.eventType();
    }

    /**
     * 事件幂等存储端口，可替换为 Redis 等外部实现。
     */
    public interface DeduplicationStore {

        boolean alreadySeen(String deduplicationKey);

        void markSeen(String deduplicationKey);
    }

    /**
     * 内存去重实现，适合单机/测试。默认保留最近 4096 条。
     */
    public static final class InMemoryDeduplicationStore implements DeduplicationStore {
        private static final int CAPACITY = 4096;

        private final Set<String> seen = new LinkedHashSet<>();

        @Override
        public synchronized boolean alreadySeen(String deduplicationKey) {
            return seen.contains(deduplicationKey);
        }

        @Override
        public synchronized void markSeen(String deduplicationKey) {
            if (seen.size() >= CAPACITY && !seen.contains(deduplicationKey)) {
                // 简单淘汰：移除最早的一条，避免无界增长
                String first = seen.iterator().next();
                seen.remove(first);
            }
            seen.add(deduplicationKey);
        }
    }
}
