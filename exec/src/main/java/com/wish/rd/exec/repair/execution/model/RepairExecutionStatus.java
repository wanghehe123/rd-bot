package com.wish.rd.exec.repair.execution.model;

/**
 * 修复执行状态，供执行器端口返回给 bootstrap 编排桥和持久化适配器使用。
 */
public enum RepairExecutionStatus {
    SUCCESS,
    FAILED,
    NEED_INFO,
    UNSAFE,
    FAILED_VALIDATION
}
