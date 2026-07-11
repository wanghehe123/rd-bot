package com.wish.rd.engine.draft;

import com.wish.rd.engine.draft.model.TaskDraftRequest;

/** Structured task draft model boundary. */
@FunctionalInterface
public interface TaskDraftModelPort {
    ModelResponse complete(TaskDraftRequest request);

    static TaskDraftModelPort unavailable(String reason) {
        return request -> ModelResponse.unavailable(reason);
    }

    record ModelResponse(boolean available, String rawJson, String reason) {
        public ModelResponse {
            rawJson = rawJson == null ? "" : rawJson;
            reason = reason == null ? "" : reason.strip();
        }
        public static ModelResponse available(String rawJson) { return new ModelResponse(true, rawJson, ""); }
        public static ModelResponse unavailable(String reason) { return new ModelResponse(false, "", reason); }
    }
}
