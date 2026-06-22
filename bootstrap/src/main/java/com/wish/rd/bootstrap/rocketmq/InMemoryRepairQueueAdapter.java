package com.wish.rd.bootstrap.rocketmq;

import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存修复队列适配器：默认本地/测试实现，不依赖外部 MQ。
 *
 * <p>由配置 {@code rd.repair.queue.mode=memory}（{@code matchIfMissing=true}）开启。
 * 发布即同步触发消费回调，便于本地端到端联调与单测。
 */
@Component
@ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryRepairQueueAdapter implements RepairQueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRepairQueueAdapter.class);

    private static final String TOPIC = "in-memory://RD_BOT_REPAIR_TICKET";

    private final List<RepairTicketMessage> published = new ArrayList<>();
    private final AtomicLong idSequence = new AtomicLong(0);
    private volatile RepairQueueConsumer consumer;

    /**
     * 绑定消费回调（由 Spring 在启动时找到唯一的 {@link RepairQueueConsumer} bean 后注入）。
     *
     * @param consumer 消费回调，可为 null 表示暂不消费
     */
    public void bindConsumer(RepairQueueConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public synchronized RepairQueuePublishResult publish(RepairTicketMessage message) {
        long id = idSequence.incrementAndGet();
        published.add(message);
        log.info("in-memory queue published, ticketId={}, tag={}, seq={}",
                message.ticketId(), message.tag(), id);
        return RepairQueuePublishResult.success("mem-" + id, TOPIC, message.tag());
    }

    /**
     * 触发消费已发布但未消费的消息，返回处理结果。
     *
     * <p>测试通道在调用 {@code POST /test/repair/tickets/{ticketId}/run} 时触发。
     *
     * @param ticketId 工单 ID
     * @return 消费是否成功；未找到消息返回 empty
     */
    public synchronized Optional<Boolean> drainOne(String ticketId) {
        Optional<RepairTicketMessage> found = published.stream()
                .filter(message -> message.ticketId().equals(ticketId))
                .reduce((first, second) -> second);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (consumer == null) {
            log.warn("in-memory queue has no consumer bound, ticketId={}", ticketId);
            return Optional.empty();
        }
        return Optional.of(consumer.handle(found.get()));
    }

    /**
     * 同步发布并立即消费一条消息，返回消费结果。
     *
     * @param message 队列消息
     * @return 消费是否成功
     */
    public synchronized boolean publishAndDrain(RepairTicketMessage message) {
        publish(message);
        if (consumer == null) {
            return false;
        }
        return consumer.handle(message);
    }

    /**
     * 快照已发布消息（测试与排查用）。
     *
     * @return 不可变消息列表
     */
    public synchronized List<RepairTicketMessage> snapshot() {
        return List.copyOf(published);
    }
}
