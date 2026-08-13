package com.wish.rd.engine.retrieval.impl;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.RequirementKnowledgeSearchPort;
import com.wish.rd.engine.retrieval.model.ChannelAudit;
import com.wish.rd.engine.retrieval.model.KnowledgeProviderMode;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.retrieval.navigator.ThreeTierNavigationEngine;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorCollectedEvidence;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorRunResult;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorStopReason;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 需求交付读路径的模式装饰器。LOCAL 原样委托；SHADOW 的返回值只由本地检索产生，
 * 旁路探测不得 {@code close()}/{@code get()} 到调用方——那会把影子任务变成关键路径。
 */
public final class KnowledgeRetrievalModeRouter implements RequirementKnowledgeSearchPort {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalModeRouter.class);

    private final RequirementKnowledgeSearchPort localSearch;
    private final KnowledgeProviderMode mode;
    private final Duration shadowTimeBudget;
    private final ThreeTierNavigationEngine navigationEngine;
    private final RetrievalRunLifecycle lifecycle;
    private final ExecutorService shadowExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param localSearch       现有本地检索适配器
     * @param mode              读路径模式
     * @param shadowTimeBudget  影子探测的独立超时，超时只丢弃远端结果
     * @param navigationEngine  三层导航；LOCAL 模式不会调用
     * @param lifecycle         用于把旁路/降级挂到已有 retrieval run
     */
    public KnowledgeRetrievalModeRouter(
            RequirementKnowledgeSearchPort localSearch,
            KnowledgeProviderMode mode,
            Duration shadowTimeBudget,
            ThreeTierNavigationEngine navigationEngine,
            RetrievalRunLifecycle lifecycle
    ) {
        this.localSearch = Objects.requireNonNull(localSearch, "localSearch must not be null");
        this.mode = mode == null ? KnowledgeProviderMode.LOCAL : mode;
        this.shadowTimeBudget = shadowTimeBudget == null || shadowTimeBudget.isZero() || shadowTimeBudget.isNegative()
                ? Duration.ofSeconds(20)
                : shadowTimeBudget;
        this.navigationEngine = Objects.requireNonNull(navigationEngine, "navigationEngine must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    /**
     * 范围解析始终委托本地适配器，与模式无关。
     *
     * @param task 需求任务
     * @return 项目知识库范围
     */
    @Override
    public RetrievalScope resolveScope(RdRequirementTask task) {
        return localSearch.resolveScope(task);
    }

    /**
     * 按模式检索。空 scope 一律走本地 MISSING_SCOPE 分支。
     *
     * @param task  需求任务
     * @param role  当前角色
     * @param query 查询
     * @param scope 已解析范围，null 时回退 {@link #resolveScope}
     * @param topK  返回上限
     * @return 检索结果；SHADOW 下与纯本地同一实例
     */
    @Override
    public SearchResult search(
            RdRequirementTask task,
            AgentRole role,
            String query,
            RetrievalScope scope,
            int topK
    ) {
        RetrievalScope safeScope = scope == null ? localSearch.resolveScope(task) : scope;
        // 空 scope 在本地是"跳过检索"，任何模式都不得把它解释成全局检索或远端探测。
        if (safeScope.knowledgeBaseIds().isEmpty()) {
            return localSearch.search(task, role, query, safeScope, topK);
        }
        return switch (mode) {
            case LOCAL -> localSearch.search(task, role, query, safeScope, topK);
            case SHADOW -> searchShadow(task, role, query, safeScope, topK);
            case OPENVIKING -> searchOpenViking(task, role, query, safeScope, topK);
        };
    }

    private SearchResult searchShadow(
            RdRequirementTask task,
            AgentRole role,
            String query,
            RetrievalScope scope,
            int topK
    ) {
        launchShadowProbe(task, query, scope, topK);
        return localSearch.search(task, role, query, scope, topK);
    }

    private SearchResult searchOpenViking(
            RdRequirementTask task,
            AgentRole role,
            String query,
            RetrievalScope scope,
            int topK
    ) {
        String runId = activeRunId(task);
        NavigatorRunResult remote;
        try {
            remote = navigationEngine.navigate(query, scope.knowledgeBaseIds(), runId);
        } catch (RuntimeException ex) {
            log.debug("openviking navigation threw; degrading to local: {}", ex.toString());
            return degradeToLocal(task, role, query, scope, topK, "REMOTE_UNAVAILABLE",
                    "三层导航抛出异常，已回退本地检索");
        }
        if (shouldDegrade(remote)) {
            return degradeToLocal(task, role, query, scope, topK, remote.stopReason().name(),
                    "三层导航 " + remote.stopReason().name() + "，已回退本地检索");
        }
        return new SearchResult(
                toCandidates(remote.evidence(), topK),
                List.of(new ChannelAudit(
                        "OpenViking",
                        false,
                        remote.evidence().size(),
                        "",
                        remote.stopReason().name()))
        );
    }

    private boolean shouldDegrade(NavigatorRunResult remote) {
        if (remote == null || remote.evidence().isEmpty()) {
            return true;
        }
        return remote.stopReason() == NavigatorStopReason.REMOTE_UNAVAILABLE
                || remote.stopReason() == NavigatorStopReason.NOTHING_ADMITTED;
    }

    private SearchResult degradeToLocal(
            RdRequirementTask task,
            AgentRole role,
            String query,
            RetrievalScope scope,
            int topK,
            String stopReason,
            String message
    ) {
        SearchResult localResult = localSearch.search(task, role, query, scope, topK);
        List<ChannelAudit> channels = new ArrayList<>(localResult.channels());
        channels.add(new ChannelAudit(
                "OpenViking",
                true,
                0,
                "DEGRADED",
                message + "; stopReason=" + stopReason));
        return new SearchResult(localResult.candidates(), channels);
    }

    private void launchShadowProbe(RdRequirementTask task, String query, RetrievalScope scope, int topK) {
        String runId = activeRunId(task);
        List<String> knowledgeBaseIds = List.copyOf(scope.knowledgeBaseIds());
        String safeQuery = query == null ? "" : query;
        int limit = Math.max(1, topK);
        shadowExecutor.execute(() -> {
            Future<?> probe = shadowExecutor.submit(() ->
                    navigationEngine.probeL0(safeQuery, knowledgeBaseIds, limit, runId));
            try {
                probe.get(shadowTimeBudget.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                probe.cancel(true);
                log.debug("shadow navigator probe exceeded its time budget and was discarded");
            } catch (ExecutionException ex) {
                log.debug("shadow navigator probe discarded: {}", String.valueOf(ex.getCause()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                probe.cancel(true);
                log.debug("shadow navigator probe interrupted and discarded");
            } catch (RuntimeException ex) {
                log.debug("shadow navigator probe discarded: {}", ex.toString());
            }
        });
    }

    private String activeRunId(RdRequirementTask task) {
        if (task == null || task.taskId() == null || task.taskId().isBlank()) {
            return "";
        }
        return lifecycle.findLatestForTask(task.taskId()).map(run -> run.runId()).orElse("");
    }

    private static List<RoleContextEvidence> toCandidates(List<NavigatorCollectedEvidence> evidence, int topK) {
        int bound = Math.max(1, topK);
        List<RoleContextEvidence> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (NavigatorCollectedEvidence item : evidence) {
            if (candidates.size() >= bound) {
                break;
            }
            if (item == null) {
                continue;
            }
            candidates.add(new RoleContextEvidence(
                    item.admitted().document().id(),
                    blankTo(item.admitted().document().knowledgeType(), "KNOWLEDGE"),
                    item.resourceUri(),
                    item.admitted().document().sourceName(),
                    blankTo(item.admitted().document().checksum(), item.admitted().binding().observedChecksum()),
                    item.fencedText(),
                    now,
                    "openviking " + item.tier()
                            + " admitted version=" + item.admitted().binding().observedVersion(),
                    Math.max(0.0d, Math.min(1.0d, item.admitted().hit().score())),
                    blankTo(item.admitted().document().knowledgeType(), "DOMAIN_KNOWLEDGE").toUpperCase(),
                    false
            ));
        }
        return List.copyOf(candidates);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
