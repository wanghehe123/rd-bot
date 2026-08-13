package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeReconcileFindingRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface KnowledgeReconcileFindingMapper extends BaseMapper<KnowledgeReconcileFindingRow> {

    /**
     * 按身份定位已有发现。{@code document_id} 为空时必须用 {@code IS NULL} 匹配，
     * PostgreSQL UNIQUE 把 NULL 当成互异，不能靠约束吸收孤儿行。
     */
    @Select("""
            SELECT * FROM knowledge_reconcile_findings
             WHERE provider = #{provider}
               AND knowledge_base_id = #{knowledgeBaseId}
               AND finding_type = #{findingType}
               AND remote_uri = #{remoteUri}
               AND (
                    (#{documentId} IS NULL AND document_id IS NULL)
                    OR document_id = #{documentId}
               )
             LIMIT 1
            """)
    KnowledgeReconcileFindingRow findByIdentity(
            @Param("provider") String provider,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("findingType") String findingType,
            @Param("remoteUri") String remoteUri,
            @Param("documentId") Long documentId
    );

    @Select("""
            UPDATE knowledge_reconcile_findings
               SET detail = CASE WHEN #{detail} = '' THEN detail ELSE #{detail} END,
                   status = #{status},
                   last_seen_at = #{lastSeenAt}
             WHERE id = #{id}
            RETURNING *
            """)
    KnowledgeReconcileFindingRow updateLastSeen(
            @Param("id") long id,
            @Param("detail") String detail,
            @Param("status") String status,
            @Param("lastSeenAt") OffsetDateTime lastSeenAt
    );

    @Insert("""
            INSERT INTO knowledge_reconcile_findings (
                id, provider, knowledge_base_id, finding_type, remote_uri, document_id,
                detail, status, first_seen_at, last_seen_at
            ) VALUES (
                #{id}, #{provider}, #{knowledgeBaseId}, #{findingType}, #{remoteUri}, #{documentId},
                #{detail}, #{status}, #{firstSeenAt}, #{lastSeenAt}
            )
            """)
    int insertFinding(KnowledgeReconcileFindingRow row);

    @Select("""
            SELECT * FROM knowledge_reconcile_findings
             WHERE provider = #{provider}
               AND knowledge_base_id = #{knowledgeBaseId}
             ORDER BY last_seen_at DESC, id
            """)
    List<KnowledgeReconcileFindingRow> listByKnowledgeBase(
            @Param("provider") String provider,
            @Param("knowledgeBaseId") long knowledgeBaseId
    );
}
