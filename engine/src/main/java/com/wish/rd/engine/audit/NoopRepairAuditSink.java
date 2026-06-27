package com.wish.rd.engine.audit;

/**
 * 空审计 sink，供单测和未配置持久化时保持调用方零配置可运行。
 */
public final class NoopRepairAuditSink implements RepairAuditSinkPort {

    private static final NoopRepairAuditSink INSTANCE = new NoopRepairAuditSink();

    private NoopRepairAuditSink() {
    }

    /**
     * 返回单例空实现。
     *
     * @return 空审计 sink
     */
    public static NoopRepairAuditSink instance() {
        return INSTANCE;
    }

    @Override
    public void publish(RepairAuditEvent event) {
        // no-op
    }
}
