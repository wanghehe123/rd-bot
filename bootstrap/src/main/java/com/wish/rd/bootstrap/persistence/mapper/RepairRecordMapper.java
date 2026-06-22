package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairRecordRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RepairRecordMapper extends BaseMapper<RepairRecordRow> {

    @Insert("""
            INSERT INTO repair_records (
                id, ticket_id, ticket_url, title, status, rag_summary,
                executor_json, docker_json, github_json, test_json, risk_json,
                error_message, extension_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{ticketId}, #{ticketUrl}, #{title}, #{status}, #{ragSummary},
                #{executorJson}::jsonb, #{dockerJson}::jsonb, #{githubJson}::jsonb,
                #{testJson}::jsonb, #{riskJson}::jsonb, #{errorMessage},
                #{extensionJson}::jsonb, #{createdAt}, #{updatedAt}
            )
            """)
    void insertRecord(RepairRecordRow row);

    @Update("""
            UPDATE repair_records
            SET status = #{status},
                rag_summary = #{ragSummary},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateStatus(RepairRecordRow row);

    @Update("""
            UPDATE repair_records
            SET executor_json = #{executorJson}::jsonb,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateExecutorJson(RepairRecordRow row);

    @Update("""
            UPDATE repair_records
            SET docker_json = #{dockerJson}::jsonb,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateDockerJson(RepairRecordRow row);

    @Update("""
            UPDATE repair_records
            SET github_json = #{githubJson}::jsonb,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateGithubJson(RepairRecordRow row);

    @Update("""
            UPDATE repair_records
            SET test_json = #{testJson}::jsonb,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateTestJson(RepairRecordRow row);

    @Update("""
            UPDATE repair_records
            SET risk_json = #{riskJson}::jsonb,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateRiskJson(RepairRecordRow row);

    @Update("""
            UPDATE repair_records
            SET error_message = #{errorMessage},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void updateErrorMessage(RepairRecordRow row);
}
