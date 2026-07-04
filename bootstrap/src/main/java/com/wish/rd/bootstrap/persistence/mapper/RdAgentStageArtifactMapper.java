package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageArtifactRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 阶段产物 MyBatis-Plus mapper。
 */
@Mapper
public interface RdAgentStageArtifactMapper extends BaseMapper<RdAgentStageArtifactRow> {

    /**
     * 插入或更新阶段产物。
     *
     * @param row 阶段产物行
     */
    @Insert("""
            INSERT INTO rd_agent_stage_artifacts (
                id, stage_run_id, task_id, role, artifact_type, artifact_uri,
                summary, content_preview, content_hash, metadata_json, created_at
            )
            VALUES (
                #{id}, #{stageRunId}, #{taskId}, #{role}, #{artifactType}, #{artifactUri},
                #{summary}, #{contentPreview}, #{contentHash}, #{metadataJson}::jsonb, #{createdAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                stage_run_id = EXCLUDED.stage_run_id,
                task_id = EXCLUDED.task_id,
                role = EXCLUDED.role,
                artifact_type = EXCLUDED.artifact_type,
                artifact_uri = EXCLUDED.artifact_uri,
                summary = EXCLUDED.summary,
                content_preview = EXCLUDED.content_preview,
                content_hash = EXCLUDED.content_hash,
                metadata_json = EXCLUDED.metadata_json
            """)
    void upsertStageArtifact(RdAgentStageArtifactRow row);
}
