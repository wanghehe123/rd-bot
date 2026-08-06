package com.wish.rd.exec.repair.docker.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines optional Docker hardening and resource boundaries for one container run.
 *
 * <p>The execution adapters select a policy; the Docker runner only translates it
 * into argv. Disabled policies preserve the behavior of existing executors.
 *
 * @param enabled          whether the policy is active
 * @param readOnlyRootfs   whether the container root filesystem is read-only
 * @param capDropAll       whether all Linux capabilities are dropped
 * @param noNewPrivileges  whether privilege escalation is prohibited
 * @param memoryLimit      Docker memory limit, for example {@code 8g}
 * @param cpuLimit         Docker CPU limit, for example {@code 4} or {@code 1.5}
 * @param pidsLimit        maximum number of processes
 * @param runAsUser        container user or uid:gid pair
 * @param tmpfsMounts      tmpfs target path to Docker mount options
 */
public record ContainerSecurityPolicy(
        boolean enabled,
        boolean readOnlyRootfs,
        boolean capDropAll,
        boolean noNewPrivileges,
        String memoryLimit,
        String cpuLimit,
        int pidsLimit,
        String runAsUser,
        Map<String, String> tmpfsMounts
) {

    public ContainerSecurityPolicy {
        memoryLimit = normalize(memoryLimit);
        cpuLimit = normalize(cpuLimit);
        runAsUser = normalize(runAsUser);
        tmpfsMounts = normalizeTmpfs(tmpfsMounts);
        if (enabled) {
            requireMemoryLimit(memoryLimit);
            requireCpuLimit(cpuLimit);
            if (pidsLimit <= 0) {
                throw new IllegalArgumentException("pidsLimit must be positive when security policy is enabled");
            }
            if (!runAsUser.matches("(?:\\d+:\\d+|[a-z_][a-z0-9_-]*)")) {
                throw new IllegalArgumentException(
                        "runAsUser must be a user name or uid:gid when security policy is enabled");
            }
        }
    }

    /**
     * Returns a policy that emits no Docker security arguments.
     *
     * @return disabled policy
     */
    public static ContainerSecurityPolicy disabled() {
        return new ContainerSecurityPolicy(false, false, false, false, "", "", 0, "", Map.of());
    }

    private static void requireMemoryLimit(String value) {
        if (!value.matches("\\d+(?:[kKmMgG](?:[bB])?)?")) {
            throw new IllegalArgumentException(
                    "memoryLimit must use a positive Docker memory value when security policy is enabled");
        }
        if (value.startsWith("0")) {
            throw new IllegalArgumentException("memoryLimit must be positive when security policy is enabled");
        }
    }

    private static void requireCpuLimit(String value) {
        try {
            if (Double.parseDouble(value) <= 0D) {
                throw new IllegalArgumentException("cpuLimit must be positive when security policy is enabled");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "cpuLimit must be a positive number when security policy is enabled",
                    exception
            );
        }
    }

    private static Map<String, String> normalizeTmpfs(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        source.forEach((rawPath, rawOptions) -> {
            String path = normalize(rawPath);
            String options = normalize(rawOptions);
            if (!path.startsWith("/") || path.contains("/../") || path.endsWith("/..") || path.contains("//")) {
                throw new IllegalArgumentException("tmpfs target must be a safe absolute path: " + path);
            }
            if (options.isBlank() || options.contains(":")) {
                throw new IllegalArgumentException("tmpfs options must be non-blank and must not contain ':'");
            }
            if (normalized.putIfAbsent(path, options) != null) {
                throw new IllegalArgumentException("tmpfsMounts contains duplicate target after normalization: " + path);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
