package com.wish.rd.exec.repair.docker;

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
        Path outputDirectory
) {

    public ContainerRunRequest {
        containerName = requireText(containerName, "containerName");
        image = requireText(image, "image");
        command = requireCommand(command);
        env = normalizeMap(env, "env", true);
        mounts = normalizeMap(mounts, "mounts", false);
        workingDirectory = normalizeText(workingDirectory);
        networkMode = normalizeText(networkMode);
        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory must not be null");
        }
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
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
