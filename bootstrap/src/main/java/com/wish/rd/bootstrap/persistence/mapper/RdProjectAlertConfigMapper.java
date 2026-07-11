package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.RdProjectAlertConfigRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL mapper for project alert configuration. */
@Mapper
public interface RdProjectAlertConfigMapper {

    @Insert("""
            INSERT INTO rd_project_alert_configs (
                project_id, enabled, recipients_json, event_types_json,
                budget_threshold_cny, failure_threshold, created_at, updated_at
            ) VALUES (
                #{projectId}, #{enabled}, CAST(#{recipientsJson} AS jsonb), CAST(#{eventTypesJson} AS jsonb),
                #{budgetThresholdCny}, #{failureThreshold}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (project_id) DO UPDATE SET
                enabled = EXCLUDED.enabled,
                recipients_json = EXCLUDED.recipients_json,
                event_types_json = EXCLUDED.event_types_json,
                budget_threshold_cny = EXCLUDED.budget_threshold_cny,
                failure_threshold = EXCLUDED.failure_threshold,
                updated_at = EXCLUDED.updated_at
            """)
    int upsert(RdProjectAlertConfigRow row);

    @Select("""
            SELECT project_id, enabled, recipients_json::text AS recipients_json,
                   event_types_json::text AS event_types_json, budget_threshold_cny,
                   failure_threshold, created_at, updated_at
            FROM rd_project_alert_configs WHERE project_id = #{projectId}
            """)
    RdProjectAlertConfigRow findByProjectId(long projectId);
}
