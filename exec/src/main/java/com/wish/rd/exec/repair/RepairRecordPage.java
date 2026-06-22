package com.wish.rd.exec.repair;

import java.util.List;

/**
 * 修复记录分页结果。
 *
 * @param records  当前页记录
 * @param page     当前页码
 * @param pageSize 页大小
 * @param total    满足条件的总数
 */
public record RepairRecordPage(
        List<RepairRecord> records,
        int page,
        int pageSize,
        long total
) {

    public RepairRecordPage {
        records = records == null ? List.of() : List.copyOf(records);
    }

    /**
     * 构造空页。
     *
     * @return 空分页
     */
    public static RepairRecordPage empty(int page, int pageSize) {
        return new RepairRecordPage(List.of(), page, pageSize, 0L);
    }
}
