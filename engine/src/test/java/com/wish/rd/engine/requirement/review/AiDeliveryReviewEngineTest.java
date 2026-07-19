package com.wish.rd.engine.requirement.review;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewPackage;
import com.wish.rd.engine.requirement.review.model.AiReviewPart;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.requirement.review.model.AiReviewSource;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDeliveryReviewEngineTest {

    @Test
    void persistsOkDecisionAndAllInputPartCalls() {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        AtomicInteger calls = new AtomicInteger();
        AiReviewModelPort model = request -> {
            calls.incrementAndGet();
            return AiReviewModelPort.ModelResponse.available("model-a", """
                    {"decision":"OK","score":94,"summary":"delivery is complete","retryFromRole":"",
                     "dimensions":[{"name":"qa_acceptance","score":96,"reason":"passed","sourceIds":["source-1"]}],
                     "findings":[]}
                    """);
        };
        AiDeliveryReviewEngine engine = engine(store, model, packageWithTwoParts());

        AiReviewRun run = engine.review(task(), "{\"approved\":true}", "AUTO");

        assertEquals(AiReviewRunStatus.SUCCEEDED_OK, run.status());
        assertEquals(94, run.score());
        assertEquals(3, calls.get(), "two part reviews plus one final aggregation");
        assertEquals(4, store.listEvents(run.runId()).size());
        assertTrue(store.listArtifacts(run.runId()).stream()
                .anyMatch(artifact -> artifact.artifactType().equals("FINAL_REPORT")));
    }

    @Test
    void persistsNotOkDecisionWithRetryRole() {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        AiReviewModelPort model = request -> AiReviewModelPort.ModelResponse.available("model-a", """
                {"decision":"NOT_OK","score":42,"summary":"code conflicts with requirement",
                 "retryFromRole":"CODING_AGENT","dimensions":[],
                 "findings":[{"severity":"HIGH","title":"missing update","detail":"status is not updated",
                 "sourceIds":["source-1"],"suggestion":"fix code and rerun QA"}]}
                """);

        AiReviewRun run = engine(store, model, packageWithOnePart()).review(task(), "{}", "AUTO");

        assertEquals(AiReviewRunStatus.SUCCEEDED_NOT_OK, run.status());
        assertEquals(AgentRole.CODING_AGENT.name(), run.retryFromRole());
    }

    @Test
    void providerFailureIsRetryableAndNeverFabricatesDecision() {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        AiReviewModelPort model = request -> AiReviewModelPort.ModelResponse.unavailable(
                "model-a", "PROVIDER_TIMEOUT", "timeout");

        AiReviewRun run = engine(store, model, packageWithOnePart()).review(task(), "{}", "AUTO");

        assertEquals(AiReviewRunStatus.FAILED_RETRYABLE, run.status());
        assertEquals("PROVIDER_TIMEOUT", run.errorCategory());
        assertNull(run.decision());
    }

    private AiDeliveryReviewEngine engine(
            InMemoryAiReviewRunStore store,
            AiReviewModelPort model,
            AiReviewPackage reviewPackage
    ) {
        AtomicInteger ids = new AtomicInteger();
        return new AiDeliveryReviewEngine(
                store,
                (task, review) -> reviewPackage,
                model,
                new AiReviewResultValidator(),
                () -> "id-" + ids.incrementAndGet(),
                () -> 1_000L + ids.get(),
                "model-a"
        );
    }

    private AiReviewPackage packageWithOnePart() {
        AiReviewSource source = source();
        return new AiReviewPackage("task-1", List.of(source),
                List.of(new AiReviewPart(1, List.of(source.sourceId()), "part-1", 6)),
                6, 0, "sha256:package");
    }

    private AiReviewPackage packageWithTwoParts() {
        AiReviewSource source = source();
        return new AiReviewPackage("task-1", List.of(source), List.of(
                new AiReviewPart(1, List.of(source.sourceId()), "part-1", 6),
                new AiReviewPart(2, List.of(source.sourceId()), "part-2", 6)
        ), 12, 0, "sha256:package");
    }

    private AiReviewSource source() {
        return new AiReviewSource("source-1", "QA", AgentRole.QA_AGENT.name(), "RESULT_JSON",
                "sha256:source", "qa passed", "{}", 100L);
    }

    private RdRequirementTask task() {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.VALIDATING,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", "{}", "", "", 10L, 20L, false);
    }
}
