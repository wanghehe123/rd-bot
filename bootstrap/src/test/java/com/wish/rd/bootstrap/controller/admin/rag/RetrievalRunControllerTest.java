package com.wish.rd.bootstrap.controller.admin.rag;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.wish.rd.bootstrap.rag.RetrievalRunConfiguration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRunControllerTest {

    @Test
    void registersArtifactEndpointWhenTheFallbackStoreIsCreatedByConfiguration() {
        new ApplicationContextRunner()
                .withBean(SnowflakeIdGenerator.class, SnowflakeIdGenerator::defaultGenerator)
                .withUserConfiguration(RetrievalArtifactController.class, RetrievalRunConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(RetrievalArtifactController.class));
    }

    @Test
    void exposesTaskRunsTimelineAndCreatesAnImmutableRetryAttempt() throws Exception {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(store, () -> "100" + ids.incrementAndGet(), () -> 100L);
        var run = lifecycle.start("9001", RetrievalConsumerType.BUG_FIX, "", "", "payment timeout", List.of("1"));
        lifecycle.waitForInput(run.runId(), "need logs");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RetrievalRunController(store, () -> "200" + ids.incrementAndGet())
        ).build();

        mockMvc.perform(get("/admin/rd-tasks/9001/retrieval-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("WAITING_INPUT"));
        mockMvc.perform(get("/admin/rag-retrieval-runs/{runId}/timeline", run.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toStatus").value("PLANNING"));
        mockMvc.perform(post("/admin/rag-retrieval-runs/{runId}/retry", run.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.parentRunId").value(run.runId()));
    }

    @Test
    void retryIsIdempotentOnDoubleClick() throws Exception {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(store, () -> "100" + ids.incrementAndGet(), () -> 100L);
        var run = lifecycle.start("9001", RetrievalConsumerType.BUG_FIX, "", "", "payment timeout", List.of("1"));
        lifecycle.waitForInput(run.runId(), "need logs");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RetrievalRunController(store, () -> "200" + ids.incrementAndGet())
        ).build();

        String firstBody = mockMvc.perform(post("/admin/rag-retrieval-runs/{runId}/retry", run.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secondBody = mockMvc.perform(post("/admin/rag-retrieval-runs/{runId}/retry", run.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.parentRunId").value(run.runId()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(firstBody, secondBody);
        org.junit.jupiter.api.Assertions.assertEquals(2, store.listByTask("9001").size());
    }

    @Test
    void exposesRedactedRetrievalEvidenceArtifacts() throws Exception {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(store, () -> "100" + ids.incrementAndGet(), () -> 100L);
        var run = lifecycle.start("9001", RetrievalConsumerType.BUG_FIX, "", "", "payment timeout", List.of("1"));
        lifecycle.appendArtifact(
                run.runId(), "SELECTED_EVIDENCE", "rag://retrieval/evidence",
                "chunkId=order-1; content=payment timeout is handled by OrderService", "sha256:evidence"
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RetrievalArtifactController(store)).build();

        mockMvc.perform(get("/admin/rag-retrieval-runs/{runId}/artifacts", run.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].artifactType").value("SELECTED_EVIDENCE"))
                .andExpect(jsonPath("$[1].contentPreview").value(org.hamcrest.Matchers.containsString("OrderService")))
                .andExpect(jsonPath("$[1].redacted").value(true));
    }
}
