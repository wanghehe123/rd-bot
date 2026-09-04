package com.wish.rd.engine.requirement.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;

/** Canonical, digest-verified codec for durable v1/v2/v3 stage execution plans. */
public final class RequirementStageExecutionPlanCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String encodeCanonical(RequirementStageExecutionPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan is required");
        try {
            return CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(plan));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("stage execution plan cannot be encoded", invalid);
        }
    }

    public RequirementStageExecutionPlan decodeAndVerify(String json, String expectedDigest) {
        String canonical = CanonicalJsonSha256.canonicalize(json);
        String actualDigest = digest(canonical);
        if (expectedDigest == null || !actualDigest.equals(expectedDigest.strip().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("stage execution plan digest mismatch");
        }
        try {
            return MAPPER.readValue(canonical, RequirementStageExecutionPlan.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid stage execution plan", invalid);
        }
    }

    public static String digest(String json) {
        return CanonicalJsonSha256.digest(json);
    }
}
