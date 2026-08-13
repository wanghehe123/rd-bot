package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeExternalIndexOutboxRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface KnowledgeExternalIndexOutboxMapper extends BaseMapper<KnowledgeExternalIndexOutboxRow> {

    @Insert("""
            INSERT INTO knowledge_external_index_outbox (
                event_id, idempotency_key, provider, operation_type, knowledge_base_id, document_id,
                sync_version, checksum, remote_uri, revision_id, payload_ref, status,
                remote_task_id, remote_operation_id, lease_owner, lease_until, attempt_count, max_attempts,
                next_visible_at, published_at, last_error_code, last_error_message, row_version,
                created_at, updated_at
            ) VALUES (
                #{eventId}, #{idempotencyKey}, #{provider}, #{operationType}, #{knowledgeBaseId}, #{documentId},
                #{syncVersion}, #{checksum}, #{remoteUri}, #{revisionId}, #{payloadRef}, #{status},
                #{remoteTaskId}, #{remoteOperationId}, #{leaseOwner}, #{leaseUntil}, #{attemptCount}, #{maxAttempts},
                #{nextVisibleAt}, #{publishedAt}, #{lastErrorCode}, #{lastErrorMessage}, #{rowVersion},
                #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(KnowledgeExternalIndexOutboxRow row);

    @Select("""
            SELECT * FROM knowledge_external_index_outbox
             WHERE idempotency_key = #{idempotencyKey}
            """)
    KnowledgeExternalIndexOutboxRow findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM knowledge_external_index_outbox
             WHERE provider = #{provider}
               AND document_id = #{documentId}
               AND sync_version = #{syncVersion}
               AND operation_type = #{operationType}
            """)
    KnowledgeExternalIndexOutboxRow findByDocumentIdentity(
            @Param("provider") String provider,
            @Param("documentId") Long documentId,
            @Param("syncVersion") Long syncVersion,
            @Param("operationType") String operationType
    );

    @Select("""
            WITH candidates AS (
                SELECT event_id
                  FROM knowledge_external_index_outbox
                 WHERE attempt_count < max_attempts
                   AND (
                        (status IN ('PENDING', 'RETRY_WAIT')
                         AND next_visible_at <= #{now}
                         AND (lease_until IS NULL OR lease_until <= #{now}))
                     OR (status = 'CLAIMED' AND lease_until IS NOT NULL AND lease_until <= #{now})
                   )
                 ORDER BY next_visible_at, created_at, event_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT #{batchSize}
            )
            UPDATE knowledge_external_index_outbox op
               SET status = 'CLAIMED',
                   lease_owner = #{leaseOwner},
                   lease_until = #{leaseUntil},
                   attempt_count = op.attempt_count + 1,
                   row_version = op.row_version + 1,
                   updated_at = #{now}
              FROM candidates
             WHERE op.event_id = candidates.event_id
            RETURNING op.*
            """)
    List<KnowledgeExternalIndexOutboxRow> claimBatch(
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("batchSize") int batchSize
    );

    @Select("""
            UPDATE knowledge_external_index_outbox
               SET status = #{nextStatus},
                   lease_owner = CASE WHEN #{nextStatus} IN ('SUCCEEDED', 'SUPERSEDED', 'DEAD_LETTER') THEN '' ELSE lease_owner END,
                   lease_until = CASE WHEN #{nextStatus} IN ('SUCCEEDED', 'SUPERSEDED', 'DEAD_LETTER') THEN NULL ELSE lease_until END,
                   row_version = row_version + 1,
                   updated_at = #{now}
             WHERE event_id = #{eventId}
               AND status = #{expectedStatus}
               AND lease_owner = #{leaseOwner}
               AND row_version = #{expectedRowVersion}
               AND lease_until IS NOT NULL
               AND lease_until > #{now}
            RETURNING *
            """)
    KnowledgeExternalIndexOutboxRow settle(
            @Param("eventId") long eventId,
            @Param("expectedStatus") String expectedStatus,
            @Param("leaseOwner") String leaseOwner,
            @Param("expectedRowVersion") long expectedRowVersion,
            @Param("nextStatus") String nextStatus,
            @Param("now") OffsetDateTime now
    );
}
