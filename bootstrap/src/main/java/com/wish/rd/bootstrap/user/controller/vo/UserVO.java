package com.wish.rd.bootstrap.user.controller.vo;

public record UserVO(
        String id,
        String username,
        String role,
        String avatar,
        String createTime,
        String updateTime
) {
}
