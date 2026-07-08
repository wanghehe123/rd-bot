package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.IntentNodeRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 意图节点 MyBatis-Plus mapper。
 *
 * <p>供 PostgreSQL 意图树适配器执行节点配置读写。
 */
@Mapper
public interface IntentNodeMapper extends BaseMapper<IntentNodeRow> {
}
