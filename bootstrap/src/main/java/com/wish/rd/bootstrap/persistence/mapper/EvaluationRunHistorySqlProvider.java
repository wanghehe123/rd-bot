package com.wish.rd.bootstrap.persistence.mapper;

/** Safe, parameter-bound SQL fragments for the evaluation history read model. */
public final class EvaluationRunHistorySqlProvider {
    private static final String GATE_STATUS = """
            COALESCE(
                NULLIF(metrics_json #>> '{summary,gateStatus}', ''),
                CASE WHEN status = 'SUCCEEDED'
                    THEN CASE WHEN overall_passed THEN 'PASSED' ELSE 'NOT_PASSED' END
                    ELSE '' END
            )
            """;
    private static final String JUDGE_STATUS = """
            COALESCE(
                NULLIF(metrics_json #>> '{summary,judgeStatus}', ''),
                NULLIF(metrics_json #>> '{summary,judge,status}', ''),
                CASE WHEN config_json ->> 'judgeProvider' = 'NONE' THEN 'NOT_REQUESTED' ELSE '' END
            )
            """;

    private EvaluationRunHistorySqlProvider() {
    }

    public static String selectHistoryPage() {
        return """
                <script>
                SELECT id, name, attempt_no, parent_run_id, status, phase_message, progress_percent,
                       config_json, sample_count, passed_sample_count, failed_sample_count, overall_passed,
                       metrics_json, error_category, error_message, version,
                       created_at, started_at, finished_at, updated_at
                  FROM rd_evaluation_runs
                %s
                 ORDER BY created_at DESC, id DESC
                 LIMIT #{limit} OFFSET #{offset}
                </script>
                """.formatted(historyWhereClause());
    }

    public static String countHistoryPage() {
        return """
                <script>
                SELECT COUNT(*)
                  FROM rd_evaluation_runs
                %s
                </script>
                """.formatted(historyWhereClause());
    }

    public static String selectHistoryOverview() {
        return """
                <script>
                WITH run_state AS (
                    SELECT status,
                           config_json,
                           %s AS gate_status
                      FROM rd_evaluation_runs
                )
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status IN (
                           'QUEUED', 'RECORDING', 'SCORING', 'REPORTING', 'DIFFING', 'CANCEL_REQUESTED'
                       )) AS active,
                       COUNT(*) FILTER (WHERE gate_status = 'PASSED' AND (
                           config_json ->> 'source' = 'TASK_RUN'
                           <choose>
                             <when test="nonSmokeDatasetIds != null and !nonSmokeDatasetIds.isEmpty()">
                               OR config_json ->> 'datasetId' IN
                               <foreach collection="nonSmokeDatasetIds" item="datasetId" open="(" separator="," close=")">
                                 #{datasetId}
                               </foreach>
                             </when>
                           </choose>
                       )) AS "gatePassed",
                       COUNT(*) FILTER (WHERE gate_status = 'INCOMPLETE') AS incomplete,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed
                  FROM run_state
                </script>
                """.formatted(GATE_STATUS);
    }

    private static String historyWhereClause() {
        return """
                <where>
                  <if test="query.keyword != null and query.keyword.length() > 0">
                    AND (
                      name ILIKE CONCAT('%%', #{query.keyword}, '%%')
                      OR CAST(id AS TEXT) ILIKE CONCAT('%%', #{query.keyword}, '%%')
                      OR COALESCE(config_json ->> 'taskId', '') ILIKE CONCAT('%%', #{query.keyword}, '%%')
                      OR COALESCE(config_json ->> 'datasetId', '') ILIKE CONCAT('%%', #{query.keyword}, '%%')
                      OR COALESCE(config_json ->> 'source', '') ILIKE CONCAT('%%', #{query.keyword}, '%%')
                    )
                  </if>
                  <if test="query.source != null">
                    AND config_json ->> 'source' = #{query.source}
                  </if>
                  <if test="query.datasetFilterApplied">
                    <choose>
                      <when test="query.datasetIds != null and !query.datasetIds.isEmpty()">
                        AND config_json ->> 'datasetId' IN
                        <foreach collection="query.datasetIds" item="datasetId" open="(" separator="," close=")">
                          #{datasetId}
                        </foreach>
                      </when>
                      <otherwise>
                        AND 1 = 0
                      </otherwise>
                    </choose>
                  </if>
                  <if test="query.status != null">
                    AND status = #{query.status}
                  </if>
                  <if test="query.gateStatus != null and query.gateStatus.length() > 0">
                    <choose>
                      <when test="query.gateStatus == 'PENDING'">
                        AND (%s) = ''
                      </when>
                      <otherwise>
                        AND (%s) = #{query.gateStatus}
                      </otherwise>
                    </choose>
                  </if>
                  <if test="query.judgeStatus != null and query.judgeStatus.length() > 0">
                    <choose>
                      <when test="query.judgeStatus == 'PENDING'">
                        AND (%s) = ''
                      </when>
                      <otherwise>
                        AND (%s) = #{query.judgeStatus}
                      </otherwise>
                    </choose>
                  </if>
                </where>
                """.formatted(GATE_STATUS, GATE_STATUS, JUDGE_STATUS, JUDGE_STATUS);
    }
}
