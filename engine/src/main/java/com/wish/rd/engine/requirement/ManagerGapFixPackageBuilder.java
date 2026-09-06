package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Builds the Host-controlled MANAGER_GAP_FIX attachment. */
public final class ManagerGapFixPackageBuilder {
    public static final String ATTACHMENT_PATH = "attachments/manager-gap-fix/request.json";
    public static final String CONTAINER_PATH = "/work/input/attachments/manager-gap-fix/request.json";
    public static final String PROTOCOL = "rd-manager-gap-fix-request/v1";
    private static final int MAX_JSON_BYTES = 65_536;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Rehydrates a frozen request.
     *
     * @param requestJson canonical JSON
     * @param requestHash digest
     * @return package
     */
    public Package fromFrozen(String requestJson, String requestHash) {
        String hash = requestHash == null ? "" : requestHash.strip().toLowerCase(java.util.Locale.ROOT);
        String canonical = CanonicalJsonSha256.requireCanonicalMatchingHash(requestJson, hash);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("frozen manager gap-fix request exceeds protocol limit");
        }
        try {
            JsonNode root = MAPPER.readTree(canonical);
            if (!PROTOCOL.equals(root.path("protocol").asText())) {
                throw new IllegalArgumentException("unsupported frozen manager gap-fix protocol");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode id : root.path("targetRecordIds")) {
                String value = id.asText("").strip();
                if (!value.isBlank()) {
                    ids.add(value);
                }
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("targetRecordIds must not be empty");
            }
            String contract = root.path("boundedContract").asText("").strip();
            RequirementExecutionRequest.InitialAgentStateAttachment attachment =
                    new RequirementExecutionRequest.InitialAgentStateAttachment(
                            ATTACHMENT_PATH, canonical, hash, bytes.length);
            String prompt = "MANAGER_GAP_FIX for " + String.join(", ", ids) + ".\n"
                    + "Authoritative request: " + CONTAINER_PATH + " (" + hash + ")\n"
                    + contract + "\n"
                    + "Follow the Host audited gap section. Do not mark unlisted AC records complete.";
            return new Package(attachment, prompt, List.of("只修复 " + String.join(", ", ids)), hash);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid frozen manager gap-fix request JSON", invalid);
        }
    }

    /**
     * Builds a canonical gap-fix request.
     *
     * @param targetRecordIds pending blocking record ids
     * @param boundedContract contract text
     * @param stateVersion audited head version
     * @param stateHash audited head hash
     * @param remediationNo 1-based round
     * @return package
     */
    public Package build(
            List<String> targetRecordIds,
            String boundedContract,
            long stateVersion,
            String stateHash,
            int remediationNo
    ) {
        if (targetRecordIds == null || targetRecordIds.isEmpty()) {
            throw new IllegalArgumentException("targetRecordIds must not be empty");
        }
        if (remediationNo < 1 || remediationNo > 2) {
            throw new IllegalArgumentException("remediationNo must be between 1 and 2");
        }
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("protocol", PROTOCOL);
            ArrayNode ids = node.putArray("targetRecordIds");
            for (String id : targetRecordIds) {
                ids.add(id);
            }
            node.put("boundedContract", boundedContract == null ? "" : boundedContract.strip());
            node.put("stateVersion", stateVersion);
            node.put("stateHash", stateHash == null ? "" : stateHash.strip());
            node.put("remediationNo", remediationNo);
            node.put("instruction", "只关闭 boundedContract 点名的已审计缺口；完成后调用 rd_submit_result。");
            String canonical = CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(node));
            return fromFrozen(canonical, CanonicalJsonSha256.digest(canonical));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("manager gap-fix request cannot be encoded", invalid);
        }
    }

    /**
     * Frozen attachment plus prompt.
     *
     * @param attachment hash-bound request JSON
     * @param promptSection coding prompt section
     * @param todos host-owned todos
     * @param requestHash canonical digest
     */
    public record Package(
            RequirementExecutionRequest.InitialAgentStateAttachment attachment,
            String promptSection,
            List<String> todos,
            String requestHash
    ) {
        public Package {
            if (attachment == null) {
                throw new IllegalArgumentException("attachment is required");
            }
            promptSection = promptSection == null ? "" : promptSection;
            todos = todos == null ? List.of() : List.copyOf(todos);
            requestHash = requestHash == null ? "" : requestHash.strip();
        }
    }
}
