package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.HostAssertionBundleRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** PostgreSQL mapper for immutable Host assertion contracts. */
@Mapper
public interface HostAssertionBundleMapper {

    @Insert("""
            INSERT INTO rd_host_assertion_bundles (
              task_id, stage_run_id, scope, canonical_specs_json, content_hash, version, created_at
            ) VALUES (
              #{taskId}, #{stageRunId}, #{scope}, CAST(#{canonicalSpecsJson} AS jsonb),
              #{contentHash}, #{version}, #{createdAt}
            ) ON CONFLICT (task_id, stage_run_id, scope) DO NOTHING
            """)
    int insertIfAbsent(HostAssertionBundleRow row);

    @Select("""
            SELECT task_id AS taskId,
                   stage_run_id AS stageRunId,
                   scope,
                   canonical_specs_json::text AS canonicalSpecsJson,
                   content_hash AS contentHash,
                   version,
                   created_at AS createdAt
            FROM rd_host_assertion_bundles
            WHERE task_id=#{taskId} AND stage_run_id=#{stageRunId} AND scope=#{scope}
            """)
    HostAssertionBundleRow find(
            @Param("taskId") long taskId,
            @Param("stageRunId") long stageRunId,
            @Param("scope") String scope
    );

    @Select("""
            SELECT task_id AS taskId,
                   stage_run_id AS stageRunId,
                   scope,
                   canonical_specs_json::text AS canonicalSpecsJson,
                   content_hash AS contentHash,
                   version,
                   created_at AS createdAt
            FROM rd_host_assertion_bundles
            WHERE task_id=#{taskId} AND stage_run_id=#{stageRunId}
            ORDER BY scope ASC
            """)
    List<HostAssertionBundleRow> listByTaskAndStage(
            @Param("taskId") long taskId,
            @Param("stageRunId") long stageRunId
    );
}
