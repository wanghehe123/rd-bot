package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryRevisionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProjectMemoryRevisionMapper {

    @Insert("""
            INSERT INTO rd_project_memory_revisions
            (id, memory_id, version, status, title, summary, content_json, content_hash, schema_version, supersedes_revision_id)
            VALUES (#{id}, #{memoryId}, #{version}, #{status}, #{title}, #{summary}, #{contentJson}::jsonb,
                    #{contentHash}, #{schemaVersion}, #{supersedesRevisionId})
            """)
    int insert(ProjectMemoryRevisionRow row);

    @Update("UPDATE rd_project_memory_revisions SET status='SUPERSEDED' WHERE id=#{revisionId} AND status='ACTIVE'")
    int markSuperseded(@Param("revisionId") long revisionId);

    @Select("""
            SELECT id, memory_id AS memoryId, version, status, title, summary, content_json AS contentJson,
                   content_hash AS contentHash, schema_version AS schemaVersion,
                   supersedes_revision_id AS supersedesRevisionId, row_version AS rowVersion
              FROM rd_project_memory_revisions
             WHERE memory_id=#{memoryId}
             ORDER BY version ASC
            """)
    List<ProjectMemoryRevisionRow> selectByMemoryId(@Param("memoryId") long memoryId);

    @Select("""
            SELECT id, memory_id AS memoryId, version, status, title, summary, content_json AS contentJson,
                   content_hash AS contentHash, schema_version AS schemaVersion,
                   supersedes_revision_id AS supersedesRevisionId, row_version AS rowVersion
              FROM rd_project_memory_revisions
             WHERE id=#{revisionId}
            """)
    ProjectMemoryRevisionRow selectById(@Param("revisionId") long revisionId);

    @Update("""
            UPDATE rd_project_memory_revisions
               SET status=#{status}, title=#{title}, summary=#{summary},
                   content_json=#{contentJson}::jsonb, content_hash=#{contentHash},
                   row_version=row_version+1
             WHERE id=#{id} AND row_version=#{expectedRowVersion}
            """)
    int replaceRevision(
            @Param("id") long id,
            @Param("status") String status,
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("contentJson") String contentJson,
            @Param("contentHash") String contentHash,
            @Param("expectedRowVersion") long expectedRowVersion
    );
}
