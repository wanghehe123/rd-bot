package com.wish.rd.exec.repair.health;

import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthSnapshot;
import com.wish.rd.exec.repair.model.ModelHealthState;
import com.wish.rd.exec.repair.health.impl.InMemoryModelHealthStateStore;

import java.util.Map;
import java.util.Objects;

/**
 * 模型健康状态存储器，按 CLOSED/OPEN/HALF_OPEN 语义实现断路器。
 *
 * <p>该类属于 exec 执行层，不依赖 Spring 或具体模型 SDK。调用方用供应商名或模型名作为 ID，
 * 在真实调用前执行 {@link #allowCall(String)}，并在调用结束后标记成功或失败。
 */
public class ModelHealthStore {

    private final ModelCircuitBreakerPolicy policy;
    private final ModelHealthStateStore stateStore;

    /**
     * 创建模型健康状态存储器。
     *
     * @param policy 熔断策略，null 时使用禁用策略
     */
    public ModelHealthStore(ModelCircuitBreakerPolicy policy) {
        this(policy, new InMemoryModelHealthStateStore());
    }

    /**
     * Creates a circuit breaker backed by the supplied atomic state store.
     *
     * @param policy circuit-breaker policy, or disabled when {@code null}
     * @param stateStore shared state store; production supplies Redis
     */
    public ModelHealthStore(ModelCircuitBreakerPolicy policy, ModelHealthStateStore stateStore) {
        this.policy = policy == null ? ModelCircuitBreakerPolicy.disabled() : policy;
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
    }

    /**
     * 判断模型是否暂不可用。
     *
     * @param id 模型或供应商 ID
     * @return OPEN 未到期或 HALF_OPEN 有探测在途时返回 true
     */
    public boolean isUnavailable(String id) {
        if (!policy.enabled()) {
            return false;
        }
        String normalized = normalizeId(id);
        if (normalized.isBlank()) {
            return false;
        }
        return stateStore.isUnavailable(normalized, System.currentTimeMillis());
    }

    /**
     * 尝试获取一次模型调用许可。
     *
     * @param id 模型或供应商 ID
     * @return 允许调用返回 true
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean allowCall(String id) {
        String normalized = normalizeId(id);
        if (normalized.isBlank()) {
            return false;
        }
        if (!policy.enabled()) {
            return true;
        }
        return stateStore.tryAcquireCall(normalized, policy, System.currentTimeMillis());
    }

    /**
     * 标记一次成功调用，关闭熔断并清空失败计数。
     *
     * @param id 模型或供应商 ID
     */
    public void markSuccess(String id) {
        String normalized = normalizeId(id);
        if (!policy.enabled() || normalized.isBlank()) {
            return;
        }
        stateStore.markSuccess(normalized);
    }

    /**
     * 标记一次失败调用，达到阈值后打开熔断。
     *
     * @param id 模型或供应商 ID
     */
    public void markFailure(String id) {
        String normalized = normalizeId(id);
        if (!policy.enabled() || normalized.isBlank()) {
            return;
        }
        stateStore.markFailure(normalized, policy, System.currentTimeMillis());
    }

    /**
     * 查询单个模型健康快照。
     *
     * @param id 模型或供应商 ID
     * @return 当前健康快照，未出现过的 ID 返回 CLOSED 快照
     */
    public ModelHealthSnapshot snapshot(String id) {
        String normalized = normalizeId(id);
        if (normalized.isBlank()) {
            return new ModelHealthSnapshot("", ModelHealthState.CLOSED, 0, 0L, false);
        }
        return stateStore.snapshot(normalized, System.currentTimeMillis());
    }

    /**
     * 查询所有模型健康快照。
     *
     * @return 按模型 ID 排序后的不可变快照
     */
    public Map<String, ModelHealthSnapshot> snapshots() {
        if (!policy.enabled()) {
            return Map.of();
        }
        return stateStore.snapshots(System.currentTimeMillis());
    }

    /**
     * 返回当前熔断策略。
     *
     * @return 模型熔断策略
     */
    public ModelCircuitBreakerPolicy policy() {
        return policy;
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.strip();
    }

}
