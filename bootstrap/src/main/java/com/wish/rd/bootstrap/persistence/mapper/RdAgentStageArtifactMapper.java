package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageArtifactRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    /**
     * Append-only insert for immutable artifacts. Returns 1 when inserted, 0 on id conflict.
     *
     * @param row stage artifact row
     * @return affected row count
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
            ON CONFLICT (id) DO NOTHING
            """)
    int insertStageArtifactIgnoringConflict(RdAgentStageArtifactRow row);

    /**
     * Task-scoped list for workbench and recovery. Private QA evidence blobs stay in object
     * storage; pulling {@code content_preview} for every screenshot/trace over Hangzhou
     * Postgres makes {@code /execution-overview} miss the frontend 30s timeout.
     */
    @Select("""
            SELECT id, stage_run_id, task_id, role, artifact_type, artifact_uri, summary,
                   CASE WHEN artifact_type IN (
                       'QA_COMMAND_LOG',
                       'QA_CONSOLE_LOG',
                       'QA_EVIDENCE_MANIFEST',
                       'QA_HTTP_TRANSCRIPT',
                       'QA_NETWORK_LOG',
                       'QA_SCREENSHOT',
                       'QA_TRACE',
                       'QA_VIDEO'
                   ) THEN NULL ELSE content_preview END AS content_preview,
                   content_hash, metadata_json, created_at
            FROM rd_agent_stage_artifacts
            WHERE task_id = #{taskId}
            """)
    List<RdAgentStageArtifactRow> selectByTaskOmittingPrivateQaPreviews(@Param("taskId") long taskId);
}
