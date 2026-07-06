package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdProjectRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * RD 项目 MyBatis-Plus mapper。
 *
 * <p>供 PostgreSQL 项目管理适配器执行项目配置读写。
 */
@Mapper
public interface RdProjectMapper extends BaseMapper<RdProjectRow> {
}
