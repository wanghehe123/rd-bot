package com.wish.rd.bootstrap.user.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("admin_users")
public class AdminUserDO {

    @TableId(type = IdType.INPUT)
    public Long id;
    public String username;
    public String role;
    public String avatar;
    public String passwordHash;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
