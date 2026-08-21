package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** PostgreSQL mapper for agent profiles and their project/task bindings. */
@Mapper
public interface AgentExecutionProfileMapper {

    @Insert("""
            INSERT INTO rd_agent_execution_profiles (
              profile_id, project_id, role, name, runtime_type, provider_profile_id,
              model_override, extension_set_id, extension_set_version, tool_policy_id, tool_policy_version,
              enabled, version, capabilities_json
            ) VALUES (
              #{profileId}, #{projectId}, #{role}, #{name}, #{runtimeType}, #{providerProfileId},
              #{modelOverride}, #{extensionSetId}, #{extensionSetVersion}, #{toolPolicyId}, #{toolPolicyVersion},
              #{enabled}, #{version}, CAST(#{capabilitiesJson} AS jsonb)
            )
            """)
    int insert(AgentExecutionProfileRow row);

    @Update("""
            UPDATE rd_agent_execution_profiles SET
              project_id=#{row.projectId},
              role=#{row.role},
              name=#{row.name},
              runtime_type=#{row.runtimeType},
              provider_profile_id=#{row.providerProfileId},
              model_override=#{row.modelOverride},
              extension_set_id=#{row.extensionSetId},
              extension_set_version=#{row.extensionSetVersion},
              tool_policy_id=#{row.toolPolicyId},
              tool_policy_version=#{row.toolPolicyVersion},
              enabled=#{row.enabled},
              version=#{row.version},
              capabilities_json=CAST(#{row.capabilitiesJson} AS jsonb),
              updated_at=now()
            WHERE profile_id=#{row.profileId} AND version=#{expectedVersion}
            """)
    int update(
            @Param("row") AgentExecutionProfileRow row,
            @Param("expectedVersion") long expectedVersion
    );

    @Select("""
            SELECT profile_id, project_id, role, name, runtime_type, provider_profile_id,
                   model_override, extension_set_id, extension_set_version, tool_policy_id, tool_policy_version,
                   enabled, version, capabilities_json
            FROM rd_agent_execution_profiles
            WHERE profile_id=#{profileId}
            """)
    AgentExecutionProfileRow find(@Param("profileId") String profileId);

    /** Linearization lock used before a PI remediation intent becomes OUTCOME_RECORDED. */
    @Select("""
            SELECT profile_id, project_id, role, name, runtime_type, provider_profile_id,
                   model_override, extension_set_id, extension_set_version, tool_policy_id, tool_policy_version,
                   enabled, version, capabilities_json
            FROM rd_agent_execution_profiles
            WHERE profile_id=#{profileId}
            FOR UPDATE
            """)
    AgentExecutionProfileRow findForUpdate(@Param("profileId") String profileId);

    @Select("""
            SELECT profile_id, project_id, role, name, runtime_type, provider_profile_id,
                   model_override, extension_set_id, extension_set_version, tool_policy_id, tool_policy_version,
                   enabled, version, capabilities_json
            FROM rd_agent_execution_profiles
            WHERE project_id=#{projectId}
            ORDER BY role, name, profile_id
            """)
    List<AgentExecutionProfileRow> listByProject(@Param("projectId") long projectId);

    @Insert("""
            INSERT INTO rd_project_agent_profile_bindings (project_id, role, profile_id)
            VALUES (#{projectId}, #{role}, #{profileId})
            ON CONFLICT (project_id, role) DO UPDATE SET
              profile_id=EXCLUDED.profile_id,
              updated_at=now()
            """)
    int bindProjectDefault(
            @Param("projectId") long projectId,
            @Param("role") String role,
            @Param("profileId") String profileId
    );

    @Select("""
            SELECT profile_id
            FROM rd_project_agent_profile_bindings
            WHERE project_id=#{projectId} AND role=#{role}
            """)
    String findProjectDefault(@Param("projectId") long projectId, @Param("role") String role);

    @Insert("""
            INSERT INTO rd_task_agent_profile_overrides (task_id, project_id, role, profile_id)
            VALUES (#{taskId}, #{projectId}, #{role}, #{profileId})
            ON CONFLICT (task_id, role) DO UPDATE SET
              project_id=EXCLUDED.project_id,
              profile_id=EXCLUDED.profile_id,
              updated_at=now()
            """)
    int setTaskOverride(
            @Param("taskId") long taskId,
            @Param("projectId") long projectId,
            @Param("role") String role,
            @Param("profileId") String profileId
    );

    @Select("""
            SELECT profile_id
            FROM rd_task_agent_profile_overrides
            WHERE task_id=#{taskId} AND role=#{role}
            """)
    String findTaskOverride(@Param("taskId") long taskId, @Param("role") String role);

    @Delete("""
            DELETE FROM rd_task_agent_profile_overrides
            WHERE task_id=#{taskId} AND role=#{role}
            """)
    int clearTaskOverride(@Param("taskId") long taskId, @Param("role") String role);
}
