package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.AiReviewArtifactRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for redacted AI review artifacts. */
@Mapper
public interface AiReviewArtifactMapper extends BaseMapper<AiReviewArtifactRow> {
    @Insert("""
            INSERT INTO rd_ai_review_artifacts (
                id, run_id, artifact_type, artifact_uri, content_preview,
                content_hash, metadata_json, redacted, created_at
            ) VALUES (
                #{id}, #{runId}, #{artifactType}, #{artifactUri}, #{contentPreview},
                #{contentHash}, #{metadataJson}::jsonb, #{redacted}, #{createdAt}
            )
            """)
    void insertArtifact(AiReviewArtifactRow row);
}
