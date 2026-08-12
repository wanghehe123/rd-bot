package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;

/** MyBatis persistence operations for immutable policy-run generations and fenced ledger CAS. */
@Mapper
public interface RequirementPolicyRunMapper extends BaseMapper<RequirementPolicyRunRow> {
    @Insert("INSERT INTO rd_requirement_policy_runs (id,task_id,source_task_version,source_fence,plan_json,plan_digest,policy_json,policy_digest,policy_action,state,bound_task_version,bound_fence,approval_expected_task_version,approval_expected_fence,approval_request_id,approved_by,note,approved_at,approval_resume_command_id,consumed_by_command_id,consumed_at,ledger_version,created_at,updated_at) VALUES (#{id},#{taskId},#{sourceTaskVersion},#{sourceFence},CAST(#{planJson} AS jsonb),#{planDigest},CAST(NULLIF(#{policyJson}, '') AS jsonb),NULLIF(#{policyDigest}, ''),#{policyAction},#{state},#{boundTaskVersion},#{boundFence},#{approvalExpectedTaskVersion},#{approvalExpectedFence},NULLIF(#{approvalRequestId}, ''),#{approvedBy},#{note},#{approvedAt},#{approvalResumeCommandId},#{consumedByCommandId},#{consumedAt},#{ledgerVersion},#{createdAt},#{updatedAt}) ON CONFLICT DO NOTHING")
    int insertIfAbsent(RequirementPolicyRunRow row);
    @Select("SELECT * FROM rd_requirement_policy_runs WHERE id=#{id}") RequirementPolicyRunRow findById(@Param("id") long id);
    @Select("SELECT * FROM rd_requirement_policy_runs WHERE task_id=#{taskId} AND source_task_version=#{version} AND source_fence=#{fence}") RequirementPolicyRunRow findByGeneration(@Param("taskId") long taskId,@Param("version") long version,@Param("fence") long fence);
    @Select("SELECT * FROM rd_requirement_policy_runs WHERE task_id=#{taskId} AND state IN ('PLAN_READY','POLICY_DECIDED','WAITING_APPROVAL','APPROVED') ORDER BY created_at,id LIMIT 1") RequirementPolicyRunRow findActiveByTask(@Param("taskId") long taskId);
    @Select("SELECT * FROM rd_requirement_policy_runs WHERE id=#{id} FOR UPDATE") RequirementPolicyRunRow lockForUpdate(@Param("id") long id);
    /** Locks the sole ledger generation bound to an approval-resume command before its task row. */
    @Select("SELECT * FROM rd_requirement_policy_runs WHERE approval_resume_command_id=#{commandId} FOR UPDATE")
    RequirementPolicyRunRow lockByApprovalResumeCommandIdForUpdate(@Param("commandId") long commandId);
    /**
     * Locks the exact policy generation consumed by a policy-apply command before locking its
     * task row. The source pair is derived from the apply command's post-evaluation generation.
     */
    @Select("SELECT * FROM rd_requirement_policy_runs WHERE task_id=#{taskId} AND source_task_version=#{sourceVersion} AND source_fence=#{sourceFence} AND state IN ('POLICY_DECIDED','WAITING_APPROVAL','APPLIED','DENIED') FOR UPDATE")
    RequirementPolicyRunRow lockPolicyApplyGenerationForUpdate(
            @Param("taskId") long taskId,
            @Param("sourceVersion") long sourceVersion,
            @Param("sourceFence") long sourceFence);
    @Update("UPDATE rd_requirement_policy_runs SET policy_json=CAST(NULLIF(#{row.policyJson}, '') AS jsonb),policy_digest=NULLIF(#{row.policyDigest}, ''),policy_action=#{row.policyAction},state=#{row.state},bound_task_version=#{row.boundTaskVersion},bound_fence=#{row.boundFence},approval_expected_task_version=#{row.approvalExpectedTaskVersion},approval_expected_fence=#{row.approvalExpectedFence},approval_request_id=NULLIF(#{row.approvalRequestId}, ''),approved_by=#{row.approvedBy},note=#{row.note},approved_at=#{row.approvedAt},approval_resume_command_id=#{row.approvalResumeCommandId},consumed_by_command_id=#{row.consumedByCommandId},consumed_at=#{row.consumedAt},ledger_version=#{row.ledgerVersion},updated_at=#{row.updatedAt} WHERE id=#{row.id} AND state=#{expectedState} AND ledger_version=#{expectedVersion}")
    int compareAndSet(@Param("row") RequirementPolicyRunRow row,@Param("expectedState") String expectedState,@Param("expectedVersion") long expectedVersion);
}
