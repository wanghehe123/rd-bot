package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairRecordRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
}
