package com.wish.rd.engine.retrieval;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.impl.KnowledgeRetrievalModeRouter;
import com.wish.rd.engine.retrieval.model.ChannelAudit;
import com.wish.rd.engine.retrieval.model.KnowledgeProviderMode;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.retrieval.navigator.ExternalKnowledgeNavigatorPort;
import com.wish.rd.rag.retrieval.navigator.KnowledgeEvidenceAllowlist;
import com.wish.rd.rag.retrieval.navigator.ThreeTierNavigationEngine;
import com.wish.rd.rag.retrieval.navigator.UntrustedKnowledgeFence;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorDocument;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorQuery;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorSettings;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 读路径模式路由的契约：LOCAL 零远端、SHADOW 不改返回值且不阻塞、OPENVIKING 失败显式降级。
 */
class KnowledgeRetrievalModeRouterTest {

    private static final String KB = "7475766492030701568";
    private static final String DOC = "7474332749017518080";

    @Test
    void localModeDelegatesAndNeverTouchesTheNavigator() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(expected);

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.LOCAL);
        SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);

        assertSame(expected, actual);
        verify(local).search(any(), any(), any(), any(), anyInt());
        verifyNoInteractions(navigator);
    }

    @Test
    void localModeResolveScopeNeverTouchesTheNavigator() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        RetrievalScope expected = scoped();
        when(local.resolveScope(any())).thenReturn(expected);

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.LOCAL);

        assertSame(expected, router.resolveScope(task()));
        verifyNoInteractions(navigator);
    }

    @Test
    void shadowModeReturnsTheExactLocalResultWhenTheNavigatorSucceeds() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(expected);
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenReturn(ExternalNavigatorSearch.of(List.of()));

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.SHADOW);
        SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);

        assertSame(expected, actual);
        verify(navigator, timeout(1_000)).searchAbstracts(argThat(query ->
                "保存商品".equals(query.query()) && query.knowledgeBaseIds().equals(List.of(KB))));
    }

    @Test
    void shadowModeReturnsTheExactLocalResultWhenTheRemoteReturnsRichHits() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(expected);
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenReturn(ExternalNavigatorSearch.of(List.of(
                new ExternalNavigatorSearch.Hit(root(), 0, 0.98, "丰富命中", List.of("tag"))
        )));
        when(navigator.readOverview(any())).thenReturn(ExternalNavigatorDocument.of(root(), "保存商品 overview"));

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.SHADOW);
        SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);

        assertSame(expected, actual);
        verify(navigator, timeout(1_000)).searchAbstracts(any(ExternalNavigatorQuery.class));
    }

    @Test
    void shadowModeReturnsTheExactLocalResultWhenTheNavigatorThrows() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(expected);
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenThrow(new IllegalStateException("remote exploded"));

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.SHADOW);
        SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);

        assertSame(expected, actual);
        verify(navigator, timeout(1_000)).searchAbstracts(any(ExternalNavigatorQuery.class));
    }

    @Test
    void shadowModeReturnsTheExactLocalResultWithoutWaitingWhenTheNavigatorTimesOut() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(expected);
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenAnswer(invocation -> {
            Thread.sleep(2_000L);
            return ExternalNavigatorSearch.of(List.of());
        });

        Wired wired = wired(local, navigator, KnowledgeProviderMode.SHADOW, Duration.ofMillis(50));
        long startedAt = System.nanoTime();
        SearchResult actual = wired.router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertSame(expected, actual);
        assertTrue(elapsedMillis < 400L, "shadow timeout must not extend the caller critical path: " + elapsedMillis);
        verify(navigator, timeout(500)).searchAbstracts(any(ExternalNavigatorQuery.class));
    }

    @Test
    void shadowModeDoesNotBlockOnAnInFlightProbe() throws InterruptedException {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(expected);
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return ExternalNavigatorSearch.of(List.of());
        });

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.SHADOW);
        long startedAt = System.nanoTime();
        SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertSame(expected, actual);
        assertTrue(elapsedMillis < 400L, "in-flight probe must not block the caller: " + elapsedMillis);
        assertTrue(entered.await(1, TimeUnit.SECONDS), "probe should have started");
        release.countDown();
    }

    @Test
    void shadowProbeRecordsAllowlistRejectionTallyOnTheExistingRun() throws InterruptedException {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(expected);
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenReturn(ExternalNavigatorSearch.of(List.of(
                new ExternalNavigatorSearch.Hit(root(), 0, 0.9, "无绑定命中", List.of())
        )));

        Wired wired = wired(local, navigator, KnowledgeProviderMode.SHADOW, Duration.ofSeconds(20));
        assertSame(expected, wired.router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8));

        org.mockito.Mockito.verify(navigator, timeout(1_000)).searchAbstracts(any());
        List<RetrievalRunArtifact> artifacts = awaitArtifacts(wired);
        assertTrue(artifacts.stream().anyMatch(artifact ->
                "NAVIGATOR_ALLOWLIST".equals(artifact.artifactType())
                        && artifact.contentPreview().contains("NO_BINDING=1")),
                () -> artifacts.toString());
    }

    @Test
    void emptyScopeKeepsMissingScopeAndIssuesNoRemoteCallInEveryMode() {
        for (KnowledgeProviderMode mode : KnowledgeProviderMode.values()) {
            RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
            ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
            RetrievalScope empty = new RetrievalScope(
                    List.of(), "github.com/example/waimai", true, "project has no enabled knowledge-base binding");
            SearchResult missing = new SearchResult(
                    List.of(),
                    List.of(new ChannelAudit("ProjectScope", true, 0, "MISSING_SCOPE", empty.missingReason()))
            );
            when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(missing);

            KnowledgeRetrievalModeRouter router = router(local, navigator, mode);
            SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", empty, 8);

            assertEquals(missing, actual);
            assertEquals("MISSING_SCOPE", actual.channels().getFirst().errorCategory());
            verify(navigator, never()).searchAbstracts(any());
            verify(navigator, never()).readOverview(any());
            verify(navigator, never()).readContent(any(), anyInt(), anyInt());
            verify(navigator, never()).ready();
        }
    }

    @Test
    void openVikingModeFallsBackToLocalAndRecordsDegradedWhenRemoteIsUnavailable() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult localResult = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(localResult);
        when(navigator.ready()).thenReturn(false);

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.OPENVIKING);
        SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);

        assertEquals(localResult.candidates(), actual.candidates());
        assertTrue(actual.channels().stream().anyMatch(channel ->
                "OpenViking".equals(channel.channel())
                        && "DEGRADED".equals(channel.errorCategory())
                        && channel.errorMessage().contains("REMOTE_UNAVAILABLE")));
        verify(navigator).ready();
        verify(navigator, never()).searchAbstracts(any());
    }

    @Test
    void openVikingModeFallsBackToLocalWhenTheNavigatorThrows() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult localResult = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(localResult);
        when(navigator.ready()).thenThrow(new IllegalStateException("ready exploded"));

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.OPENVIKING);
        SearchResult actual = router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);

        assertEquals(localResult.candidates(), actual.candidates());
        assertTrue(actual.channels().stream().anyMatch(channel -> "DEGRADED".equals(channel.errorCategory())));
    }

    @Test
    void openVikingModeUsesAdmittedRemoteEvidenceInsteadOfLocalCandidates() {
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult localResult = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenReturn(localResult);
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenReturn(ExternalNavigatorSearch.of(List.of(
                new ExternalNavigatorSearch.Hit(root(), 0, 0.97, "保存商品摘要", List.of())
        )));
        when(navigator.readOverview(any())).thenReturn(
                ExternalNavigatorDocument.of(root(), "管理员可以保存商品"));

        Wired wired = wired(local, navigator, KnowledgeProviderMode.OPENVIKING, Duration.ofSeconds(20));
        wired.seedAdmittedDocument();
        SearchResult actual = wired.router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8);

        assertNotEquals(localResult.candidates(), actual.candidates());
        assertFalse(actual.candidates().isEmpty());
        assertTrue(UntrustedKnowledgeFence.isFullyFenced(actual.candidates().getFirst().summary()));
        assertTrue(actual.channels().stream().noneMatch(channel -> "DEGRADED".equals(channel.errorCategory())));
        verify(local, never()).search(any(), any(), any(), any(), anyInt());
    }

    @Test
    void invalidModeValueIsRejectedAndListsLegalValues() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> KnowledgeProviderMode.parse("VECTOR"));
        assertTrue(thrown.getMessage().contains("LOCAL"));
        assertTrue(thrown.getMessage().contains("SHADOW"));
        assertTrue(thrown.getMessage().contains("OPENVIKING"));
        assertTrue(thrown.getMessage().contains("VECTOR"));
    }

    @Test
    void blankModeDefaultsToLocal() {
        assertEquals(KnowledgeProviderMode.LOCAL, KnowledgeProviderMode.parse(""));
        assertEquals(KnowledgeProviderMode.LOCAL, KnowledgeProviderMode.parse("  "));
        assertEquals(KnowledgeProviderMode.LOCAL, KnowledgeProviderMode.parse(null));
        assertEquals(KnowledgeProviderMode.LOCAL, KnowledgeProviderMode.parse("local"));
    }

    @Test
    void shadowProbeMustNotChangeLocalCallCount() {
        AtomicInteger localCalls = new AtomicInteger();
        RequirementKnowledgeSearchPort local = mock(RequirementKnowledgeSearchPort.class);
        ExternalKnowledgeNavigatorPort navigator = mock(ExternalKnowledgeNavigatorPort.class);
        SearchResult expected = sampleResult();
        when(local.search(any(), any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            localCalls.incrementAndGet();
            return expected;
        });
        when(navigator.ready()).thenReturn(true);
        when(navigator.searchAbstracts(any())).thenReturn(ExternalNavigatorSearch.of(List.of()));

        KnowledgeRetrievalModeRouter router = router(local, navigator, KnowledgeProviderMode.SHADOW);
        assertSame(expected, router.search(task(), AgentRole.CODING_AGENT, "保存商品", scoped(), 8));
        assertEquals(1, localCalls.get());
    }

    private static List<RetrievalRunArtifact> awaitArtifacts(Wired wired) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        List<RetrievalRunArtifact> artifacts = List.of();
        while (System.nanoTime() < deadline) {
            artifacts = wired.store.listArtifacts(wired.run.runId());
            if (artifacts.stream().anyMatch(artifact -> "NAVIGATOR_ALLOWLIST".equals(artifact.artifactType()))) {
                return artifacts;
            }
            Thread.sleep(10L);
        }
        return artifacts;
    }

    private static KnowledgeRetrievalModeRouter router(
            RequirementKnowledgeSearchPort local,
            ExternalKnowledgeNavigatorPort navigator,
            KnowledgeProviderMode mode
    ) {
        return wired(local, navigator, mode, Duration.ofSeconds(20)).router;
    }

    private static Wired wired(
            RequirementKnowledgeSearchPort local,
            ExternalKnowledgeNavigatorPort navigator,
            KnowledgeProviderMode mode,
            Duration shadowBudget
    ) {
        return new Wired(local, navigator, mode, shadowBudget);
    }

    private static RetrievalScope scoped() {
        return new RetrievalScope(List.of(KB), "github.com/example/waimai", true, "");
    }

    private static String root() {
        return OpenVikingProjectionUris.documentRootUri(KB, DOC);
    }

    private static SearchResult sampleResult() {
        return new SearchResult(
                List.of(new RoleContextEvidence(
                        "chunk-1", "CODE", "knowledge://" + KB + "/doc#1", "save", "sha256:chunk-1",
                        "商品保存", 100L, "project-scoped RRF rank=1", 1.0d, "CODE_SYMBOL", false)),
                List.of(new ChannelAudit("VectorSearch", false, 1, "", ""))
        );
    }

    private static RdRequirementTask task() {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "manual", "", "", "P1", null, "商品管理",
                "project-1", "", "", "https://github.com/example/waimai", "example", "waimai",
                "main", "", "管理员可以保存商品", "[]", "", "", "", "", 100L, 100L,
                false, 0L, 1L, 1L, null);
    }

    private static final class Wired {
        final InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        final RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(store, () -> "id-" + System.nanoTime(), () -> 1L);
        final InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        final RetrievalRun run;
        final KnowledgeRetrievalModeRouter router;

        private Wired(
                RequirementKnowledgeSearchPort local,
                ExternalKnowledgeNavigatorPort navigator,
                KnowledgeProviderMode mode,
                Duration shadowBudget
        ) {
            this.run = lifecycle.start(
                    "task-1", RetrievalConsumerType.AGENT_ROLE, "CODING_AGENT", "stage-1",
                    "保存商品", List.of(KB));
            ThreeTierNavigationEngine engine = new ThreeTierNavigationEngine(
                    navigator,
                    new KnowledgeEvidenceAllowlist(bindings, documents),
                    NavigatorSettings.defaults(),
                    lifecycle
            );
            this.router = new KnowledgeRetrievalModeRouter(local, mode, shadowBudget, engine, lifecycle);
        }

        private void seedAdmittedDocument() {
            documents.save(new KnowledgeDocument(
                    DOC, KB, "doc.md", "api", "text/markdown", KnowledgeDocumentStatus.INDEXED,
                    true, 1, List.of(), 1L, "LOCAL", "", "", "", "ck", "preview", 1L, 0L, 1L, "", "",
                    0L, 0L, "", 0L, false
            ), "body");
            bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    DOC,
                    KB,
                    root(),
                    OpenVikingProjectionUris.ownershipMarker(KB, DOC),
                    ExternalKnowledgeDesiredState.PRESENT,
                    1L,
                    "ck",
                    ExternalKnowledgeObservedState.READY,
                    1L,
                    "ck",
                    ExternalKnowledgeProjectionStatus.IN_SYNC,
                    "",
                    "",
                    "",
                    1L,
                    1L,
                    "",
                    "",
                    0L,
                    1L,
                    1L
            ));
        }
    }
}
