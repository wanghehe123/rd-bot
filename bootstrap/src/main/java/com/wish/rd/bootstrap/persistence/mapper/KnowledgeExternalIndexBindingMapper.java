package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeExternalIndexBindingRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface KnowledgeExternalIndexBindingMapper extends BaseMapper<KnowledgeExternalIndexBindingRow> {

    @Insert("""
            INSERT INTO knowledge_external_index_bindings (
                provider, document_id, knowledge_base_id, remote_uri, ownership_marker,
                desired_state, desired_version, desired_checksum, observed_state, observed_version,
                observed_checksum, projection_status, active_operation_id, remote_task_id,
                semantic_config_fingerprint, last_submitted_at, last_verified_at,
                last_error_code, last_error_message, row_version, created_at, updated_at
            ) VALUES (
                #{provider}, #{documentId}, #{knowledgeBaseId}, #{remoteUri}, #{ownershipMarker},
                #{desiredState}, #{desiredVersion}, #{desiredChecksum}, #{observedState}, #{observedVersion},
                #{observedChecksum}, #{projectionStatus}, #{activeOperationId}, #{remoteTaskId},
                #{semanticConfigFingerprint}, #{lastSubmittedAt}, #{lastVerifiedAt},
                #{lastErrorCode}, #{lastErrorMessage}, #{rowVersion}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (provider, document_id) DO UPDATE SET
                knowledge_base_id = EXCLUDED.knowledge_base_id,
                remote_uri = EXCLUDED.remote_uri,
                ownership_marker = EXCLUDED.ownership_marker,
                desired_state = EXCLUDED.desired_state,
                desired_version = EXCLUDED.desired_version,
                desired_checksum = EXCLUDED.desired_checksum,
                observed_state = EXCLUDED.observed_state,
                observed_version = EXCLUDED.observed_version,
                observed_checksum = EXCLUDED.observed_checksum,
                projection_status = EXCLUDED.projection_status,
                active_operation_id = EXCLUDED.active_operation_id,
                remote_task_id = EXCLUDED.remote_task_id,
                semantic_config_fingerprint = EXCLUDED.semantic_config_fingerprint,
                last_submitted_at = EXCLUDED.last_submitted_at,
                last_verified_at = EXCLUDED.last_verified_at,
                last_error_code = EXCLUDED.last_error_code,
                last_error_message = EXCLUDED.last_error_message,
                row_version = EXCLUDED.row_version,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(KnowledgeExternalIndexBindingRow row);

    @Insert("""
            INSERT INTO knowledge_external_index_bindings (
                provider, document_id, knowledge_base_id, remote_uri, ownership_marker,
                desired_state, desired_version, desired_checksum, observed_state, observed_version,
                observed_checksum, projection_status, active_operation_id, remote_task_id,
                semantic_config_fingerprint, last_submitted_at, last_verified_at,
                last_error_code, last_error_message, row_version, created_at, updated_at
            ) VALUES (
                #{provider}, #{documentId}, #{knowledgeBaseId}, #{remoteUri}, #{ownershipMarker},
                #{desiredState}, #{desiredVersion}, #{desiredChecksum}, #{observedState}, #{observedVersion},
                #{observedChecksum}, #{projectionStatus}, #{activeOperationId}, #{remoteTaskId},
                #{semanticConfigFingerprint}, #{lastSubmittedAt}, #{lastVerifiedAt},
                #{lastErrorCode}, #{lastErrorMessage}, #{rowVersion}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (provider, document_id) DO NOTHING
            """)
    int insertIfAbsent(KnowledgeExternalIndexBindingRow row);

    /**
     * 以 CAS 写入观测结果。SET 子句刻意不包含任何 {@code desired_*} 列：
     * desired 归本地 mutation 事务所有，Worker/Poller 覆盖它会把刚提交的更高版本意图退回。
     */
    @Select("""
            UPDATE knowledge_external_index_bindings
               SET observed_state = #{observedState},
                   observed_version = #{observedVersion},
                   observed_checksum = #{observedChecksum},
                   projection_status = #{projectionStatus},
                   active_operation_id = #{activeOperationId},
                   remote_task_id = #{remoteTaskId},
                   semantic_config_fingerprint = #{semanticConfigFingerprint},
                   last_submitted_at = COALESCE(#{lastSubmittedAt}, last_submitted_at),
                   last_verified_at = COALESCE(#{lastVerifiedAt}, last_verified_at),
                   last_error_code = #{lastErrorCode},
                   last_error_message = #{lastErrorMessage},
                   row_version = row_version + 1,
                   updated_at = #{updatedAt}
             WHERE provider = #{provider}
               AND document_id = #{documentId}
               AND row_version = #{expectedRowVersion}
            RETURNING *
            """)
    KnowledgeExternalIndexBindingRow updateObservationIfVersionMatches(
            @Param("provider") String provider,
            @Param("documentId") Long documentId,
            @Param("expectedRowVersion") long expectedRowVersion,
            @Param("observedState") String observedState,
            @Param("observedVersion") Long observedVersion,
            @Param("observedChecksum") String observedChecksum,
            @Param("projectionStatus") String projectionStatus,
            @Param("activeOperationId") Long activeOperationId,
            @Param("remoteTaskId") String remoteTaskId,
            @Param("semanticConfigFingerprint") String semanticConfigFingerprint,
            @Param("lastSubmittedAt") OffsetDateTime lastSubmittedAt,
            @Param("lastVerifiedAt") OffsetDateTime lastVerifiedAt,
            @Param("lastErrorCode") String lastErrorCode,
            @Param("lastErrorMessage") String lastErrorMessage,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    /**
     * 在调用方给出的祖先 URI 中取 {@code remote_uri} 最长的一行。
     * 祖先列表必须非空；空列表会生成非法的 {@code IN ()}，由 Store 在调用前拦掉。
     *
     * @param provider   提供方
     * @param remoteUris 命中 URI 的自身及祖先，最长不必排在前面
     * @return 最长匹配行，没有命中时为 null
     */
    @Select("""
            <script>
            SELECT *
              FROM knowledge_external_index_bindings
             WHERE provider = #{provider}
               AND remote_uri IN
               <foreach collection="remoteUris" item="remoteUri" open="(" separator="," close=")">
                   #{remoteUri}
               </foreach>
             ORDER BY char_length(remote_uri) DESC
             LIMIT 1
            </script>
            """)
    KnowledgeExternalIndexBindingRow selectLongestRemoteUriPrefix(
            @Param("provider") String provider,
            @Param("remoteUris") List<String> remoteUris
    );
}
