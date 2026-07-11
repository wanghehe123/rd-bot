package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.QueryTermMappingRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** PostgreSQL mapper for durable query rewrite rules. */
@Mapper
public interface QueryTermMappingMapper {

    @Insert("""
            INSERT INTO rd_query_term_mappings (
                id, project_id, scope, source_term, target_term, priority, enabled, remark, created_at, updated_at
            ) VALUES (
                #{id}, #{projectId}, #{scope}, #{sourceTerm}, #{targetTerm}, #{priority}, #{enabled}, #{remark},
                #{createdAt}, #{updatedAt}
            ) ON CONFLICT (id) DO UPDATE SET
                project_id = EXCLUDED.project_id,
                scope = EXCLUDED.scope,
                source_term = EXCLUDED.source_term,
                target_term = EXCLUDED.target_term,
                priority = EXCLUDED.priority,
                enabled = EXCLUDED.enabled,
                remark = EXCLUDED.remark,
                updated_at = EXCLUDED.updated_at
            """)
    int upsert(QueryTermMappingRow row);

    @Select("""
            SELECT id, project_id, scope, source_term, target_term, priority, enabled, remark, created_at, updated_at
            FROM rd_query_term_mappings
            WHERE id = #{id}
            """)
    QueryTermMappingRow findById(long id);

    @Select("""
            SELECT id, project_id, scope, source_term, target_term, priority, enabled, remark, created_at, updated_at
            FROM rd_query_term_mappings
            ORDER BY updated_at DESC, id
            """)
    List<QueryTermMappingRow> list();

    @Delete("DELETE FROM rd_query_term_mappings WHERE id = #{id}")
    int deleteById(long id);
}
