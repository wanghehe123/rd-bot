package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ProjectRuntimeProfileRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** PostgreSQL mapper for verified per-project role runtimes. */
@Mapper
public interface ProjectRuntimeProfileMapper {

    @Insert("""
            INSERT INTO rd_project_runtime_profiles (
              project_id, role, agent_type, image, dockerfile_uri, dockerfile_sha256,
              dockerfile_name, validation_status, validation_summary, created_at, updated_at
            ) VALUES (
              #{projectId}, #{role}, #{agentType}, #{image}, #{dockerfileUri}, #{dockerfileSha256},
              #{dockerfileName}, #{validationStatus}, #{validationSummary}, #{createdAt}, #{updatedAt}
            ) ON CONFLICT (project_id, role) DO UPDATE SET
              agent_type=EXCLUDED.agent_type,
              image=EXCLUDED.image,
              dockerfile_uri=EXCLUDED.dockerfile_uri,
              dockerfile_sha256=EXCLUDED.dockerfile_sha256,
              dockerfile_name=EXCLUDED.dockerfile_name,
              validation_status=EXCLUDED.validation_status,
              validation_summary=EXCLUDED.validation_summary,
              updated_at=EXCLUDED.updated_at
            """)
    int upsert(ProjectRuntimeProfileRow row);

    @Select("""
            SELECT project_id, role, agent_type, image, dockerfile_uri, dockerfile_sha256,
                   dockerfile_name, validation_status, validation_summary, created_at, updated_at
            FROM rd_project_runtime_profiles
            WHERE project_id=#{projectId} AND role=#{role}
            """)
    ProjectRuntimeProfileRow find(
            @Param("projectId") long projectId,
            @Param("role") String role
    );

    @Select("""
            SELECT project_id, role, agent_type, image, dockerfile_uri, dockerfile_sha256,
                   dockerfile_name, validation_status, validation_summary, created_at, updated_at
            FROM rd_project_runtime_profiles
            WHERE project_id=#{projectId}
            ORDER BY role ASC
            """)
    List<ProjectRuntimeProfileRow> list(@Param("projectId") long projectId);

    @Delete("""
            DELETE FROM rd_project_runtime_profiles
            WHERE project_id=#{projectId} AND role=#{role}
            """)
    int delete(@Param("projectId") long projectId, @Param("role") String role);
}
