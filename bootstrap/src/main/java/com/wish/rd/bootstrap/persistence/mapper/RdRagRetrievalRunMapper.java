package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalRunRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RdRagRetrievalRunMapper extends BaseMapper<RdRagRetrievalRunRow> {

    @Insert("""
            INSERT INTO rd_rag_retrieval_runs (
                id, task_id, consumer_type, role, stage_run_id, attempt_no, parent_run_id, idempotency_key,
                status, knowledge_base_ids_json, query_hash, query_preview, current_iteration, max_iterations,
                context_budget_chars, candidate_count, selected_evidence_count, quality_decision, stop_reason,
                error_category, error_message, lease_owner, lease_until, version, created_at, updated_at
            ) VALUES (
                #{id}, #{taskId}, #{consumerType}, #{role}, #{stageRunId}, #{attemptNo}, #{parentRunId}, #{idempotencyKey},
                #{status}, #{knowledgeBaseIdsJson}::jsonb, #{queryHash}, #{queryPreview}, #{currentIteration}, #{maxIterations},
                #{contextBudgetChars}, #{candidateCount}, #{selectedEvidenceCount}, #{qualityDecision}, #{stopReason},
                #{errorCategory}, #{errorMessage}, #{leaseOwner}, #{leaseUntil}, #{version}, #{createdAt}, #{updatedAt}
            )
            """)
    void insertRun(RdRagRetrievalRunRow row);

    @Update("""
            UPDATE rd_rag_retrieval_runs
            SET status = #{status}, current_iteration = #{currentIteration}, quality_decision = #{qualityDecision},
                stop_reason = #{stopReason}, error_category = #{errorCategory}, error_message = #{errorMessage},
                lease_owner = #{leaseOwner}, lease_until = #{leaseUntil}, version = #{version}, updated_at = #{updatedAt}
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int transition(RdRagRetrievalRunRow row);

    @Update("""
            UPDATE rd_rag_retrieval_runs
            SET candidate_count = #{candidateCount}, selected_evidence_count = #{selectedEvidenceCount},
                version = #{version}, updated_at = #{updatedAt}
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateEvidenceCounts(RdRagRetrievalRunRow row);
}
