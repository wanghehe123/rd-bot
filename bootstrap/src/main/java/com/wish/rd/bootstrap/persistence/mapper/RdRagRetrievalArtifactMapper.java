package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalArtifactRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RdRagRetrievalArtifactMapper extends BaseMapper<RdRagRetrievalArtifactRow> {

    @Insert("""
            INSERT INTO rd_rag_retrieval_artifacts (
                id, run_id, step_id, artifact_type, artifact_uri, content_preview, content_hash,
                metadata_json, redacted, created_at
            ) VALUES (
                #{id}, #{runId}, #{stepId}, #{artifactType}, #{artifactUri}, #{contentPreview}, #{contentHash},
                #{metadataJson}::jsonb, #{redacted}, #{createdAt}
            )
            """)
    void insertArtifact(RdRagRetrievalArtifactRow row);
}
