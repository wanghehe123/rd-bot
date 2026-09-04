package com.wish.rd.engine.requirement.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.util.ArrayList;
import java.util.List;

/** Builds version-1 {@link AuditedTaskState} from frozen acceptance criteria. */
public final class AuditedTaskStateInitializer {

    static final List<String> GATE_IDS = List.of(
            "GATE-BUILD",
            "GATE-STATIC",
            "GATE-QA-EVIDENCE",
            "GATE-WORKSPACE-INTEGRITY"
    );
    static final String ARTIFACT_PR_ID = "ART-PR";
    static final String HOST_ASSERTION_GATE_ID = "GATE-HOST-ASSERTION";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditedTaskStateCodec codec;

    public AuditedTaskStateInitializer(AuditedTaskStateCodec codec) {
        this.codec = codec == null ? new AuditedTaskStateCodec() : codec;
    }

    /**
     * Initializes {@code state_version=1} for a requirement task.
     *
     * @param task requirement task entering {@code EXECUTING}
     * @return sealed initial state
     */
    public AuditedTaskState initialize(RdRequirementTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (task.version() < 0L) {
            throw new IllegalArgumentException("task version must not be negative");
        }
        if (task.fencingToken() <= 0L) {
            throw new IllegalArgumentException("fencing token must be positive");
        }
        List<String> criteria = parseCriteria(task.acceptanceCriteriaJson());
        List<String> ids = AcceptanceCriteriaIds.of(criteria);
        List<AuditedRecord> records = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            records.add(new AuditedRecord(
                    ids.get(index),
                    AuditedRecordKind.REQUIREMENT,
                    true,
                    criteria.get(index),
                    AuditedRecordStatus.PENDING,
                    List.of(),
                    "",
                    ""));
        }
        for (String gateId : GATE_IDS) {
            records.add(gate(gateId));
        }
        if (task.hasHostAssertionBundle()) {
            records.add(gate(HOST_ASSERTION_GATE_ID));
        }
        records.add(new AuditedRecord(
                ARTIFACT_PR_ID,
                AuditedRecordKind.ARTIFACT,
                false,
                "pull request",
                AuditedRecordStatus.PENDING,
                List.of(),
                "",
                ""));
        String criteriaHash = CanonicalJsonSha256.digest(
                CanonicalJsonSha256.canonicalize(task.acceptanceCriteriaJson()));
        AuditedTaskState unsigned = new AuditedTaskState(
                task.taskId(),
                1L,
                "",
                new AuditedContractRef(criteriaHash, task.version(), task.fencingToken()),
                records,
                "");
        return codec.seal(unsigned);
    }

    private static AuditedRecord gate(String id) {
        return new AuditedRecord(
                id,
                AuditedRecordKind.GATE,
                true,
                id,
                AuditedRecordStatus.PENDING,
                List.of(),
                "",
                "");
    }

    private static List<String> parseCriteria(String json) {
        try {
            JsonNode tree = MAPPER.readTree(json == null || json.isBlank() ? "[]" : json);
            if (tree == null || !tree.isArray()) {
                throw new IllegalArgumentException("acceptanceCriteriaJson must be a JSON array");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode node : tree) {
                values.add(node.isTextual() ? node.textValue() : node.toString());
            }
            return List.copyOf(values);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("acceptanceCriteriaJson must be valid JSON", invalid);
        }
    }
}
