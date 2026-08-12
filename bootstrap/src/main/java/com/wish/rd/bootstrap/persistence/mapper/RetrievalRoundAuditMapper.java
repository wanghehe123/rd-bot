package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RetrievalRoundAuditRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis mapper for append-only iterative retrieval round audit projections. */
@Mapper
public interface RetrievalRoundAuditMapper extends BaseMapper<RetrievalRoundAuditRow> {

    @Insert("""
            INSERT INTO rd_retrieval_round_audits (
                id, task_id, run_id, round_no, query_preview,
                candidate_evidence_ids_json, selected_evidence_ids_json, missing_evidence_types_json,
                information_gain, cumulative_tokens, elapsed_ms, stop_reason, scope_fingerprint, recorded_at
            ) VALUES (
                #{id}, #{taskId}, #{runId}, #{roundNo}, #{queryPreview},
                #{candidateEvidenceIdsJson}::jsonb, #{selectedEvidenceIdsJson}::jsonb, #{missingEvidenceTypesJson}::jsonb,
                #{informationGain}, #{cumulativeTokens}, #{elapsedMs}, #{stopReason}, #{scopeFingerprint}, #{recordedAt}
            ) ON CONFLICT (run_id, round_no) DO NOTHING
            """)
    int insertAudit(RetrievalRoundAuditRow row);

    @Select("""
            SELECT * FROM rd_retrieval_round_audits
             WHERE run_id = #{runId}
             ORDER BY round_no, recorded_at, id
            """)
    List<RetrievalRoundAuditRow> listByRun(@Param("runId") long runId);

    @Select("""
            SELECT * FROM rd_retrieval_round_audits
             WHERE task_id = #{taskId}
             ORDER BY recorded_at, run_id, round_no, id
            """)
    List<RetrievalRoundAuditRow> listByTask(@Param("taskId") long taskId);
}
