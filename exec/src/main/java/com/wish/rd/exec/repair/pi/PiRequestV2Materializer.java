package com.wish.rd.exec.repair.pi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileDecision;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileExpectation;
import com.wish.rd.rag.project.agent.model.RuntimeContextManifest;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicy;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicyMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Host-side helpers for rd-pi-request/v2 materialization and runtime context parsing. */
public final class PiRequestV2Materializer {

    public static final String INPUT_MANIFEST_PATH_RELATIVE = "role-execution-input-manifest.json";
    public static final String CONTAINER_INPUT_MANIFEST_PATH = "/work/input/role-execution-input-manifest.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PiRequestV2Materializer() {
    }

    public static boolean isV2(String protocolVersion) {
        return "v2".equals(normalizeProtocolVersion(protocolVersion));
    }

    public static String normalizeProtocolVersion(String protocolVersion) {
        String normalized = protocolVersion == null ? "" : protocolVersion.strip();
        if (normalized.isBlank()) {
            return "v1";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ("v1".equals(lower) || "v2".equals(lower)) {
            return lower;
        }
        throw new IllegalArgumentException("requestProtocolVersion must be v1 or v2 but was: " + protocolVersion);
    }

    public static void materializeInputManifest(Path inputDirectory, String inputManifestJson) throws IOException {
        if (inputManifestJson == null || inputManifestJson.isBlank()) {
            return;
        }
        Path root = inputDirectory.toAbsolutePath().normalize();
        Path target = inputDirectory.resolve(INPUT_MANIFEST_PATH_RELATIVE).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("input manifest path escapes input directory");
        }
        Files.createDirectories(inputDirectory);
        Files.writeString(target, inputManifestJson.strip() + "\n", StandardCharsets.UTF_8);
    }

    public static Map<String, Object> parseContextPolicy(String contextPolicyJson) {
        return contextPolicyToMap(parseRuntimeContextPolicy(contextPolicyJson));
    }

    public static RuntimeContextPolicy parseRuntimeContextPolicy(String contextPolicyJson) {
        String json = requireNonBlank(contextPolicyJson, "contextPolicyJson");
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("contextPolicyJson must be a JSON object");
            }
            String protocol = text(root.path("protocol"));
            if (!"rd-runtime-context-policy/v1".equals(protocol)) {
                throw new IllegalArgumentException("contextPolicy.protocol must be rd-runtime-context-policy/v1");
            }
            String policyHash = text(root.path("policyHash"));
            if (policyHash.isBlank()) {
                throw new IllegalArgumentException("contextPolicy.policyHash must not be blank");
            }
            String modeText = text(root.path("mode"));
            RuntimeContextPolicyMode mode;
            try {
                mode = modeText.isBlank()
                        ? RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY
                        : RuntimeContextPolicyMode.valueOf(modeText);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("contextPolicy.mode is invalid", exception);
            }
            List<RuntimeContextFileExpectation> expectedFiles = new ArrayList<>();
            JsonNode files = root.path("expectedFiles");
            if (files.isArray()) {
                files.forEach(file -> expectedFiles.add(new RuntimeContextFileExpectation(
                        text(file.path("path")),
                        text(file.path("contentHash"))
                )));
            }
            return new RuntimeContextPolicy(protocol, policyHash, mode, expectedFiles);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("contextPolicyJson is not valid JSON", exception);
        }
    }

    public static RuntimeContextManifest parseRuntimeContextManifest(String manifestJson) {
        try {
            return OBJECT_MAPPER.readValue(
                    requireNonBlank(manifestJson, "manifestJson"),
                    RuntimeContextManifest.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("runtime context manifest is not valid JSON", exception);
        }
    }

    static RuntimeContextPreflightValidator.ValidationResult validatePreflight(
            String contextPolicyJson,
            String manifestJson
    ) {
        RuntimeContextPolicy policy = parseRuntimeContextPolicy(contextPolicyJson);
        RuntimeContextManifest manifest = parseRuntimeContextManifest(manifestJson);
        return new RuntimeContextPreflightValidator().validate(policy, manifest);
    }

    private static Map<String, Object> contextPolicyToMap(RuntimeContextPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("protocol", policy.protocol());
        map.put("policyHash", policy.policyHash());
        map.put("mode", policy.mode().name());
        List<Map<String, String>> expectedFiles = policy.expectedFiles().stream()
                .map(file -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("path", file.path());
                    entry.put("contentHash", file.contentHash());
                    return entry;
                })
                .toList();
        map.put("expectedFiles", expectedFiles);
        return map;
    }

    private static String requireNonBlank(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").strip();
    }
}
