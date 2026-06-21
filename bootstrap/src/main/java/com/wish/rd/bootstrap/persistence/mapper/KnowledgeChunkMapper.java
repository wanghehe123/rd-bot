package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeChunkRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkRow> {

    @Insert("""
            INSERT INTO knowledge_chunks (
                id, document_id, knowledge_base_id, chunk_index, content, content_hash,
                knowledge_type, source_name, enabled, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{documentId}, #{knowledgeBaseId}, #{chunkIndex}, #{content}, #{contentHash},
                #{knowledgeType}, #{sourceName}, #{enabled}, #{metadataJson}::jsonb, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                chunk_index = EXCLUDED.chunk_index,
                content = EXCLUDED.content,
                content_hash = EXCLUDED.content_hash,
                knowledge_type = EXCLUDED.knowledge_type,
                source_name = EXCLUDED.source_name,
                enabled = EXCLUDED.enabled,
                metadata_json = EXCLUDED.metadata_json,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(KnowledgeChunkRow row);
}
