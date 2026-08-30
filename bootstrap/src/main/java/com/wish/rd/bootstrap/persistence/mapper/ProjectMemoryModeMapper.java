package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryModeRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL mapper for per-project project-memory capture/read policy. */
@Mapper
public interface ProjectMemoryModeMapper {

    @Insert("""
            INSERT INTO rd_project_memory_modes (project_id, capture_mode, read_mode, created_at, updated_at)
            VALUES (#{projectId}, #{captureMode}, #{readMode}, #{createdAt}, #{updatedAt})
            ON CONFLICT (project_id) DO UPDATE SET
                capture_mode = EXCLUDED.capture_mode,
                read_mode = EXCLUDED.read_mode,
                updated_at = EXCLUDED.updated_at
            """)
    int upsert(ProjectMemoryModeRow row);

    @Select("""
            SELECT project_id, capture_mode, read_mode, created_at, updated_at
            FROM rd_project_memory_modes
            WHERE project_id = #{projectId}
            """)
    ProjectMemoryModeRow findByProjectId(long projectId);
}
