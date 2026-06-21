package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairRecordArtifactRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RepairRecordArtifactMapper extends BaseMapper<RepairRecordArtifactRow> {

    @Insert("""
            INSERT INTO repair_record_artifacts (
                id, repair_record_id, artifact_type, artifact_uri, summary,
                content_hash, extension_json, created_at
            )
            VALUES (
                #{id}, #{repairRecordId}, #{artifactType}, #{artifactUri}, #{summary},
                #{contentHash}, #{extensionJson}::jsonb, #{createdAt}
            )
            """)
    void insertArtifact(RepairRecordArtifactRow row);
}
