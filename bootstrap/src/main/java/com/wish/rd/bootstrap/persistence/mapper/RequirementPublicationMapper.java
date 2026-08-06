package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for durable requirement publication intents. */
@Mapper
public interface RequirementPublicationMapper extends BaseMapper<RequirementPublicationRow> {

    @Insert("""
            INSERT INTO rd_requirement_publications (
                id, operation_id, task_id, stage_run_id, status,
                base_branch, work_branch, candidate_patch_sha256,
                remote_head_sha, pull_request_url, pull_request_number,
                version, last_error, next_reconcile_at, created_at, updated_at
            ) VALUES (
                #{id}, #{operationId}, #{taskId}, #{stageRunId}, #{status},
                #{baseBranch}, #{workBranch}, #{candidatePatchSha256},
                #{remoteHeadSha}, #{pullRequestUrl}, #{pullRequestNumber},
                #{version}, #{lastError}, #{nextReconcileAt}, #{createdAt}, #{updatedAt}
            ) ON CONFLICT (operation_id) DO NOTHING
            """)
    int insertPreparedIfAbsent(RequirementPublicationRow row);

    @Select("SELECT * FROM rd_requirement_publications WHERE operation_id = #{operationId} LIMIT 1")
    RequirementPublicationRow selectByOperationId(String operationId);

    @Select("""
            SELECT * FROM rd_requirement_publications
             WHERE status = 'UNKNOWN_REMOTE_RESULT'
               AND next_reconcile_at IS NOT NULL
               AND next_reconcile_at <= #{before}
             ORDER BY next_reconcile_at ASC, operation_id ASC
             LIMIT #{limit}
            """)
    java.util.List<RequirementPublicationRow> selectDueForReconcile(
            @org.apache.ibatis.annotations.Param("before") java.time.OffsetDateTime before,
            @org.apache.ibatis.annotations.Param("limit") int limit
    );

    @Update("""
            UPDATE rd_requirement_publications
               SET status = #{status},
                   remote_head_sha = #{remoteHeadSha},
                   pull_request_url = #{pullRequestUrl},
                   pull_request_number = #{pullRequestNumber},
                   version = #{version},
                   last_error = #{lastError},
                   next_reconcile_at = #{nextReconcileAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND operation_id = #{operationId}
               AND version = #{expectedVersion}
            """)
    int updateWithExpectedVersion(RequirementPublicationRow row);
}
