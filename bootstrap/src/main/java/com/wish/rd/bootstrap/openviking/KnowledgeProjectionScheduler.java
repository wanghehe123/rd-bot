package com.wish.rd.bootstrap.openviking;

import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexPollEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexReconcileEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexSyncEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionBackfillEngine;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
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
 *
 * <p>回填节拍同样独立：{@code rd.knowledge.projection.backfill.enabled} 默认 false，
 * 允许名单为空时即使打开也不自动跑。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.projection.mode", havingValue = "ON")
public class KnowledgeProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProjectionScheduler.class);

    private final KnowledgeExternalIndexSyncEngine syncEngine;
    private final KnowledgeExternalIndexPollEngine pollEngine;
    private final KnowledgeExternalIndexReconcileEngine reconcileEngine;
    private final KnowledgeProjectionBackfillEngine backfillEngine;
    private final KnowledgeBaseStore knowledgeBaseStore;
    private final boolean reconcileEnabled;
    private final boolean backfillEnabled;
    private final int backfillBatchSize;
    private final List<String> backfillAllowlist;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconciling = new AtomicBoolean(false);
    private final AtomicBoolean backfilling = new AtomicBoolean(false);

    /** 多构造器时 Spring 不做猜测，必须显式指定注入入口，否则真机启动直接失败。 */
    @Autowired
    public KnowledgeProjectionScheduler(
            KnowledgeExternalIndexSyncEngine syncEngine,
            KnowledgeExternalIndexPollEngine pollEngine,
            KnowledgeExternalIndexReconcileEngine reconcileEngine,
            KnowledgeProjectionBackfillEngine backfillEngine,
            KnowledgeBaseStore knowledgeBaseStore,
            @Value("${rd.knowledge.projection.reconcile.enabled:false}") boolean reconcileEnabled,
            @Value("${rd.knowledge.projection.backfill.enabled:false}") boolean backfillEnabled,
            @Value("${rd.knowledge.projection.backfill.batch-size:20}") int backfillBatchSize,
            @Value("${rd.knowledge.projection.backfill.knowledge-base-allowlist:}") String backfillAllowlist
    ) {
        this(
                syncEngine,
                pollEngine,
                reconcileEngine,
                backfillEngine,
                knowledgeBaseStore,
                reconcileEnabled,
                backfillEnabled,
                backfillBatchSize,
                backfillAllowlist,
                System::currentTimeMillis);
    }

    public KnowledgeProjectionScheduler(
            KnowledgeExternalIndexSyncEngine syncEngine,
            KnowledgeExternalIndexPollEngine pollEngine,
            KnowledgeExternalIndexReconcileEngine reconcileEngine,
            KnowledgeProjectionBackfillEngine backfillEngine,
            KnowledgeBaseStore knowledgeBaseStore,
            boolean reconcileEnabled,
            boolean backfillEnabled,
            int backfillBatchSize,
            String backfillAllowlist,
            LongSupplier clock
    ) {
        this.syncEngine = syncEngine;
        this.pollEngine = pollEngine;
        this.reconcileEngine = reconcileEngine;
        this.backfillEngine = backfillEngine;
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.reconcileEnabled = reconcileEnabled;
        this.backfillEnabled = backfillEnabled;
        this.backfillBatchSize = backfillBatchSize;
        this.backfillAllowlist = parseAllowlist(backfillAllowlist);
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

    /**
     * 有界回填。默认关闭；允许名单为空时即使打开也不自动跑。
     */
    @Scheduled(fixedDelayString = "${rd.knowledge.projection.backfill.interval-millis:60000}")
    public void backfillTick() {
        if (!backfillEnabled || backfillAllowlist.isEmpty()) {
            return;
        }
        if (!backfilling.compareAndSet(false, true)) {
            log.debug("previous backfill tick still running, skip");
            return;
        }
        try {
            long now = clock.getAsLong();
            int scanned = 0;
            for (KnowledgeBase knowledgeBase : knowledgeBaseStore.list()) {
                if (!knowledgeBase.visible() || !backfillAllowlist.contains(knowledgeBase.id())) {
                    continue;
                }
                backfillEngine.backfillBatch(knowledgeBase.id(), backfillBatchSize, now);
                scanned++;
            }
            if (scanned > 0) {
                log.info("knowledge projection backfill tick scanned={}", scanned);
            }
        } catch (RuntimeException ex) {
            log.warn("knowledge projection backfill tick failed: {}", ex.toString());
        } finally {
            backfilling.set(false);
        }
    }

    private static List<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
