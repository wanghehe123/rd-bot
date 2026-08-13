package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocumentSnapshot;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.model.KnowledgeRefreshMetric;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;

class KnowledgeRefreshSchedulerTest {

    @Test
    void shouldPublishMetricWhenDueFeishuDocumentRefreshSucceeds() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory(generatorWithMovingClock());
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("RD 文档", "飞书导入"));
        KnowledgeDocument document = dueFeishuDocument(workspace, base.id(), "1");
        RecordingMetricSink sink = new RecordingMetricSink();
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace.mutations(), source -> new FeishuDocumentSnapshot(
                "W7bzwwbAciPkqZkECSXc146znfb",
                "https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb",
                "P0 知识库生产化",
                "2",
                "# P0\n知识库生产化与 PostgreSQL 持久化。",
                1_780_000_000_000L
        ));
        KnowledgeRefreshScheduler scheduler = new KnowledgeRefreshScheduler(workspace, importer, sink);

        List<KnowledgeDocument> refreshed = scheduler.refreshDue(2_000L, 10);

        assertEquals(1, refreshed.size());
        assertEquals(1, sink.metrics().size());
        KnowledgeRefreshMetric metric = sink.metrics().getFirst();
        assertTrue(metric.success());
        assertEquals(document.id(), metric.documentId());
        assertEquals("FEISHU", metric.sourceType());
        assertFalse(metric.sourceName().isBlank());
    }

    @Test
    void shouldPublishMetricAndContinueWhenRefreshFails() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory(generatorWithMovingClock());
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("RD 文档", "飞书导入"));
        KnowledgeDocument document = dueFeishuDocument(workspace, base.id(), "1");
        RecordingMetricSink sink = new RecordingMetricSink();
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace.mutations(), source -> {
            throw new IllegalStateException("feishu unavailable");
        });
        KnowledgeRefreshScheduler scheduler = new KnowledgeRefreshScheduler(workspace, importer, sink);

        List<KnowledgeDocument> refreshed = scheduler.refreshDue(2_000L, 10);

        assertEquals(List.of(document), refreshed);
        assertEquals(1, sink.metrics().size());
        KnowledgeRefreshMetric metric = sink.metrics().getFirst();
        assertFalse(metric.success());
        assertEquals("feishu unavailable", metric.errorMessage());
    }

    private static KnowledgeDocument dueFeishuDocument(KnowledgeWorkspace workspace, String knowledgeBaseId, String revision) {
        return workspace.writeDocument(
                new WriteKnowledgeDocumentCommand(
                        knowledgeBaseId,
                        "P0 知识库生产化",
                        "product-plan",
                        "text/markdown",
                        "# old\ncontent".getBytes(StandardCharsets.UTF_8),
                        ChunkingMode.STRUCTURE_AWARE,
                        512,
                        64
                ),
                new KnowledgeDocumentSource(
                        "FEISHU",
                        "W7bzwwbAciPkqZkECSXc146znfb",
                        "https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb",
                        revision,
                        1_000L,
                        1_000L
                )
        );
    }

    private static SnowflakeIdGenerator generatorWithMovingClock() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private static final class RecordingMetricSink implements KnowledgeRefreshMetricSink {

        private final List<KnowledgeRefreshMetric> metrics = new ArrayList<>();

        @Override
        public void publish(KnowledgeRefreshMetric metric) {
            metrics.add(metric);
        }

        private List<KnowledgeRefreshMetric> metrics() {
            return List.copyOf(metrics);
        }
    }
}
