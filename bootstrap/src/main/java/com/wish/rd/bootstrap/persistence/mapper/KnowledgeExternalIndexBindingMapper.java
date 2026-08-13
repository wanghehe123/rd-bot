package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeExternalIndexBindingRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
}
