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

    /** Locks one publication receipt for the enclosing stage-finalization transaction. */
    @Select("""
            SELECT * FROM rd_requirement_publications
             WHERE operation_id = #{operationId}
             FOR UPDATE
            """)
    RequirementPublicationRow selectByOperationIdForUpdate(
            @org.apache.ibatis.annotations.Param("operationId") String operationId
    );

    /** Advances only the exact PR-confirmed publication receipt locked by the finalizer. */
    @Update("""
            UPDATE rd_requirement_publications
               SET status = 'COMMITTED', version = version + 1, updated_at = #{updatedAt}
             WHERE id = #{id} AND operation_id = #{operationId} AND task_id = #{taskId}
               AND status = 'PR_CONFIRMED' AND version = #{expectedVersion}
            """)
    int commitConfirmedReceipt(
            @org.apache.ibatis.annotations.Param("id") String id,
            @org.apache.ibatis.annotations.Param("operationId") String operationId,
            @org.apache.ibatis.annotations.Param("taskId") Long taskId,
            @org.apache.ibatis.annotations.Param("expectedVersion") Integer expectedVersion,
            @org.apache.ibatis.annotations.Param("updatedAt") java.time.OffsetDateTime updatedAt
    );

    @Select("""
            SELECT * FROM rd_requirement_publications
             WHERE task_id = #{taskId}
             ORDER BY updated_at DESC, id DESC
             LIMIT 1
            """)
    RequirementPublicationRow selectLatestByTaskId(@org.apache.ibatis.annotations.Param("taskId") Long taskId);

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
