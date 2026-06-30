package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskMaterialRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * RD 任务材料 MyBatis-Plus mapper。
 */
@Mapper
public interface RdTaskMaterialMapper extends BaseMapper<RdTaskMaterialRow> {

    /**
     * 插入或更新任务材料。
     *
     * @param row 材料行
     */
    @Insert("""
            INSERT INTO rd_task_materials (
                id, task_id, material_type, source_type, title, source_uri, mime_type,
                content_hash, content_preview, artifact_uri, knowledge_document_id,
                revision_id, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{taskId}, #{materialType}, #{sourceType}, #{title}, #{sourceUri}, #{mimeType},
                #{contentHash}, #{contentPreview}, #{artifactUri}, #{knowledgeDocumentId},
                #{revisionId}, #{metadataJson}::jsonb, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                task_id = EXCLUDED.task_id,
                material_type = EXCLUDED.material_type,
                source_type = EXCLUDED.source_type,
                title = EXCLUDED.title,
                source_uri = EXCLUDED.source_uri,
                mime_type = EXCLUDED.mime_type,
                content_hash = EXCLUDED.content_hash,
                content_preview = EXCLUDED.content_preview,
                artifact_uri = EXCLUDED.artifact_uri,
                knowledge_document_id = EXCLUDED.knowledge_document_id,
                revision_id = EXCLUDED.revision_id,
                metadata_json = EXCLUDED.metadata_json,
                updated_at = EXCLUDED.updated_at
            """)
    void upsertMaterial(RdTaskMaterialRow row);
}
