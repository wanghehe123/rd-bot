package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.AgentPrivateArtifactRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** Mapper for restricted Pi object metadata. */
@Mapper
public interface AgentPrivateArtifactMapper extends BaseMapper<AgentPrivateArtifactRow> {

    @Insert("""
            INSERT INTO rd_agent_private_artifacts (
                artifact_id, task_id, stage_run_id, snapshot_id, artifact_type,
                artifact_name, artifact_uri, bytes, sha256, content_type,
                retention_class, created_at, expires_at
            ) VALUES (
                #{artifactId}, #{taskId}, #{stageRunId}, #{snapshotId}, #{artifactType},
                #{artifactName}, #{artifactUri}, #{bytes}, #{sha256}, #{contentType},
                #{retentionClass}, #{createdAt}, #{expiresAt}
            )
            ON CONFLICT (artifact_id) DO UPDATE SET
                task_id = EXCLUDED.task_id,
                stage_run_id = EXCLUDED.stage_run_id,
                snapshot_id = EXCLUDED.snapshot_id,
                artifact_type = EXCLUDED.artifact_type,
                artifact_name = EXCLUDED.artifact_name,
                artifact_uri = EXCLUDED.artifact_uri,
                bytes = EXCLUDED.bytes,
                sha256 = EXCLUDED.sha256,
                content_type = EXCLUDED.content_type,
                retention_class = EXCLUDED.retention_class,
                created_at = EXCLUDED.created_at,
                expires_at = EXCLUDED.expires_at
            """)
    void upsert(AgentPrivateArtifactRow row);
}
