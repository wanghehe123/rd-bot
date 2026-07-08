package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.IngestionPipelineNodeRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 摄取管道节点 MyBatis-Plus mapper。
 *
 * <p>供 PostgreSQL 摄取管道适配器保存节点拓扑。
 */
@Mapper
public interface IngestionPipelineNodeMapper extends BaseMapper<IngestionPipelineNodeRow> {

    @Insert("""
            INSERT INTO t_ingestion_pipeline_node (
                id, pipeline_id, node_id, node_type, next_node_id, settings_json, condition_json,
                created_by, updated_by, create_time, update_time, deleted
            )
            VALUES (
                #{id}, #{pipelineId}, #{nodeId}, #{nodeType}, #{nextNodeId}, #{settingsJson}::jsonb,
                #{conditionJson}::jsonb, #{createdBy}, #{updatedBy}, #{createTime}, #{updateTime}, #{deleted}
            )
            """)
    void insertNode(IngestionPipelineNodeRow row);
}
