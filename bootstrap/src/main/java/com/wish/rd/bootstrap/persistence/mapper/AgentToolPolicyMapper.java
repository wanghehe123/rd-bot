package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.AgentToolPolicyRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** PostgreSQL mapper for immutable tool policy versions. */
@Mapper
public interface AgentToolPolicyMapper {

    @Insert("""
            INSERT INTO rd_agent_tool_policies (
              policy_id, version, policy_json, policy_hash, enabled
            ) VALUES (
              #{policyId}, #{version}, #{policyJson}, #{policyHash}, #{enabled}
            ) ON CONFLICT (policy_id, version) DO UPDATE SET
              policy_json=EXCLUDED.policy_json,
              policy_hash=EXCLUDED.policy_hash,
              enabled=EXCLUDED.enabled,
              updated_at=now()
            """)
    int upsert(AgentToolPolicyRow row);

    @Select("""
            SELECT policy_id, version, policy_json, policy_hash, enabled
            FROM rd_agent_tool_policies
            WHERE policy_id=#{policyId} AND version=#{version}
            """)
    AgentToolPolicyRow find(@Param("policyId") String policyId, @Param("version") long version);

    @Select("""
            SELECT policy_id, version, policy_json, policy_hash, enabled
            FROM rd_agent_tool_policies
            WHERE policy_id=#{policyId}
            ORDER BY version DESC
            LIMIT 1
            """)
    AgentToolPolicyRow findLatest(@Param("policyId") String policyId);
}
