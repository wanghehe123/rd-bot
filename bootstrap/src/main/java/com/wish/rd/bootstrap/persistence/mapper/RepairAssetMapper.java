package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairAssetRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RepairAssetMapper extends BaseMapper<RepairAssetRow> {

    @Insert("""
            INSERT INTO repair_assets (
                id, repair_record_id, asset_type, title, summary, content_json,
                source_artifact_id, reusable, created_at
            )
            VALUES (
                #{id}, #{repairRecordId}, #{assetType}, #{title}, #{summary},
                #{contentJson}::jsonb, #{sourceArtifactId}, #{reusable}, #{createdAt}
            )
            """)
    void insertAsset(RepairAssetRow row);
}
