package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryOperationRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMemoryOperationMapper {
    @Insert("""
            INSERT INTO rd_project_memory_operations (
                id, operation_key, project_id, operation_kind, source_kind, source_identity,
                source_content_hash, extractor_version, schema_version, status
            ) VALUES (
                #{id}, #{operationKey}, #{projectId}, #{operationKind}, 'HOST', #{sourceIdentity},
                #{sourceContentHash}, #{extractorVersion}, #{schemaVersion}, 'PENDING'
            ) ON CONFLICT (operation_key) DO NOTHING
            """)
    int insertIfAbsent(ProjectMemoryOperationRow row);

    @Select("SELECT * FROM rd_project_memory_operations WHERE operation_key=#{key} FOR UPDATE")
    ProjectMemoryOperationRow findForUpdate(@Param("key") String key);

    @Select("SELECT * FROM rd_project_memory_operations WHERE id=#{id}")
    ProjectMemoryOperationRow findById(@Param("id") long id);

    @Select("""
            SELECT * FROM rd_project_memory_operations
            WHERE status IN ('PENDING','RETRYABLE')
              AND next_visible_at <= now()
              AND (lease_until IS NULL OR lease_until < now())
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """)
    ProjectMemoryOperationRow claimNext();

    @Update("""
            UPDATE rd_project_memory_operations
            SET lease_owner=#{owner},
                lease_until=now() + make_interval(secs => #{leaseSeconds}),
                fencing_token=fencing_token + 1,
                row_version=row_version + 1,
                status='RUNNING'
            WHERE id=#{id}
              AND row_version=#{rowVersion}
              AND status IN ('PENDING','RETRYABLE')
              AND (lease_until IS NULL OR lease_until < now())
            """)
    int claim(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("rowVersion") long rowVersion,
            @Param("leaseSeconds") long leaseSeconds
    );

    @Update("""
            UPDATE rd_project_memory_operations
            SET status=#{status},
                lease_owner='',
                lease_until=NULL,
                row_version=row_version + 1
            WHERE id=#{id}
              AND lease_owner=#{owner}
              AND fencing_token=#{fence}
              AND row_version=#{rowVersion}
              AND lease_until >= now()
            """)
    int settle(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("fence") long fence,
            @Param("rowVersion") long rowVersion,
            @Param("status") String status
    );

    @Update("""
            UPDATE rd_project_memory_operations
            SET status='RETRYABLE',
                lease_owner='',
                lease_until=NULL,
                attempt_no=attempt_no + 1,
                next_visible_at=to_timestamp(#{nextVisibleEpochMillis} / 1000.0),
                last_error=#{lastError},
                row_version=row_version + 1
            WHERE id=#{id}
              AND lease_owner=#{owner}
              AND fencing_token=#{fence}
              AND row_version=#{rowVersion}
              AND lease_until >= now()
            """)
    int scheduleRetry(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("fence") long fence,
            @Param("rowVersion") long rowVersion,
            @Param("nextVisibleEpochMillis") long nextVisibleEpochMillis,
            @Param("lastError") String lastError
    );

    @Update("""
            UPDATE rd_project_memory_operations
            SET status='NEEDS_HUMAN',
                lease_owner='',
                lease_until=NULL,
                last_error=#{lastError},
                row_version=row_version + 1
            WHERE id=#{id}
              AND lease_owner=#{owner}
              AND fencing_token=#{fence}
              AND row_version=#{rowVersion}
              AND lease_until >= now()
            """)
    int markNeedsHuman(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("fence") long fence,
            @Param("rowVersion") long rowVersion,
            @Param("lastError") String lastError
    );

    @Update("""
            UPDATE rd_project_memory_operations
            SET checkpoint_json=#{checkpointJson}::jsonb,
                row_version=row_version + 1
            WHERE id=#{id}
              AND lease_owner=#{owner}
              AND fencing_token=#{fence}
              AND row_version=#{rowVersion}
              AND lease_until >= now()
            """)
    int updateCheckpoint(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("fence") long fence,
            @Param("rowVersion") long rowVersion,
            @Param("checkpointJson") String checkpointJson
    );
}
