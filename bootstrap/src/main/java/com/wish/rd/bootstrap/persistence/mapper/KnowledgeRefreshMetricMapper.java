package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeRefreshMetricRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeRefreshMetricMapper extends BaseMapper<KnowledgeRefreshMetricRow> {

    @Insert("""
            INSERT INTO knowledge_refresh_metrics (
                document_id, knowledge_base_id, source_type, source_name, success,
                old_chunk_count, new_chunk_count, duration_ms, error_message,
                metadata_json, created_at
            )
            VALUES (
                #{documentId}, #{knowledgeBaseId}, #{sourceType}, #{sourceName}, #{success},
                #{oldChunkCount}, #{newChunkCount}, #{durationMs}, #{errorMessage},
                #{metadataJson}::jsonb, #{createdAt}
            )
            """)
    void insertMetric(KnowledgeRefreshMetricRow row);
}
