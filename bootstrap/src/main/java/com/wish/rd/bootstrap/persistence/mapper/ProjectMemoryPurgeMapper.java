package com.wish.rd.bootstrap.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMemoryPurgeMapper {

    @Select("SELECT COUNT(*) FROM rd_project_memories WHERE project_id=#{projectId}")
    int countMemories(@Param("projectId") long projectId);

    @Select("""
            SELECT COUNT(*)
              FROM rd_project_memory_revisions r
              JOIN rd_project_memories m ON m.id=r.memory_id
             WHERE m.project_id=#{projectId}
            """)
    int countRevisions(@Param("projectId") long projectId);

    @Select("SELECT COUNT(*) FROM rd_project_memory_sources WHERE project_id=#{projectId}")
    int countSources(@Param("projectId") long projectId);

    @Select("SELECT COUNT(*) FROM rd_project_memory_operations WHERE project_id=#{projectId}")
    int countOperations(@Param("projectId") long projectId);

    @Select("SELECT COUNT(*) FROM rd_project_memory_legacy_links WHERE project_id=#{projectId}")
    int countLegacyLinks(@Param("projectId") long projectId);

    @Delete("DELETE FROM rd_project_memory_legacy_links WHERE project_id=#{projectId}")
    int deleteLegacyLinks(@Param("projectId") long projectId);

    @Update("""
            UPDATE rd_project_memories
               SET head_revision_id=NULL, head_version=0, updated_at=now()
             WHERE project_id=#{projectId}
            """)
    int clearHeads(@Param("projectId") long projectId);

    @Delete("DELETE FROM rd_project_memory_sources WHERE project_id=#{projectId}")
    int deleteSources(@Param("projectId") long projectId);

    @Delete("""
            DELETE FROM rd_project_memory_revisions r
             USING rd_project_memories m
             WHERE r.memory_id=m.id AND m.project_id=#{projectId}
            """)
    int deleteRevisions(@Param("projectId") long projectId);

    @Delete("DELETE FROM rd_project_memories WHERE project_id=#{projectId}")
    int deleteMemories(@Param("projectId") long projectId);

    @Delete("DELETE FROM rd_project_memory_operations WHERE project_id=#{projectId}")
    int deleteOperations(@Param("projectId") long projectId);
}
