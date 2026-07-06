package com.wish.rd.exec.repair.health;

import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthSnapshot;
import com.wish.rd.exec.repair.model.ModelHealthState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型健康状态存储器，按 ragent 的 CLOSED/OPEN/HALF_OPEN 语义实现断路器。
 *
 * <p>该类属于 exec 执行层，不依赖 Spring 或具体模型 SDK。调用方用供应商名或模型名作为 ID，
 * 在真实调用前执行 {@link #allowCall(String)}，并在调用结束后标记成功或失败。
 */
public class ModelHealthStore {

    private final ModelCircuitBreakerPolicy policy;
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    /**
     * 创建模型健康状态存储器。
     *
     * @param policy 熔断策略，null 时使用禁用策略
     */
    public ModelHealthStore(ModelCircuitBreakerPolicy policy) {
        this.policy = policy == null ? ModelCircuitBreakerPolicy.disabled() : policy;
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
        ModelHealth health = healthById.get(normalized);
        if (health == null) {
            return false;
        }
        if (health.state == ModelHealthState.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }
        return health.state == ModelHealthState.HALF_OPEN && health.halfOpenInFlight;
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
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(false);
        healthById.compute(normalized, (key, value) -> {
            ModelHealth health = value == null ? new ModelHealth() : value;
            if (health.state == ModelHealthState.OPEN) {
                if (health.openUntil > now) {
                    return health;
                }
                health.state = ModelHealthState.HALF_OPEN;
                health.halfOpenInFlight = true;
                allowed.set(true);
                return health;
            }
            if (health.state == ModelHealthState.HALF_OPEN) {
                if (health.halfOpenInFlight) {
                    return health;
                }
                health.halfOpenInFlight = true;
                allowed.set(true);
                return health;
            }
            allowed.set(true);
            return health;
        });
        return allowed.get();
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
        healthById.compute(normalized, (key, value) -> {
            ModelHealth health = value == null ? new ModelHealth() : value;
            health.state = ModelHealthState.CLOSED;
            health.consecutiveFailures = 0;
            health.openUntil = 0L;
            health.halfOpenInFlight = false;
            return health;
        });
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
        long now = System.currentTimeMillis();
        healthById.compute(normalized, (key, value) -> {
            ModelHealth health = value == null ? new ModelHealth() : value;
            if (health.state == ModelHealthState.HALF_OPEN) {
                health.state = ModelHealthState.OPEN;
                health.openUntil = now + policy.openDurationMillis();
                health.consecutiveFailures = 0;
                health.halfOpenInFlight = false;
                return health;
            }
            health.consecutiveFailures++;
            if (health.consecutiveFailures >= policy.failureThreshold()) {
                health.state = ModelHealthState.OPEN;
                health.openUntil = now + policy.openDurationMillis();
                health.consecutiveFailures = 0;
                health.halfOpenInFlight = false;
            }
            return health;
        });
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
        ModelHealth health = healthById.get(normalized);
        if (health == null) {
            return new ModelHealthSnapshot(normalized, ModelHealthState.CLOSED, 0, 0L, false);
        }
        return health.snapshot(normalized);
    }

    /**
     * 查询所有模型健康快照。
     *
     * @return 按模型 ID 排序后的不可变快照
     */
    public Map<String, ModelHealthSnapshot> snapshots() {
        Map<String, ModelHealthSnapshot> snapshots = new LinkedHashMap<>();
        healthById.keySet().stream()
                .sorted()
                .forEach(id -> snapshots.put(id, snapshot(id)));
        return Map.copyOf(snapshots);
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

    private static final class ModelHealth {
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private ModelHealthState state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.state = ModelHealthState.CLOSED;
        }

        private ModelHealthSnapshot snapshot(String id) {
            return new ModelHealthSnapshot(id, state, consecutiveFailures, openUntil, halfOpenInFlight);
        }
    }
}
