package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.IngestionPipelineRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 摄取管道 MyBatis-Plus mapper。
 *
 * <p>供 PostgreSQL 摄取管道适配器执行管道配置读写。
 */
@Mapper
public interface IngestionPipelineMapper extends BaseMapper<IngestionPipelineRow> {
}
