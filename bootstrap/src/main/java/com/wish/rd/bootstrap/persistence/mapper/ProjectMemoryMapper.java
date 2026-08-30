package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryRow;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemorySearchRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ProjectMemoryMapper extends BaseMapper<ProjectMemoryRow> {
    @Insert("""
            INSERT INTO rd_project_memories (id, project_id, scope_role, memory_type, logical_key, head_revision_id, head_version, row_version, lifecycle_status)
            VALUES (#{id}, #{projectId}, #{scopeRole}, #{memoryType}, #{logicalKey}, #{headRevisionId}, #{headVersion}, #{rowVersion}, #{lifecycleStatus})
            """)
    int insertMemory(ProjectMemoryRow row);

    @Update("""
            UPDATE rd_project_memories
            SET head_revision_id=#{revisionId}, head_version=#{version}, row_version=row_version+1
            WHERE id=#{memoryId} AND row_version=#{expectedVersion} AND lifecycle_status='ENABLED'
            """)
    int advanceHead(
            @Param("memoryId") long memoryId,
            @Param("revisionId") long revisionId,
            @Param("version") long version,
            @Param("expectedVersion") long expectedVersion
    );

    @Select("""
            SELECT * FROM rd_project_memories
            WHERE project_id=#{projectId} AND scope_role=#{scopeRole} AND memory_type=#{memoryType}
              AND logical_key=#{logicalKey} AND lifecycle_status='ENABLED'
            LIMIT 1
            """)
    ProjectMemoryRow selectByLogicalKey(
            @Param("projectId") long projectId,
            @Param("scopeRole") String scopeRole,
            @Param("memoryType") String memoryType,
            @Param("logicalKey") String logicalKey
    );

    @Select("""
            SELECT m.* FROM rd_project_memories m
            JOIN rd_project_memory_revisions r ON r.id=m.head_revision_id AND r.memory_id=m.id
            WHERE m.project_id=#{projectId} AND m.lifecycle_status='ENABLED'
              AND (m.scope_role='' OR m.scope_role=#{role})
              AND r.status='ACTIVE' AND r.redacted=TRUE
              AND r.valid_from<=#{now} AND (r.valid_to IS NULL OR r.valid_to>#{now})
              AND r.confidence>=#{minConfidence} AND r.evidence_quality>=#{minEvidenceQuality}
            ORDER BY r.importance DESC, r.confidence DESC, r.created_at DESC, m.id ASC
            LIMIT #{limit}
            """)
    List<ProjectMemoryRow> searchActiveHeads(
            @Param("projectId") long projectId,
            @Param("role") String role,
            @Param("now") OffsetDateTime now,
            @Param("minConfidence") double minConfidence,
            @Param("minEvidenceQuality") double minEvidenceQuality,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
              FROM rd_project_memories m
              JOIN rd_project_memory_revisions r ON r.id=m.head_revision_id AND r.memory_id=m.id
             WHERE m.project_id=#{projectId}
               AND m.lifecycle_status='ENABLED'
               AND (m.scope_role='' OR m.scope_role=#{role})
               AND r.status='ACTIVE'
               AND r.redacted=TRUE
               AND r.valid_from<=#{now}
               AND (r.valid_to IS NULL OR r.valid_to>#{now})
               AND r.confidence>=#{minConfidence}
               AND r.evidence_quality>=#{minEvidenceQuality}
            """)
    int countExaminedActiveHeads(
            @Param("projectId") long projectId,
            @Param("role") String role,
            @Param("now") OffsetDateTime now,
            @Param("minConfidence") double minConfidence,
            @Param("minEvidenceQuality") double minEvidenceQuality
    );

    @Select("""
            SELECT m.id AS memoryId,
                   r.version AS revisionVersion,
                   m.project_id AS projectId,
                   m.scope_role AS scopeRole,
                   r.summary AS summary,
                   r.content_hash AS contentHash,
                   r.evidence_quality AS evidenceQuality,
                   r.confidence AS confidence
              FROM rd_project_memories m
              JOIN rd_project_memory_revisions r ON r.id=m.head_revision_id AND r.memory_id=m.id
             WHERE m.project_id=#{projectId}
               AND m.lifecycle_status='ENABLED'
               AND (m.scope_role='' OR m.scope_role=#{role})
               AND r.status='ACTIVE'
               AND r.redacted=TRUE
               AND r.valid_from<=#{now}
               AND (r.valid_to IS NULL OR r.valid_to>#{now})
               AND r.confidence>=#{minConfidence}
               AND r.evidence_quality>=#{minEvidenceQuality}
               AND LOWER(r.summary) LIKE CONCAT('%', #{query}, '%')
             ORDER BY r.importance DESC, r.confidence DESC, r.created_at DESC, m.id ASC
             LIMIT #{limit}
            """)
    List<ProjectMemorySearchRow> searchActiveHeadsLexical(
            @Param("projectId") long projectId,
            @Param("role") String role,
            @Param("now") OffsetDateTime now,
            @Param("minConfidence") double minConfidence,
            @Param("minEvidenceQuality") double minEvidenceQuality,
            @Param("query") String query,
            @Param("limit") int limit
    );

    @Select("""
            SELECT * FROM rd_project_memories
             WHERE project_id=#{projectId}
             ORDER BY id ASC
            """)
    List<ProjectMemoryRow> selectByProjectId(@Param("projectId") long projectId);

    @Update("""
            UPDATE rd_project_memories
               SET lifecycle_status=#{lifecycleStatus},
                   head_revision_id=#{headRevisionId},
                   head_version=#{headVersion},
                   row_version=row_version+1,
                   deleted_at=CASE WHEN #{lifecycleStatus}='DELETED' THEN now() ELSE deleted_at END,
                   updated_at=now()
             WHERE id=#{id} AND row_version=#{expectedRowVersion}
            """)
    int replaceMemory(
            @Param("id") long id,
            @Param("lifecycleStatus") String lifecycleStatus,
            @Param("headRevisionId") Long headRevisionId,
            @Param("headVersion") long headVersion,
            @Param("expectedRowVersion") long expectedRowVersion
    );
}
