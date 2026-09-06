package com.wish.rd.engine.requirement.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;

import java.util.List;
import java.util.Locale;

/**
 * Immutable Host Manager decision persisted with its continuation.
 *
 * @param taskId task id
 * @param roundNo 1-based round allocated at finalize
 * @param sourceCommandId audited command that triggered this Manager hop
 * @param stateVersion head version at decide time
 * @param stateHash head hash at decide time
 * @param route chosen route
 * @param targetRecordIds bounded record ids, empty when not a gap fix
 * @param boundedContract operator-visible contract text
 * @param executorRoute next executor role or infrastructure stage name
 * @param rationale short reason
 * @param decisionHash canonical digest of the decision payload
 */
public record ManagerDecision(
        String taskId,
        int roundNo,
        String sourceCommandId,
        long stateVersion,
        String stateHash,
        ManagerRoute route,
        List<String> targetRecordIds,
        String boundedContract,
        String executorRoute,
        String rationale,
        String decisionHash
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ManagerDecision {
        taskId = require(taskId, "taskId");
        if (roundNo < 1) {
            throw new IllegalArgumentException("roundNo must be >= 1");
        }
        sourceCommandId = require(sourceCommandId, "sourceCommandId");
        if (stateVersion < 1L) {
            throw new IllegalArgumentException("stateVersion must be >= 1");
        }
        stateHash = require(stateHash, "stateHash");
        if (route == null) {
            throw new IllegalArgumentException("route is required");
        }
        targetRecordIds = targetRecordIds == null ? List.of() : List.copyOf(targetRecordIds);
        if (targetRecordIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("targetRecordIds must not contain blank ids");
        }
        boundedContract = boundedContract == null ? "" : boundedContract.strip();
        executorRoute = executorRoute == null ? "" : executorRoute.strip();
        rationale = rationale == null ? "" : rationale.strip();
        if (route == ManagerRoute.EXECUTE && executorRoute.isBlank()) {
            throw new IllegalArgumentException("EXECUTE requires executorRoute");
        }
        if (route == ManagerRoute.DONE) {
            executorRoute = "DETERMINISTIC_REVIEW";
        }
        if ((route == ManagerRoute.ASK || route == ManagerRoute.BLOCKED) && !executorRoute.isBlank()) {
            throw new IllegalArgumentException(route + " must not carry executorRoute");
        }
        if (route == ManagerRoute.EXECUTE && "CODING_AGENT".equals(executorRoute) && targetRecordIds.isEmpty()) {
            throw new IllegalArgumentException("bounded coding EXECUTE must name targetRecordIds");
        }
        String expected = digestPayload(taskId, roundNo, sourceCommandId, stateVersion, stateHash, route,
                targetRecordIds, boundedContract, executorRoute, rationale);
        if (decisionHash == null || decisionHash.isBlank()) {
            decisionHash = expected;
        } else if (!expected.equals(decisionHash.strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("decisionHash mismatch");
        } else {
            decisionHash = decisionHash.strip().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Builds a decision and fills {@code decisionHash}.
     */
    public static ManagerDecision of(
            String taskId,
            int roundNo,
            String sourceCommandId,
            long stateVersion,
            String stateHash,
            ManagerRoute route,
            List<String> targetRecordIds,
            String boundedContract,
            String executorRoute,
            String rationale
    ) {
        return new ManagerDecision(
                taskId, roundNo, sourceCommandId, stateVersion, stateHash, route,
                targetRecordIds, boundedContract, executorRoute, rationale, "");
    }

    /**
     * Returns a copy with a Host-assigned round number.
     *
     * @param assignedRound allocated round
     * @return decision with {@code assignedRound}
     */
    public ManagerDecision withRoundNo(int assignedRound) {
        if (assignedRound == roundNo) {
            return this;
        }
        return of(taskId, assignedRound, sourceCommandId, stateVersion, stateHash, route,
                targetRecordIds, boundedContract, executorRoute, rationale);
    }

    private static String digestPayload(
            String taskId,
            int roundNo,
            String sourceCommandId,
            long stateVersion,
            String stateHash,
            ManagerRoute route,
            List<String> targetRecordIds,
            String boundedContract,
            String executorRoute,
            String rationale
    ) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("taskId", taskId);
            node.put("roundNo", roundNo);
            node.put("sourceCommandId", sourceCommandId);
            node.put("stateVersion", stateVersion);
            node.put("stateHash", stateHash);
            node.put("route", route.name());
            ArrayNode ids = node.putArray("targetRecordIds");
            for (String id : targetRecordIds) {
                ids.add(id);
            }
            node.put("boundedContract", boundedContract == null ? "" : boundedContract);
            node.put("executorRoute", executorRoute == null ? "" : executorRoute);
            node.put("rationale", rationale == null ? "" : rationale);
            return CanonicalJsonSha256.digest(MAPPER.writeValueAsString(node));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("manager decision cannot be digested", invalid);
        }
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
