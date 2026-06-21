package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.IngestionTaskRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IngestionTaskMapper extends BaseMapper<IngestionTaskRow> {

    @Insert("""
            INSERT INTO ingestion_tasks (
                id, pipeline_id, knowledge_base_id, document_id, source_type, source_location,
                source_file_name, status, chunk_count, error_message, metadata_json,
                started_at, completed_at, created_by, created_at, updated_at
            )
            VALUES (
                #{id}, #{pipelineId}, #{knowledgeBaseId}, #{documentId}, #{sourceType}, #{sourceLocation},
                #{sourceFileName}, #{status}, #{chunkCount}, #{errorMessage}, #{metadataJson}::jsonb,
                #{startedAt}, #{completedAt}, #{createdBy}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                document_id = EXCLUDED.document_id,
                status = EXCLUDED.status,
                chunk_count = EXCLUDED.chunk_count,
                error_message = EXCLUDED.error_message,
                metadata_json = EXCLUDED.metadata_json,
                completed_at = EXCLUDED.completed_at,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(IngestionTaskRow row);
}
