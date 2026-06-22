package com.wish.rd.exec.repair;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 修复记录 JSON 元数据校验与归一化工具。
 */
public final class RepairRecordJson {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private RepairRecordJson() {
    }

    /**
     * 将空 JSON 元数据归一为 {@code {}}，并拒绝非法 JSON。
     *
     * @param value JSON 字符串
     * @return 可写入 JSONB 字段的合法 JSON 字符串
     */
    public static String normalizeMetadataJson(String value) {
        String normalized = value == null || value.isBlank() ? "{}" : value.strip();
        try {
            OBJECT_MAPPER.readTree(normalized);
            return normalized;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("repair metadata json must be valid JSON", exception);
        }
    }
}
