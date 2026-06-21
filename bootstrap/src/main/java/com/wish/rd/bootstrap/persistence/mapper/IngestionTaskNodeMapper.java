package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.IngestionTaskNodeRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IngestionTaskNodeMapper extends BaseMapper<IngestionTaskNodeRow> {

    @Insert("""
            INSERT INTO ingestion_task_nodes (
                id, task_id, pipeline_id, node_id, node_type, node_order, status,
                duration_ms, message, error_message, output_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{taskId}, #{pipelineId}, #{nodeId}, #{nodeType}, #{nodeOrder}, #{status},
                #{durationMs}, #{message}, #{errorMessage}, #{outputJson}::jsonb, #{createdAt}, #{updatedAt}
            )
            """)
    void insertNode(IngestionTaskNodeRow row);
}
