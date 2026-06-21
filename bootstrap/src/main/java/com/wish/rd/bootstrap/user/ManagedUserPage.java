package com.wish.rd.bootstrap.user;

import java.util.List;

/**
 * 后台用户分页结果。
 */
public record ManagedUserPage(
        List<ManagedUser> records,
        int total,
        int size,
        int current,
        int pages
) {
}
