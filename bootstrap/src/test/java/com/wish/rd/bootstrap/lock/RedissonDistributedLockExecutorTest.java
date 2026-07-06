package com.wish.rd.bootstrap.lock;

import com.wish.rd.bootstrap.lock.impl.RedissonDistributedLockExecutor;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedissonDistributedLockExecutor} 单元测试。
 */
class RedissonDistributedLockExecutorTest {

    @Test
    void shouldLockAndUnlockAroundAction() {
        RecordingRLock recordingLock = new RecordingRLock();
        AtomicReference<String> lockName = new AtomicReference<>();
        RedissonDistributedLockExecutor executor = new RedissonDistributedLockExecutor(
                redissonClient(lockName, recordingLock.proxy())
        );

        String result = executor.execute("rd-bot:lock:test", () -> {
            assertTrue(recordingLock.locked());
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals("rd-bot:lock:test", lockName.get());
        assertEquals(1, recordingLock.lockCalls());
        assertEquals(1, recordingLock.unlockCalls());
        assertFalse(recordingLock.locked());
    }

    @Test
    void shouldUnlockWhenActionThrows() {
        RecordingRLock recordingLock = new RecordingRLock();
        RedissonDistributedLockExecutor executor = new RedissonDistributedLockExecutor(
                redissonClient(new AtomicReference<>(), recordingLock.proxy())
        );

        assertThrows(IllegalStateException.class, () -> executor.execute("rd-bot:lock:test", () -> {
            throw new IllegalStateException("failed");
        }));

        assertEquals(1, recordingLock.lockCalls());
        assertEquals(1, recordingLock.unlockCalls());
        assertFalse(recordingLock.locked());
    }

    private RedissonClient redissonClient(AtomicReference<String> lockName, RLock lock) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getLock".equals(method.getName())) {
                lockName.set((String) args[0]);
                return lock;
            }
            return defaultValue(method.getReturnType());
        };
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[] { RedissonClient.class },
                handler
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Character.TYPE) {
            return '\0';
        }
        return null;
    }

    private final class RecordingRLock {

        private final AtomicBoolean locked = new AtomicBoolean(false);
        private final AtomicInteger lockCalls = new AtomicInteger();
        private final AtomicInteger unlockCalls = new AtomicInteger();

        private RLock proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "lock" -> {
                    locked.set(true);
                    lockCalls.incrementAndGet();
                    yield null;
                }
                case "unlock" -> {
                    unlockCalls.incrementAndGet();
                    locked.set(false);
                    yield null;
                }
                case "isHeldByCurrentThread" -> locked.get();
                default -> defaultValue(method.getReturnType());
            };
            return (RLock) Proxy.newProxyInstance(
                    RLock.class.getClassLoader(),
                    new Class<?>[] { RLock.class },
                    handler
            );
        }

        private boolean locked() {
            return locked.get();
        }

        private int lockCalls() {
            return lockCalls.get();
        }

        private int unlockCalls() {
            return unlockCalls.get();
        }
    }
}
