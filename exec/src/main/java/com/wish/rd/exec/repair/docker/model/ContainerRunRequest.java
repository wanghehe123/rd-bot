package com.wish.rd.exec.repair.docker.model;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次容器执行请求，供 Docker 适配器和测试 fake runner 共享命令、挂载和输出目录协议。
 *
 * @param containerName   容器名称
 * @param image           容器镜像
 * @param command         容器内执行命令，yolo 模式通过命令参数表达
 * @param env             容器环境变量
 * @param mounts          本地路径到容器路径的挂载映射
 * @param workingDirectory 容器内工作目录
 * @param networkMode     网络模式
 * @param removeAfterExit 退出后是否删除容器
 * @param allowPrivileged 是否允许特权模式
 * @param outputDirectory 标准协议输出目录
 * @param initEnabled      是否使用 Docker init 处理浏览器子进程
 * @param sharedMemorySize 容器共享内存大小，例如 {@code 1g}
 * @param executionTimeoutMillis 容器执行硬超时；{@code 0} 表示不设硬超时
 * @param securityPolicy   Docker 安全与资源边界；禁用时保持既有行为
 */
public record ContainerRunRequest(
        String containerName,
        String image,
        List<String> command,
        Map<String, String> env,
        Map<String, String> mounts,
        String workingDirectory,
        String networkMode,
        boolean removeAfterExit,
        boolean allowPrivileged,
        Path outputDirectory,
        boolean initEnabled,
        String sharedMemorySize,
        long executionTimeoutMillis,
        ContainerSecurityPolicy securityPolicy
) {

    public ContainerRunRequest {
        containerName = requireText(containerName, "containerName");
        image = requireText(image, "image");
        command = requireCommand(command);
        env = normalizeMap(env, "env", true);
        mounts = normalizeMap(mounts, "mounts", false);
        workingDirectory = normalizeText(workingDirectory);
        networkMode = normalizeText(networkMode);
        sharedMemorySize = normalizeText(sharedMemorySize);
        executionTimeoutMillis = Math.max(0L, executionTimeoutMillis);
        securityPolicy = securityPolicy == null ? ContainerSecurityPolicy.disabled() : securityPolicy;
        if (securityPolicy.enabled() && allowPrivileged) {
            throw new IllegalArgumentException(
                    "allowPrivileged is incompatible with an enabled container security policy");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory must not be null");
        }
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
    }

    /**
     * Backward-compatible request constructor without an explicit security policy.
     */
    public ContainerRunRequest(
            String containerName,
            String image,
            List<String> command,
            Map<String, String> env,
            Map<String, String> mounts,
            String workingDirectory,
            String networkMode,
            boolean removeAfterExit,
            boolean allowPrivileged,
            Path outputDirectory,
            boolean initEnabled,
            String sharedMemorySize,
            long executionTimeoutMillis
    ) {
        this(
                containerName,
                image,
                command,
                env,
                mounts,
                workingDirectory,
                networkMode,
                removeAfterExit,
                allowPrivileged,
                outputDirectory,
                initEnabled,
                sharedMemorySize,
                executionTimeoutMillis,
                ContainerSecurityPolicy.disabled()
        );
    }

    /**
     * Backward-compatible request constructor for browser workloads without a hard timeout.
     */
    public ContainerRunRequest(
            String containerName,
            String image,
            List<String> command,
            Map<String, String> env,
            Map<String, String> mounts,
            String workingDirectory,
            String networkMode,
            boolean removeAfterExit,
            boolean allowPrivileged,
            Path outputDirectory,
            boolean initEnabled,
            String sharedMemorySize
    ) {
        this(
                containerName,
                image,
                command,
                env,
                mounts,
                workingDirectory,
                networkMode,
                removeAfterExit,
                allowPrivileged,
                outputDirectory,
                initEnabled,
                sharedMemorySize,
                0L
        );
    }

    /**
     * Backward-compatible request constructor for non-browser workloads.
     */
    public ContainerRunRequest(
            String containerName,
            String image,
            List<String> command,
            Map<String, String> env,
            Map<String, String> mounts,
            String workingDirectory,
            String networkMode,
            boolean removeAfterExit,
            boolean allowPrivileged,
            Path outputDirectory
    ) {
        this(
                containerName,
                image,
                command,
                env,
                mounts,
                workingDirectory,
                networkMode,
                removeAfterExit,
                allowPrivileged,
                outputDirectory,
                false,
                "",
                0L
        );
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> requireCommand(List<String> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        List<String> normalized = source.stream()
                .map(ContainerRunRequest::normalizeText)
                .toList();
        if (normalized.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("command entries must not be blank");
        }
        return List.copyOf(normalized);
    }

    private static Map<String, String> normalizeMap(Map<String, String> source, String fieldName, boolean allowBlankValue) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = normalizeText(key);
            String normalizedValue = normalizeText(value);
            if (normalizedKey.isBlank()) {
                throw new IllegalArgumentException(fieldName + " keys must not be blank");
            }
            if (!allowBlankValue && normalizedValue.isBlank()) {
                throw new IllegalArgumentException(fieldName + " values must not be blank");
            }
            if (normalized.containsKey(normalizedKey)) {
                throw new IllegalArgumentException(fieldName + " contains duplicate key after normalization: " + normalizedKey);
            }
            normalized.put(normalizedKey, normalizedValue);
        });
        return Map.copyOf(normalized);
    }
}
