package com.wish.rd.bootstrap.user.controller.vo;

import java.util.List;

public record UserPageVO<T>(
        List<T> records,
        int total,
        int size,
        int current,
        int pages
) {
}
