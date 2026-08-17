package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.AgentStrategyProfileRow;
import com.wish.rd.bootstrap.persistence.entity.AgentStrategyRoleSlotRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** PostgreSQL mapper for project agent strategies and their role slots. */
@Mapper
public interface AgentStrategyProfileMapper {

    @Insert("""
            INSERT INTO rd_agent_strategy_profiles (
              project_id, strategy_id, name, enabled, version
            ) VALUES (
              #{projectId}, #{strategyId}, #{name}, #{enabled}, #{version}
            ) ON CONFLICT (project_id, strategy_id) DO UPDATE SET
              name=EXCLUDED.name,
              enabled=EXCLUDED.enabled,
              version=EXCLUDED.version,
              updated_at=now()
            """)
    int upsertProfile(AgentStrategyProfileRow row);

    @Delete("""
            DELETE FROM rd_agent_strategy_role_slots
            WHERE project_id=#{projectId} AND strategy_id=#{strategyId}
            """)
    int deleteSlots(@Param("projectId") long projectId, @Param("strategyId") String strategyId);

    @Insert("""
            INSERT INTO rd_agent_strategy_role_slots (
              project_id, strategy_id, role, runtime_type, provider_profile_id, model_override,
              extension_set_id, extension_set_version, tool_policy_id, tool_policy_version,
              image_mode, image, dockerfile_name, dockerfile_sha256, dockerfile_artifact_uri, dockerfile_text
            ) VALUES (
              #{projectId}, #{strategyId}, #{role}, #{runtimeType}, #{providerProfileId}, #{modelOverride},
              #{extensionSetId}, #{extensionSetVersion}, #{toolPolicyId}, #{toolPolicyVersion},
              #{imageMode}, #{image}, #{dockerfileName}, #{dockerfileSha256}, #{dockerfileArtifactUri}, #{dockerfileText}
            )
            """)
    int insertSlot(AgentStrategyRoleSlotRow row);

    @Select("""
            SELECT strategy_id, project_id, name, enabled, version
            FROM rd_agent_strategy_profiles
            WHERE strategy_id=#{strategyId} AND project_id=#{projectId}
            """)
    AgentStrategyProfileRow findProfile(
            @Param("projectId") long projectId,
            @Param("strategyId") String strategyId
    );

    @Select("""
            SELECT strategy_id, project_id, name, enabled, version
            FROM rd_agent_strategy_profiles
            WHERE project_id=#{projectId}
            ORDER BY name, strategy_id
            """)
    List<AgentStrategyProfileRow> listProfiles(@Param("projectId") long projectId);

    @Select("""
            SELECT project_id, strategy_id, role, runtime_type, provider_profile_id, model_override,
                   extension_set_id, extension_set_version, tool_policy_id, tool_policy_version,
                   image_mode, image, dockerfile_name, dockerfile_sha256, dockerfile_artifact_uri, dockerfile_text
            FROM rd_agent_strategy_role_slots
            WHERE project_id=#{projectId} AND strategy_id=#{strategyId}
            ORDER BY role
            """)
    List<AgentStrategyRoleSlotRow> listSlots(
            @Param("projectId") long projectId,
            @Param("strategyId") String strategyId
    );

    @Select("""
            SELECT s.project_id, s.strategy_id, s.role, s.runtime_type, s.provider_profile_id, s.model_override,
                   s.extension_set_id, s.extension_set_version, s.tool_policy_id, s.tool_policy_version,
                   s.image_mode, s.image, s.dockerfile_name, s.dockerfile_sha256, s.dockerfile_artifact_uri, s.dockerfile_text
            FROM rd_agent_strategy_role_slots s
            WHERE s.project_id=#{projectId}
            ORDER BY s.strategy_id, s.role
            """)
    List<AgentStrategyRoleSlotRow> listSlotsByProject(@Param("projectId") long projectId);

    @Insert("""
            INSERT INTO rd_project_agent_strategy_bindings (project_id, strategy_id)
            VALUES (#{projectId}, #{strategyId})
            ON CONFLICT (project_id) DO UPDATE SET
              strategy_id=EXCLUDED.strategy_id,
              updated_at=now()
            """)
    int bindDefault(@Param("projectId") long projectId, @Param("strategyId") String strategyId);

    @Select("""
            SELECT strategy_id
            FROM rd_project_agent_strategy_bindings
            WHERE project_id=#{projectId}
            """)
    String findDefault(@Param("projectId") long projectId);
}
