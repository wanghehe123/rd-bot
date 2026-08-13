package com.wish.rd.bootstrap.openviking;

import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexPollEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexSyncEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 投影节拍。默认关闭（{@code rd.knowledge.projection.mode=OFF}）。
 *
 * <p>本地只用 {@code AtomicBoolean} 防止自己压自己；跨实例的互斥由 Outbox 的租约负责，
 * 因为分布式锁只能保证"同时只有一个调度器"，保证不了"同时只有一个人在写这一行"。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.projection.mode", havingValue = "ON")
public class KnowledgeProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProjectionScheduler.class);

    private final KnowledgeExternalIndexSyncEngine syncEngine;
    private final KnowledgeExternalIndexPollEngine pollEngine;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public KnowledgeProjectionScheduler(
            KnowledgeExternalIndexSyncEngine syncEngine,
            KnowledgeExternalIndexPollEngine pollEngine
    ) {
        this(syncEngine, pollEngine, System::currentTimeMillis);
    }

    public KnowledgeProjectionScheduler(
            KnowledgeExternalIndexSyncEngine syncEngine,
            KnowledgeExternalIndexPollEngine pollEngine,
            LongSupplier clock
    ) {
        this.syncEngine = syncEngine;
        this.pollEngine = pollEngine;
        this.clock = clock;
    }

    /**
     * 先提交后查询：同一轮里新入队的行至少要等下一轮才被查询，
     * 避免刚发出去就立刻打一次注定 pending 的任务查询。
     */
    @Scheduled(fixedDelayString = "${rd.knowledge.projection.tick-millis:3000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.debug("previous projection tick still running, skip");
            return;
        }
        try {
            long now = clock.getAsLong();
            int dispatched = syncEngine.runOnce(now);
            int converged = pollEngine.runOnce(clock.getAsLong());
            if (dispatched > 0 || converged > 0) {
                log.info("knowledge projection tick dispatched={} converged={}", dispatched, converged);
            }
        } catch (RuntimeException ex) {
            log.warn("knowledge projection tick failed: {}", ex.toString());
        } finally {
            running.set(false);
        }
    }
}
