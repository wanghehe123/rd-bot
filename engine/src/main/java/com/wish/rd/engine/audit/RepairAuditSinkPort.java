package com.wish.rd.engine.audit;

/**
 * 修复审计事件输出端口，bootstrap 可适配为 PostgreSQL、日志或通知系统。
 */
@FunctionalInterface
public interface RepairAuditSinkPort {

    /**
     * 发布一条修复审计事件。
     *
     * @param event 审计事件
     */
    void publish(RepairAuditEvent event);
}
