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
                checksum, raw_preview, raw_content, last_synced_at, next_refresh_at, created_at, updated_at,
                sync_version, current_revision_id, source_identity_key, deleted_at, purge_after,
                superseded_by_document_id, row_version, local_only_override
            )
            VALUES (
                #{id}, #{knowledgeBaseId}, #{sourceName}, #{knowledgeType}, #{mimeType}, #{status}, #{enabled},
                #{chunkCount}, #{nodeLogsJson}::jsonb, #{sourceType}, #{sourceToken}, #{sourceUrl}, #{revisionId},
                #{checksum}, #{rawPreview}, #{rawContent}, #{lastSyncedAt}, #{nextRefreshAt}, #{createdAt}, #{updatedAt},
                #{syncVersion}, #{currentRevisionId}, #{sourceIdentityKey}, #{deletedAt}, #{purgeAfter},
                #{supersededByDocumentId}, #{rowVersion}, #{localOnlyOverride}
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
                updated_at = EXCLUDED.updated_at,
                sync_version = EXCLUDED.sync_version,
                current_revision_id = EXCLUDED.current_revision_id,
                source_identity_key = EXCLUDED.source_identity_key,
                deleted_at = EXCLUDED.deleted_at,
                purge_after = EXCLUDED.purge_after,
                superseded_by_document_id = EXCLUDED.superseded_by_document_id,
                row_version = EXCLUDED.row_version,
                local_only_override = EXCLUDED.local_only_override
            """)
    void upsert(KnowledgeDocumentRow row);
}
