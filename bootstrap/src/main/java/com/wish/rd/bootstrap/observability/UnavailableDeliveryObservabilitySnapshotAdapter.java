package com.wish.rd.bootstrap.observability;

import com.wish.rd.engine.admin.observability.DeliveryObservabilitySnapshotPort;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fallback when PostgreSQL is not the knowledge store. Queries stay available and report
 * collector failure instead of blocking application startup.
 *
 * <p>该兜底与 {@link PostgresDeliveryObservabilitySnapshotAdapter} 必须由同一个属性严格互斥地选择。
 * 类扫描组件上的 {@code @ConditionalOnMissingBean}/{@code @ConditionalOnBean} 求值时机取决于扫描
 * 顺序，会出现两个实现同时不注册、上下文启动失败的情况，因此这里只允许使用属性条件。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class UnavailableDeliveryObservabilitySnapshotAdapter implements DeliveryObservabilitySnapshotPort {

    @Override
    public DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query) {
        return DeliveryLedgerSnapshot.failed(Instant.now(), "delivery ledger unavailable");
    }
}
