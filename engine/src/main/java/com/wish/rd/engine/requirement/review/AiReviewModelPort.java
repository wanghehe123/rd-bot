package com.wish.rd.engine.requirement.review;

import java.util.List;

/** External structured-model boundary for part review and final delivery judgment. */
@FunctionalInterface
public interface AiReviewModelPort {

    ModelResponse review(ModelRequest request);

    static AiReviewModelPort unavailable(String reason) {
        return request -> ModelResponse.unavailable("", "MODEL_UNAVAILABLE", reason);
    }

    record ModelRequest(
            String runId,
            String taskId,
            String mode,
            int partNo,
            int partCount,
            List<String> sourceIds,
            String content
    ) {
        public ModelRequest {
            runId = safe(runId);
            taskId = safe(taskId);
            mode = safe(mode).toUpperCase();
            partNo = Math.max(0, partNo);
            partCount = Math.max(1, partCount);
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            content = content == null ? "" : content;
        }
    }

    record ModelResponse(
            boolean available,
            String modelName,
            String rawJson,
            String errorCategory,
            String reason
    ) {
        public ModelResponse {
            modelName = safe(modelName);
            rawJson = rawJson == null ? "" : rawJson.strip();
            errorCategory = safe(errorCategory);
            reason = safe(reason);
        }

        public static ModelResponse available(String modelName, String rawJson) {
            return new ModelResponse(true, modelName, rawJson, "", "");
        }

        public static ModelResponse unavailable(String modelName, String errorCategory, String reason) {
            return new ModelResponse(false, modelName, "", errorCategory, reason);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
