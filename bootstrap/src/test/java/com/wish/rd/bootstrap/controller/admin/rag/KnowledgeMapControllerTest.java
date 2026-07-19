package com.wish.rd.bootstrap.controller.admin.rag;

import com.wish.rd.bootstrap.persistence.entity.KnowledgeBaseRow;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeChunkRow;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeBaseMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeChunkMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeDocumentMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeMapNodeMapper;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeMapControllerTest {

    @Test
    void shouldRebuildMapWithoutPersistingRawDocumentOrChunkContent() {
        KnowledgeBaseMapper baseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeMapNodeMapper mapNodeMapper = mock(KnowledgeMapNodeMapper.class);
        AtomicLong ids = new AtomicLong(1_000L);
        SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(1, 1, ids::getAndIncrement);

        KnowledgeBaseRow base = new KnowledgeBaseRow();
        base.id = 9L;
        when(baseMapper.selectById(9L)).thenReturn(base);

        KnowledgeDocumentRow document = new KnowledgeDocumentRow();
        document.id = 21L;
        document.sourceName = "/secret/path/waimai-spec.md";
        document.rawPreview = "SENSITIVE_DOCUMENT_BODY_SHOULD_NOT_LEAK";
        document.chunkCount = 1;
        document.revisionId = "rev-1";
        document.checksum = "checksum-doc";
        document.enabled = true;
        when(documentMapper.selectList(any())).thenReturn(List.of(document));

        KnowledgeChunkRow chunk = new KnowledgeChunkRow();
        chunk.id = 31L;
        chunk.chunkIndex = 0;
        chunk.content = "SENSITIVE_CHUNK_BODY_SHOULD_NOT_LEAK";
        chunk.contentHash = "checksum-chunk";
        chunk.enabled = true;
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));

        KnowledgeMapController controller = new KnowledgeMapController(
                baseMapper, documentMapper, chunkMapper, mapNodeMapper, idGenerator);

        controller.rebuild("9");

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.wish.rd.bootstrap.persistence.entity.KnowledgeMapNodeRow.class);
        verify(mapNodeMapper, org.mockito.Mockito.atLeast(2)).insertNode(captor.capture());
        String joined = captor.getAllValues().stream()
                .map(row -> String.valueOf(row.title) + "|" + String.valueOf(row.summary))
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(joined.contains("SENSITIVE_DOCUMENT_BODY_SHOULD_NOT_LEAK"));
        assertFalse(joined.contains("SENSITIVE_CHUNK_BODY_SHOULD_NOT_LEAK"));
        assertFalse(joined.contains("/secret/path/"));
        assertTrue(joined.contains("waimai-spec.md") || joined.contains("文档"));
    }
}
