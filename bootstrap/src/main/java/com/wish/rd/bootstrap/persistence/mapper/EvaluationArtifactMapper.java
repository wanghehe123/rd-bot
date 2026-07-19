package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.EvaluationArtifactRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for evaluation artifact metadata. */
@Mapper
public interface EvaluationArtifactMapper extends BaseMapper<EvaluationArtifactRow> {
    @Insert("""
            INSERT INTO rd_evaluation_artifacts (
                id, run_id, artifact_type, artifact_uri, content_preview,
                content_hash, size_bytes, created_at
            ) VALUES (
                #{id}, #{runId}, #{artifactType}, #{artifactUri}, #{contentPreview},
                #{contentHash}, #{sizeBytes}, #{createdAt}
            )
            """)
    void insertArtifact(EvaluationArtifactRow row);
}
