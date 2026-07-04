package com.wish.rd.bootstrap;

import java.nio.file.Path;
import java.util.List;

final class ProductionSmokePreconditions {

    private ProductionSmokePreconditions() {
    }

    static void requireReady(String smokeName, List<String> missingProperties, Path reportPath) {
        List<String> missing = missingProperties == null ? List.of() : List.copyOf(missingProperties);
        if (!missing.isEmpty()) {
            throw new AssertionError(smokeName + " production smoke requires real properties: "
                    + missing + "; report=" + reportPath.toAbsolutePath());
        }
    }
}
