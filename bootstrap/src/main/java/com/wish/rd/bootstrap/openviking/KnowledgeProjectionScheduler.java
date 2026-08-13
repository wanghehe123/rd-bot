package com.wish.rd.bootstrap.openviking;

import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexPollEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexReconcileEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexSyncEngine;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>对账节拍有独立开关 {@code rd.knowledge.projection.reconcile.enabled}（默认 false）
 * 和独立间隔 {@code rd.knowledge.projection.reconcile.interval-millis}（默认 300000）。
 * 打开投影但不打开对账时，Worker/Poller 仍跑，对账保持静默。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.projection.mode", havingValue = "ON")
public class KnowledgeProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProjectionScheduler.class);

    private final KnowledgeExternalIndexSyncEngine syncEngine;
    private final KnowledgeExternalIndexPollEngine pollEngine;
    private final KnowledgeExternalIndexReconcileEngine reconcileEngine;
    private final KnowledgeBaseStore knowledgeBaseStore;
    private final boolean reconcileEnabled;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

    public KnowledgeProjectionScheduler(
            KnowledgeExternalIndexSyncEngine syncEngine,
            KnowledgeExternalIndexPollEngine pollEngine,
            KnowledgeExternalIndexReconcileEngine reconcileEngine,
            KnowledgeBaseStore knowledgeBaseStore,
            @Value("${rd.knowledge.projection.reconcile.enabled:false}") boolean reconcileEnabled
    ) {
        this(syncEngine, pollEngine, reconcileEngine, knowledgeBaseStore, reconcileEnabled, System::currentTimeMillis);
    }

    public KnowledgeProjectionScheduler(
            KnowledgeExternalIndexSyncEngine syncEngine,
            KnowledgeExternalIndexPollEngine pollEngine,
            KnowledgeExternalIndexReconcileEngine reconcileEngine,
            KnowledgeBaseStore knowledgeBaseStore,
            boolean reconcileEnabled,
            LongSupplier clock
    ) {
        this.syncEngine = syncEngine;
        this.pollEngine = pollEngine;
        this.reconcileEngine = reconcileEngine;
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.reconcileEnabled = reconcileEnabled;
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

    /**
     * 对账只记账、只入队重建。默认关闭，避免未配置的部署去扫共享索引。
     */
    @Scheduled(fixedDelayString = "${rd.knowledge.projection.reconcile.interval-millis:300000}")
    public void reconcileTick() {
        if (!reconcileEnabled) {
            return;
        }
        if (!reconciling.compareAndSet(false, true)) {
            log.debug("previous reconcile tick still running, skip");
            return;
        }
        try {
            long now = clock.getAsLong();
            int scanned = 0;
            for (KnowledgeBase knowledgeBase : knowledgeBaseStore.list()) {
                if (!knowledgeBase.visible()) {
                    continue;
                }
                reconcileEngine.runOnce(
                        knowledgeBase.id(),
                        OpenVikingProjectionUris.knowledgeBaseRoot(knowledgeBase.id()),
                        now);
                scanned++;
            }
            if (scanned > 0) {
                log.info("knowledge projection reconcile tick scanned={}", scanned);
            }
        } catch (RuntimeException ex) {
            log.warn("knowledge projection reconcile tick failed: {}", ex.toString());
        } finally {
            reconciling.set(false);
        }
    }
}
