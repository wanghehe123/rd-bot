package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ProjectMemorySourceRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectMemorySourceMapper {

    @Insert("""
            INSERT INTO rd_project_memory_sources
            (id, revision_id, project_id, task_id, stage_run_id, artifact_id, source_uri, source_content_hash,
             repository_revision, extractor_version, schema_version, redacted_summary)
            VALUES (#{id}, #{revisionId}, #{projectId}, #{taskId}, #{stageRunId}, #{artifactId}, #{sourceUri},
                    #{sourceContentHash}, #{repositoryRevision}, #{extractorVersion}, #{schemaVersion},
                    #{redactedSummary})
            """)
    int insert(ProjectMemorySourceRow row);

    @Select("""
            SELECT id, revision_id AS revisionId, project_id AS projectId, task_id AS taskId,
                   stage_run_id AS stageRunId, artifact_id AS artifactId, source_uri AS sourceUri,
                   source_content_hash AS sourceContentHash, repository_revision AS repositoryRevision,
                   extractor_version AS extractorVersion, schema_version AS schemaVersion,
                   redacted_summary AS redactedSummary
              FROM rd_project_memory_sources
             WHERE revision_id=#{revisionId}
             ORDER BY id ASC
            """)
    List<ProjectMemorySourceRow> selectByRevisionId(@Param("revisionId") long revisionId);
}
