package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocImportCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocumentSnapshot;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;

class FeishuDocKnowledgeImporterTest {

    @Test
    void shouldImportMockFeishuDocumentAndSkipDuplicateRevision() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory(generatorWithMovingClock());
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("RD 文档", "飞书导入"));
        FeishuDocumentClient client = source -> new FeishuDocumentSnapshot(
                "W7bzwwbAciPkqZkECSXc146znfb",
                "https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb",
                "P0 知识库生产化",
                "2",
                "# P0\n知识库生产化与 PostgreSQL 持久化。",
                1_780_000_000_000L
        );
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace.mutations(), client);

        KnowledgeDocument first = importer.importDocument(new FeishuDocImportCommand(
                base.id(),
                "https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb",
                "product-plan",
                600,
                60
        ));
        KnowledgeDocument duplicate = importer.importDocument(new FeishuDocImportCommand(
                base.id(),
                "https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb",
                "product-plan",
                600,
                60
        ));

        assertEquals(first.id(), duplicate.id());
        assertEquals(1, workspace.listDocuments(base.id()).size());
        assertEquals("FEISHU", first.sourceType());
        assertEquals("2", first.revisionId());
        assertTrue(workspace.previewDocument(first.id()).contains("PostgreSQL"));
    }

    private SnowflakeIdGenerator generatorWithMovingClock() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
