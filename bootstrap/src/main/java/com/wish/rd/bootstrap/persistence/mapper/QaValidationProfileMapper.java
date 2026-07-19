package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.QaValidationProfileRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL mapper for task and project QA profiles. */
@Mapper
public interface QaValidationProfileMapper {

    @Insert("""
            INSERT INTO rd_qa_validation_profiles (
              scope_type, scope_id, mode, base_url, start_command, health_path,
              allowed_hosts_json, regression_commands_json, created_at, updated_at
            ) VALUES (
              #{scopeType}, #{scopeId}, #{mode}, #{baseUrl}, #{startCommand}, #{healthPath},
              CAST(#{allowedHostsJson} AS jsonb), CAST(#{regressionCommandsJson} AS jsonb),
              #{createdAt}, #{updatedAt}
            ) ON CONFLICT (scope_type, scope_id) DO UPDATE SET
              mode=EXCLUDED.mode,
              base_url=EXCLUDED.base_url,
              start_command=EXCLUDED.start_command,
              health_path=EXCLUDED.health_path,
              allowed_hosts_json=EXCLUDED.allowed_hosts_json,
              regression_commands_json=EXCLUDED.regression_commands_json,
              updated_at=EXCLUDED.updated_at
            """)
    int upsert(QaValidationProfileRow row);

    @Select("""
            SELECT scope_type, scope_id, mode, base_url, start_command, health_path,
                   allowed_hosts_json::text AS allowed_hosts_json,
                   regression_commands_json::text AS regression_commands_json,
                   created_at, updated_at
            FROM rd_qa_validation_profiles
            WHERE scope_type=#{scopeType} AND scope_id=#{scopeId}
            """)
    QaValidationProfileRow find(
            @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId
    );
}
