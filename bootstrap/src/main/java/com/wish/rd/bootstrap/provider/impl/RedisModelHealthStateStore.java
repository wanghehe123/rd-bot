package com.wish.rd.bootstrap.provider.impl;

import com.wish.rd.exec.repair.health.ModelHealthStateStore;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthSnapshot;
import com.wish.rd.exec.repair.model.ModelHealthState;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redis-backed atomic Provider circuit state shared by every executor instance. */
public final class RedisModelHealthStateStore implements ModelHealthStateStore {

    private static final String DEFAULT_KEY_PREFIX = "rd-bot:model-health";

    private static final String TRY_ACQUIRE_LUA = """
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[1], 'model_id', ARGV[1])
            redis.call('HSETNX', KEYS[1], 'version', 0)
            local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
            local now = tonumber(ARGV[2])
            local leaseMillis = tonumber(ARGV[3])
            if state == 'OPEN' then
              local openUntil = tonumber(redis.call('HGET', KEYS[1], 'open_until') or '0')
              if openUntil > now then return 0 end
              redis.call('HSET', KEYS[1], 'state', 'HALF_OPEN', 'half_open_until', now + leaseMillis)
              redis.call('HINCRBY', KEYS[1], 'version', 1)
              return 1
            end
            if state == 'HALF_OPEN' then
              local halfOpenUntil = tonumber(redis.call('HGET', KEYS[1], 'half_open_until') or '0')
              if halfOpenUntil > now then return 0 end
              redis.call('HSET', KEYS[1], 'half_open_until', now + leaseMillis)
              redis.call('HINCRBY', KEYS[1], 'version', 1)
              return 1
            end
            redis.call('HSETNX', KEYS[1], 'state', 'CLOSED')
            redis.call('HSETNX', KEYS[1], 'failures', 0)
            return 1
            """;

    private static final String IS_UNAVAILABLE_LUA = """
            local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
            local now = tonumber(ARGV[1])
            if state == 'OPEN' then
              return tonumber(redis.call('HGET', KEYS[1], 'open_until') or '0') > now and 1 or 0
            end
            if state == 'HALF_OPEN' then
              return tonumber(redis.call('HGET', KEYS[1], 'half_open_until') or '0') > now and 1 or 0
            end
            return 0
            """;

    private static final String MARK_SUCCESS_LUA = """
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[1],
              'model_id', ARGV[1],
              'state', 'CLOSED',
              'failures', 0,
              'open_until', 0,
              'half_open_until', 0)
            redis.call('HINCRBY', KEYS[1], 'version', 1)
            return 1
            """;

    private static final String MARK_FAILURE_LUA = """
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[1], 'model_id', ARGV[1])
            redis.call('HSETNX', KEYS[1], 'version', 0)
            local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
            local now = tonumber(ARGV[2])
            local threshold = tonumber(ARGV[3])
            local openMillis = tonumber(ARGV[4])
            if state == 'HALF_OPEN' or state == 'OPEN' then
              redis.call('HSET', KEYS[1],
                'state', 'OPEN',
                'failures', 0,
                'open_until', now + openMillis,
                'half_open_until', 0)
              redis.call('HINCRBY', KEYS[1], 'version', 1)
              return 1
            end
            local failures = tonumber(redis.call('HGET', KEYS[1], 'failures') or '0') + 1
            if failures >= threshold then
              redis.call('HSET', KEYS[1],
                'state', 'OPEN',
                'failures', 0,
                'open_until', now + openMillis,
                'half_open_until', 0)
            else
              redis.call('HSET', KEYS[1], 'state', 'CLOSED', 'failures', failures)
            end
            redis.call('HINCRBY', KEYS[1], 'version', 1)
            return 1
            """;

    private final RedissonClient redissonClient;
    private final RScript script;
    private final String keyPrefix;
    private final String indexKey;

