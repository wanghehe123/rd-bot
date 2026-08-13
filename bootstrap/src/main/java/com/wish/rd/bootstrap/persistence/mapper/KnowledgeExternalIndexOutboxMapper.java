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

    /**
     * 领取待提交的行。{@code remote_operation_id = ''} 是发送边界护栏：
     * 已经把请求交给远端的行永远不回到提交路径。
     */
    @Select("""
            WITH candidates AS (
                SELECT event_id
                  FROM knowledge_external_index_outbox
                 WHERE attempt_count < max_attempts
                   AND remote_operation_id = ''
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

    /**
     * 领取需要查询远端才能收敛的行。
     *
     * <p>{@code (lease_until IS NULL OR lease_until <= now)} 是存活性判定而非归属判定：
     * 崩溃实例的行可被接手，存活实例的行不会被抢。这里刻意不改状态、不递增
     * {@code attempt_count}、也不按 {@code attempt_count < max_attempts} 过滤——
     * 提交预算耗尽不该让一个仍在运行的远端任务变成不可见。
     */
    @Select("""
            WITH candidates AS (
                SELECT event_id
                  FROM knowledge_external_index_outbox
                 WHERE status IN ('SUBMITTED', 'WAITING_REMOTE', 'UNKNOWN_REMOTE_RESULT', 'VERIFYING')
                   AND next_visible_at <= #{now}
                   AND (lease_until IS NULL OR lease_until <= #{now})
                 ORDER BY next_visible_at, created_at, event_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT #{batchSize}
            )
            UPDATE knowledge_external_index_outbox op
               SET lease_owner = #{leaseOwner},
                   lease_until = #{leaseUntil},
                   row_version = op.row_version + 1,
                   updated_at = #{now}
              FROM candidates
             WHERE op.event_id = candidates.event_id
            RETURNING op.*
            """)
    List<KnowledgeExternalIndexOutboxRow> claimPollBatch(
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("batchSize") int batchSize
    );

    /**
     * CAS 写入。租约释放由调用方显式声明的 {@code releaseLease} 决定，
     * 不从目标状态是否终态推断；空串与 NULL 参数保持原值。
     */
    @Select("""
            UPDATE knowledge_external_index_outbox
               SET status = #{nextStatus},
                   lease_owner = CASE WHEN #{releaseLease} THEN '' ELSE lease_owner END,
                   lease_until = CASE WHEN #{releaseLease} THEN NULL ELSE lease_until END,
                   remote_task_id = CASE WHEN #{remoteTaskId} = '' THEN remote_task_id ELSE #{remoteTaskId} END,
                   remote_operation_id = CASE WHEN #{clearSendMarker} THEN ''
                                              WHEN #{remoteOperationId} = '' THEN remote_operation_id
                                              ELSE #{remoteOperationId} END,
                   next_visible_at = COALESCE(#{nextVisibleAt}, next_visible_at),
                   published_at = COALESCE(published_at, #{publishedAt}),
                   last_error_code = CASE WHEN #{clearError} THEN ''
                                          WHEN #{errorCode} = '' THEN last_error_code
                                          ELSE #{errorCode} END,
                   last_error_message = CASE WHEN #{clearError} THEN ''
                                             WHEN #{errorMessage} = '' THEN last_error_message
                                             ELSE #{errorMessage} END,
                   attempt_count = CASE WHEN #{refundAttempt} THEN GREATEST(attempt_count - 1, 0)
                                        ELSE attempt_count END,
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
            @Param("releaseLease") boolean releaseLease,
            @Param("remoteTaskId") String remoteTaskId,
            @Param("remoteOperationId") String remoteOperationId,
            @Param("nextVisibleAt") OffsetDateTime nextVisibleAt,
            @Param("publishedAt") OffsetDateTime publishedAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("clearError") boolean clearError,
            @Param("clearSendMarker") boolean clearSendMarker,
            @Param("refundAttempt") boolean refundAttempt,
            @Param("now") OffsetDateTime now
    );
}
