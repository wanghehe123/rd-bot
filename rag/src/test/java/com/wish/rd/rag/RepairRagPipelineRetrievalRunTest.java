package com.wish.rd.rag;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.guidance.IntentGuidanceService;
import com.wish.rd.rag.intent.IntentClassifier;
import com.wish.rd.rag.intent.IntentTree;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.model.RepairRagRequest;
import com.wish.rd.rag.retrieval.MultiChannelRetrievalEngine;
import com.wish.rd.rag.retrieval.SearchChannel;
import com.wish.rd.rag.retrieval.impl.GlobalVectorSearchChannel;
import com.wish.rd.rag.retrieval.impl.KeywordBM25SearchChannel;
import com.wish.rd.rag.retrieval.impl.IntentDirectedVectorSearchChannel;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairRagPipelineRetrievalRunTest {

    @Test
    void persistsACompletedBugRetrievalRunForTheOwningTask() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RepairRagPipeline pipeline = new RepairRagPipeline(
                new IntentClassifier(new IntentTree(List.of())),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(List.of(
                        new IntentDirectedVectorSearchChannel(vectorStore),
                        new GlobalVectorSearchChannel(vectorStore),
                        new KeywordBM25SearchChannel(vectorStore)
                )),
                context -> { },
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );

        pipeline.prepareContext(new RepairRagRequest(
                "ticket-1", "支付服务超时", List.of(), List.of(), "task-1", "支付服务超时"
        ));

        assertEquals(RetrievalRunStatus.WAITING_INPUT, store.listByTask("task-1").getFirst().status());
    }

    @Test
    void marksAllChannelFailuresAsFailedRetryableInsteadOfWaitingInput() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        SearchChannel failing = new SearchChannel() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public boolean isEnabled(RetrievalRequest request) {
                return true;
            }

            @Override
            public ChannelSearchResult search(RetrievalRequest request) {
                throw new IllegalStateException("provider unavailable");
            }
        };
        RepairRagPipeline pipeline = new RepairRagPipeline(
                new IntentClassifier(new IntentTree(List.of())),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(List.of(failing)),
                context -> { },
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );

        pipeline.prepareContext(new RepairRagRequest(
                "ticket-1", "支付服务超时", List.of("kb-1"), List.of(), "task-fail", "支付服务超时"
        ));

        RetrievalRun run = store.listByTask("task-fail").getFirst();
        assertEquals(RetrievalRunStatus.FAILED_RETRYABLE, run.status());
    }

    @Test
    void marksHealthyEmptyHitsAsWaitingInput() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        SearchChannel emptyHealthy = new SearchChannel() {
            @Override
            public String name() {
                return "empty-healthy";
            }

            @Override
            public boolean isEnabled(RetrievalRequest request) {
                return true;
            }

            @Override
            public ChannelSearchResult search(RetrievalRequest request) {
                return new ChannelSearchResult(name(), List.of());
            }
        };
        RepairRagPipeline pipeline = new RepairRagPipeline(
                new IntentClassifier(new IntentTree(List.of())),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(List.of(emptyHealthy)),
                context -> { },
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );

        pipeline.prepareContext(new RepairRagRequest(
                "ticket-1", "支付服务超时", List.of("kb-1"), List.of(), "task-empty", "支付服务超时"
        ));

        assertEquals(RetrievalRunStatus.WAITING_INPUT, store.listByTask("task-empty").getFirst().status());
    }

    @Test
    void forcesATerminalStateWhenPipelineBodyThrows() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        SearchChannel explodingEnabled = new SearchChannel() {
            @Override
            public String name() {
                return "exploding";
            }

            @Override
            public boolean isEnabled(RetrievalRequest request) {
                throw new IllegalStateException("channel selection exploded");
            }

            @Override
            public ChannelSearchResult search(RetrievalRequest request) {
                return new ChannelSearchResult(name(), List.of());
            }
        };
        RepairRagPipeline pipeline = new RepairRagPipeline(
                new IntentClassifier(new IntentTree(List.of())),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(List.of(explodingEnabled)),
                context -> { },
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );

        try {
            pipeline.prepareContext(new RepairRagRequest(
                    "ticket-1", "支付服务超时", List.of("kb-1"), List.of(), "task-boom", "支付服务超时"
            ));
        } catch (IllegalStateException ignored) {
            // expected to surface after ensuring terminal retrieval state
        }

        RetrievalRun run = store.listByTask("task-boom").getFirst();
        assertTrue(run.status().isTerminal());
        assertEquals(RetrievalRunStatus.FAILED_RETRYABLE, run.status());
    }

    @Test
    void exposesPersistedArtifactsForRetrievalRunInspection() {
        assertDoesNotThrow(() -> RetrievalRunStore.class.getMethod("listArtifacts", String.class),
                "retrieval control-plane must expose persisted, inspectable run artifacts");
    }

    @Test
    void recordsChannelProcessAndSelectedEvidencePreviewsForOperators() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        SearchChannel code = new SearchChannel() {
            @Override
            public String name() {
                return "code";
            }

            @Override
            public boolean isEnabled(RetrievalRequest request) {
                return true;
            }

            @Override
            public ChannelSearchResult search(RetrievalRequest request) {
                return new ChannelSearchResult(name(), List.of(new RetrievedChunk(
                        "orders#status-filter", "orders query supports status filtering and amount aggregation",
                        "waimai", "code", "server/src/routes/orders.ts", 9.5d, Map.of()
                )));
            }
        };
        RepairRagPipeline pipeline = new RepairRagPipeline(
                new IntentClassifier(new IntentTree(List.of())),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(List.of(code)),
                context -> { },
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );

        pipeline.prepareContext(new RepairRagRequest(
                "ticket-1", "订单状态筛选", List.of("kb-waimai"), List.of(), "task-observe", "订单状态筛选"
        ));

        var artifacts = store.listArtifacts(store.listByTask("task-observe").getFirst().runId());
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("CHANNEL_RESULT")
                        && artifact.contentPreview().contains("orders query supports status filtering")),
                "operators need the bounded content returned by each channel, not only a channel counter");
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("SELECTED_EVIDENCE")
                        && artifact.contentPreview().contains("status filtering")),
                "operators need a bounded preview of the evidence that reached the prompt");
    }

    @Test
    void recordsEveryReturnedCandidateAndFinalEvidenceAsSeparateInspectableArtifacts() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        List<RetrievedChunk> channelChunks = List.of(
                new RetrievedChunk(
                        "orders#status-filter", "status filtering implementation details", "waimai", "code",
                        "server/src/routes/orders.ts", 9.5d, Map.of()
                ),
                new RetrievedChunk(
                        "orders#amount-summary", "amount aggregation implementation details", "waimai", "code",
                        "server/src/services/orderStats.ts", 8.5d, Map.of()
                )
        );
        SearchChannel code = new SearchChannel() {
            @Override
            public String name() {
                return "code";
            }

            @Override
            public boolean isEnabled(RetrievalRequest request) {
                return true;
            }

            @Override
            public ChannelSearchResult search(RetrievalRequest request) {
                return new ChannelSearchResult(name(), channelChunks);
            }
        };
        RepairRagPipeline pipeline = new RepairRagPipeline(
                new IntentClassifier(new IntentTree(List.of())),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(List.of(code)),
                context -> { },
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );

        pipeline.prepareContext(new RepairRagRequest(
                "ticket-1", "订单查询统计", List.of(), List.of("kb-waimai"), "task-all-visible", "订单查询统计"
        ));

        var artifacts = store.listArtifacts(store.listByTask("task-all-visible").getFirst().runId());
        assertEquals(2, artifacts.stream().filter(artifact -> artifact.artifactType().equals("CHANNEL_CANDIDATE")).count(),
                "each candidate returned by a channel must remain inspectable on its own");
        assertEquals(2, artifacts.stream().filter(artifact -> artifact.artifactType().equals("SELECTED_EVIDENCE")).count(),
                "each final evidence item must remain inspectable on its own");
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("FUSION_RESULT")
                        && artifact.contentPreview().contains("selected=2")),
                "the fusion decision must be visible instead of jumping from channel results to success");
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("ROUTE_SCOPE")
                        && artifact.contentPreview().contains("kb-waimai")),
                "the approved knowledge-base scope must be visible in the process timeline");
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("QUALITY_REPORT")),
                "the deterministic quality decision must be visible in local and PostgreSQL stores");
        assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("CONTEXT_PACKAGE")),
                "the final context packaging step must be visible in local and PostgreSQL stores");
    }
}