    /**
     * Creates the production Redis state store with the standard key namespace.
     *
     * @param redissonClient shared Redis client
     */
    public RedisModelHealthStateStore(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_KEY_PREFIX);
    }

    /**
     * Creates a Redis state store under a caller-supplied namespace.
     *
     * @param redissonClient shared Redis client
     * @param keyPrefix key namespace, useful for isolated integration tests
     */
    public RedisModelHealthStateStore(RedissonClient redissonClient, String keyPrefix) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.script = redissonClient.getScript(StringCodec.INSTANCE);
        String normalized = keyPrefix == null ? "" : keyPrefix.strip();
        this.keyPrefix = normalized.isBlank() ? DEFAULT_KEY_PREFIX : normalized;
        this.indexKey = this.keyPrefix + ":index";
    }

    @Override
    public boolean isUnavailable(String modelId, long nowEpochMillis) {
        Long result = script.eval(
                RScript.Mode.READ_ONLY,
                IS_UNAVAILABLE_LUA,
                RScript.ReturnType.LONG,
                List.of(stateKey(modelId)),
                Long.toString(nowEpochMillis)
        );
        return result != null && result == 1L;
    }

    @Override
    public boolean tryAcquireCall(
            String modelId,
            ModelCircuitBreakerPolicy policy,
            long nowEpochMillis
    ) {
        Long result = script.eval(
                RScript.Mode.READ_WRITE,
                TRY_ACQUIRE_LUA,
                RScript.ReturnType.LONG,
                List.of(stateKey(modelId), indexKey),
                normalizeId(modelId),
                Long.toString(nowEpochMillis),
                Long.toString(policy.openDurationMillis())
        );
        return result != null && result == 1L;
    }

    @Override
    public void markSuccess(String modelId) {
        script.eval(
                RScript.Mode.READ_WRITE,
                MARK_SUCCESS_LUA,
                RScript.ReturnType.LONG,
                List.of(stateKey(modelId), indexKey),
                normalizeId(modelId)
        );
    }

    @Override
    public void markFailure(
            String modelId,
            ModelCircuitBreakerPolicy policy,
            long nowEpochMillis
    ) {
        script.eval(
                RScript.Mode.READ_WRITE,
                MARK_FAILURE_LUA,
                RScript.ReturnType.LONG,
                List.of(stateKey(modelId), indexKey),
                normalizeId(modelId),
                Long.toString(nowEpochMillis),
                Integer.toString(policy.failureThreshold()),
                Long.toString(policy.openDurationMillis())
        );
    }

    @Override
    public ModelHealthSnapshot snapshot(String modelId, long nowEpochMillis) {
        String normalized = normalizeId(modelId);
        RMap<String, String> state = redissonClient.getMap(stateKey(normalized), StringCodec.INSTANCE);
        if (!state.isExists()) {
            return closedSnapshot(normalized);
        }
        ModelHealthState healthState = parseState(state.get("state"));
        long halfOpenUntil = parseLong(state.get("half_open_until"));
        return new ModelHealthSnapshot(
                normalized,
                healthState,
                parseInt(state.get("failures")),
                parseLong(state.get("open_until")),
                healthState == ModelHealthState.HALF_OPEN && halfOpenUntil > nowEpochMillis
        );
    }

    @Override
    public Map<String, ModelHealthSnapshot> snapshots(long nowEpochMillis) {
        RSet<String> index = redissonClient.getSet(indexKey, StringCodec.INSTANCE);
        Map<String, ModelHealthSnapshot> snapshots = new LinkedHashMap<>();
        index.readAll().stream()
                .sorted()
                .forEach(modelId -> snapshots.put(modelId, snapshot(modelId, nowEpochMillis)));
        return Map.copyOf(snapshots);
    }

    private String stateKey(String modelId) {
        return keyPrefix + ":state:" + sha256(normalizeId(modelId));
    }

    private static String normalizeId(String modelId) {
        String normalized = modelId == null ? "" : modelId.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ModelHealthState parseState(String value) {
        try {
            return ModelHealthState.valueOf(value == null ? "CLOSED" : value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid Redis model health state: " + value, exception);
        }
    }

    private static int parseInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value == null ? "0" : value));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("invalid Redis model health integer: " + value, exception);
        }
    }

    private static long parseLong(String value) {
        try {
            return Math.max(0L, Long.parseLong(value == null ? "0" : value));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("invalid Redis model health long: " + value, exception);
        }
    }

    private static ModelHealthSnapshot closedSnapshot(String modelId) {
        return new ModelHealthSnapshot(modelId, ModelHealthState.CLOSED, 0, 0L, false);
    }
}
