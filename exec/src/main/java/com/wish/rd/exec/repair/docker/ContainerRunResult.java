package com.wish.rd.exec.repair.docker;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次容器执行结果，供后续执行器读取退出码、日志文本和标准协议产物引用。
 *
 * @param exitCode         容器进程退出码
 * @param durationMillis   容器运行耗时毫秒
 * @param stdout           标准输出摘要
 * @param stderr           标准错误摘要
 * @param resultJson       result.json 产物路径
 * @param patchDiff        patch.diff 产物路径
 * @param testLog          test.log 产物路径
 * @param claudeEventsJsonl claude-events.jsonl 产物路径
 * @param dockerMetaJson   docker-meta.json 产物路径
 * @param metadata         后续执行器测试需要的容器元数据
 */
public record ContainerRunResult(
        int exitCode,
        long durationMillis,
        String stdout,
        String stderr,
        Path resultJson,
        Path patchDiff,
        Path testLog,
        Path claudeEventsJsonl,
        Path dockerMetaJson,
        Map<String, String> metadata
) {

    public ContainerRunResult {
        durationMillis = Math.max(0L, durationMillis);
        stdout = normalizeText(stdout);
        stderr = normalizeText(stderr);
        resultJson = normalizePath(resultJson);
        patchDiff = normalizePath(patchDiff);
        testLog = normalizePath(testLog);
        claudeEventsJsonl = normalizePath(claudeEventsJsonl);
        dockerMetaJson = normalizePath(dockerMetaJson);
        metadata = normalizeMap(metadata, "metadata");
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }

    private static Path normalizePath(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static Map<String, String> normalizeMap(Map<String, String> source, String fieldName) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = normalizeText(key);
            if (normalizedKey.isBlank()) {
                throw new IllegalArgumentException(fieldName + " keys must not be blank");
            }
            if (normalized.containsKey(normalizedKey)) {
                throw new IllegalArgumentException(fieldName + " contains duplicate key after normalization: " + normalizedKey);
            }
            normalized.put(normalizedKey, normalizeText(value));
        });
        return Map.copyOf(normalized);
    }
}
