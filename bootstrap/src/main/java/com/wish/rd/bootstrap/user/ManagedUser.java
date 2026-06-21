package com.wish.rd.bootstrap.user;

/**
 * 后台用户聚合视图。
 */
public record ManagedUser(
        String id,
        String username,
        String role,
        String avatar,
        String createTime,
        String updateTime
) {
}
