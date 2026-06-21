package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.AdminUserRow;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUserRow> {
}
