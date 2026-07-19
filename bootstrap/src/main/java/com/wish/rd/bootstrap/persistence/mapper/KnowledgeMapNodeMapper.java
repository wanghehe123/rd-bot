package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeMapNodeRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeMapNodeMapper extends BaseMapper<KnowledgeMapNodeRow> {

    @Delete("DELETE FROM knowledge_map_nodes WHERE knowledge_base_id = #{knowledgeBaseId}")
    int deleteByKnowledgeBaseId(long knowledgeBaseId);

    @Insert("""
            INSERT INTO knowledge_map_nodes (
                id, knowledge_base_id, parent_id, node_type, path, title, summary, document_id,
                chunk_start, chunk_end, revision_id, checksum, token_estimate, metadata_json, enabled
            ) VALUES (
                #{id}, #{knowledgeBaseId}, #{parentId}, #{nodeType}, #{path}, #{title}, #{summary}, #{documentId},
                #{chunkStart}, #{chunkEnd}, #{revisionId}, #{checksum}, #{tokenEstimate}, #{metadataJson}::jsonb, #{enabled}
            )
            """)
    void insertNode(KnowledgeMapNodeRow row);
}
