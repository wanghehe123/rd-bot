package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdQaEvidenceObjectRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** Mapper for private QA evidence object metadata. */
@Mapper
public interface RdQaEvidenceObjectMapper extends BaseMapper<RdQaEvidenceObjectRow> {

    @Insert("""
            INSERT INTO rd_qa_evidence_objects (
                task_id, stage_run_id, artifact_id, artifact_type, artifact_name,
                object_uri, content_type, size_bytes, sha256, created_at, expires_at
            ) VALUES (
                #{taskId}, #{stageRunId}, #{artifactId}, #{artifactType}, #{artifactName},
                #{objectUri}, #{contentType}, #{sizeBytes}, #{sha256}, #{createdAt}, #{expiresAt}
            )
            ON CONFLICT (task_id, stage_run_id, artifact_id) DO UPDATE SET
                artifact_type = EXCLUDED.artifact_type,
                artifact_name = EXCLUDED.artifact_name,
                object_uri = EXCLUDED.object_uri,
                content_type = EXCLUDED.content_type,
                size_bytes = EXCLUDED.size_bytes,
                sha256 = EXCLUDED.sha256,
                expires_at = EXCLUDED.expires_at
            """)
    void upsertEvidenceObject(RdQaEvidenceObjectRow row);
}
