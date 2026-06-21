package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentRow> {

    @Insert("""
            INSERT INTO knowledge_documents (
                id, knowledge_base_id, source_name, knowledge_type, mime_type, status, enabled,
                chunk_count, node_logs_json, source_type, source_token, source_url, revision_id,
                checksum, raw_preview, raw_content, last_synced_at, next_refresh_at, created_at, updated_at
            )
            VALUES (
                #{id}, #{knowledgeBaseId}, #{sourceName}, #{knowledgeType}, #{mimeType}, #{status}, #{enabled},
                #{chunkCount}, #{nodeLogsJson}::jsonb, #{sourceType}, #{sourceToken}, #{sourceUrl}, #{revisionId},
                #{checksum}, #{rawPreview}, #{rawContent}, #{lastSyncedAt}, #{nextRefreshAt}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                source_name = EXCLUDED.source_name,
                knowledge_type = EXCLUDED.knowledge_type,
                mime_type = EXCLUDED.mime_type,
                status = EXCLUDED.status,
                enabled = EXCLUDED.enabled,
                chunk_count = EXCLUDED.chunk_count,
                node_logs_json = EXCLUDED.node_logs_json,
                source_type = EXCLUDED.source_type,
                source_token = EXCLUDED.source_token,
                source_url = EXCLUDED.source_url,
                revision_id = EXCLUDED.revision_id,
                checksum = EXCLUDED.checksum,
                raw_preview = EXCLUDED.raw_preview,
                raw_content = COALESCE(EXCLUDED.raw_content, knowledge_documents.raw_content),
                last_synced_at = EXCLUDED.last_synced_at,
                next_refresh_at = EXCLUDED.next_refresh_at,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(KnowledgeDocumentRow row);
}
