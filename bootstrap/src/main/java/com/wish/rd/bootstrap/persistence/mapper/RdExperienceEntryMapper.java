package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdExperienceEntryRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作流经验条目 MyBatis-Plus mapper。
 */
@Mapper
public interface RdExperienceEntryMapper extends BaseMapper<RdExperienceEntryRow> {

    /**
     * 插入或更新经验条目。
     *
     * @param row 经验条目行
     */
    @Insert("""
            INSERT INTO rd_experience_entries (
                id, task_id, stage_run_id, source_artifact_id, role, experience_type,
                title, summary, content_json, content_hash, reusable, failure,
                redacted, project_id, repository_fingerprint, intent_id, tags_json,
                source_revision, evidence_quality, applicable_roles_json,
                ingestion_task_id, created_at
            )
            VALUES (
                #{id}, #{taskId}, #{stageRunId}, #{sourceArtifactId}, #{role}, #{experienceType},
                #{title}, #{summary}, #{contentJson}::jsonb, #{contentHash}, #{reusable}, #{failure},
                #{redacted}, #{projectId}, #{repositoryFingerprint}, #{intentId}, #{tagsJson}::jsonb,
                #{sourceRevision}, #{evidenceQuality}, #{applicableRolesJson}::jsonb,
                #{ingestionTaskId}, #{createdAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                task_id = EXCLUDED.task_id,
                stage_run_id = EXCLUDED.stage_run_id,
                source_artifact_id = EXCLUDED.source_artifact_id,
                role = EXCLUDED.role,
                experience_type = EXCLUDED.experience_type,
                title = EXCLUDED.title,
                summary = EXCLUDED.summary,
                content_json = EXCLUDED.content_json,
                content_hash = EXCLUDED.content_hash,
                reusable = EXCLUDED.reusable,
                failure = EXCLUDED.failure,
                redacted = EXCLUDED.redacted,
                project_id = EXCLUDED.project_id,
                repository_fingerprint = EXCLUDED.repository_fingerprint,
                intent_id = EXCLUDED.intent_id,
                tags_json = EXCLUDED.tags_json,
                source_revision = EXCLUDED.source_revision,
                evidence_quality = EXCLUDED.evidence_quality,
                applicable_roles_json = EXCLUDED.applicable_roles_json,
                ingestion_task_id = EXCLUDED.ingestion_task_id
            """)
    void upsertExperienceEntry(RdExperienceEntryRow row);
}
