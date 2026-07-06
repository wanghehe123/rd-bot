package com.wish.rd.exec.repair.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExecutionJsonMaps {

    private ExecutionJsonMaps() {
    }

    public static Map<String, String> copy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("metadata key must not be null");
            }
            copy.put(key, value == null ? "" : value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
