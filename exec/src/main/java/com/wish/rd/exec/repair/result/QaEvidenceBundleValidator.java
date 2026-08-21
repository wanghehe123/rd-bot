package com.wish.rd.exec.repair.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.qa.QaDocsOnlyChangeClassifier;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates that a QA report references real, non-empty artifacts collected from the executor workspace.
 *
 * <p>Docs-only candidates (host-verified from the real changed-file set) may omit browser evidence;
 * undeterminable change sets fail closed and still require the full browser bundle when browser
 * validation was performed.
 */
public final class QaEvidenceBundleValidator {

    private static final Pattern PLAYWRIGHT_ERROR_RESPONSE = Pattern.compile(
            "\\\"isError\\\"\\s*:\\s*true",
            Pattern.CASE_INSENSITIVE
    );
    private static final QaDocsOnlyChangeClassifier DOCS_ONLY_CLASSIFIER = new QaDocsOnlyChangeClassifier();

    private final ObjectMapper objectMapper;
    private final AgentRoleResultValidator roleResultValidator;

    public QaEvidenceBundleValidator() {
        this(new ObjectMapper(), new AgentRoleResultValidator());
    }

    QaEvidenceBundleValidator(ObjectMapper objectMapper, AgentRoleResultValidator roleResultValidator) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
        this.roleResultValidator = roleResultValidator == null
                ? new AgentRoleResultValidator()
                : roleResultValidator;
    }

    /**
     * Validates the strict QA protocol and resolves every evidence reference against collected artifacts.
     *
     * @param resultJson strict QA result JSON
     * @param artifacts artifacts collected from {@code /work/output}
     * @return combined protocol and evidence validation
     */
    public AgentRoleResultValidation validate(String resultJson, List<RepairArtifact> artifacts) {
        return validate(resultJson, artifacts, List.of(), null);
    }

    /**
     * Validates the QA bundle and requires every task acceptance criterion to have a CURRENT result.
     *
     * @param resultJson strict QA result JSON
     * @param artifacts artifacts collected from {@code /work/output}
     * @param requiredAcceptanceCriteria task acceptance criteria that must appear in CURRENT/REGRESSION rows
     * @return combined protocol and evidence validation
     */
    public AgentRoleResultValidation validate(
            String resultJson,
            List<RepairArtifact> artifacts,
            List<String> requiredAcceptanceCriteria
    ) {
        return validate(resultJson, artifacts, requiredAcceptanceCriteria, null);
    }

    /**
     * Validates the QA bundle with a host-computed candidate changed-file set for docs-only decisions.
     *
     * @param resultJson strict QA result JSON
     * @param artifacts artifacts collected from {@code /work/output}
     * @param requiredAcceptanceCriteria task acceptance criteria that must appear in CURRENT/REGRESSION rows
     * @param candidateChangedFiles real changed paths from the candidate patch; {@code null} means undeterminable
     * @return combined protocol and evidence validation
     */
    public AgentRoleResultValidation validate(
            String resultJson,
            List<RepairArtifact> artifacts,
            List<String> requiredAcceptanceCriteria,
            List<String> candidateChangedFiles
    ) {
        return validate(resultJson, artifacts, requiredAcceptanceCriteria, candidateChangedFiles, false);
    }

    /** Validates authoritative finding-to-acceptance-to-file evidence for PI remediation v2. */
    public AgentRoleResultValidation validate(
            String resultJson,
            List<RepairArtifact> artifacts,
            List<String> requiredAcceptanceCriteria,
            List<String> candidateChangedFiles,
            boolean qaRemediationV2Enabled
    ) {
        List<String> errors = new ArrayList<>(
                roleResultValidator.validate("QA_AGENT", resultJson, qaRemediationV2Enabled).errors()
        );
        JsonNode root = parseObject(resultJson);
        if (root == null) {
            return new AgentRoleResultValidation(false, List.copyOf(errors));
        }

        Map<String, RepairArtifact> artifactsByName = artifactsByName(artifacts);
        Set<String> referencedArtifacts = new LinkedHashSet<>();
        Set<String> currentCriteria = new LinkedHashSet<>();
        JsonNode acceptanceResults = root.path("acceptanceResults");
        if (acceptanceResults.isArray()) {
            int resultIndex = 0;
            for (JsonNode result : acceptanceResults) {
                String scope = result.path("scope").asText("").strip();
                if ("CURRENT".equals(scope) || "REGRESSION".equals(scope)) {
                    String criterion = result.path("criteria").asText("").strip();
                    if (!criterion.isBlank()) {
                        currentCriteria.add(criterion);
                    }
                }
                String logReference = result.path("logArtifactId").asText("").strip();
                if (!logReference.isBlank()) {
                    validateReference(
                            "acceptanceResults[" + resultIndex + "].logArtifactId",
                            logReference,
                            artifactsByName,
                            referencedArtifacts,
                            errors
                    );
                    validatePassedBrowserLog(
                            resultIndex,
                            result,
                            logReference,
                            artifactsByName,
                            errors
                    );
                }
                JsonNode evidenceIds = result.path("evidenceArtifactIds");
                if (evidenceIds.isArray()) {
                    int evidenceIndex = 0;
                    for (JsonNode evidenceId : evidenceIds) {
                        String reference = evidenceId.asText("").strip();
                        if (!reference.isBlank()) {
                            validateReference(
                                    "acceptanceResults[" + resultIndex + "].evidenceArtifactIds["
                                            + evidenceIndex + "]",
                                    reference,
                                    artifactsByName,
                                    referencedArtifacts,
                                    errors
                            );
                        }
                        evidenceIndex++;
                    }
                }
                resultIndex++;
            }
        }
        if (requiredAcceptanceCriteria != null) {
            requiredAcceptanceCriteria.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::strip)
                    .filter(criterion -> !criterion.isBlank())
                    .distinct()
                    .filter(criterion -> !coversAcceptanceCriterion(currentCriteria, criterion))
                    .forEach(criterion -> errors.add(
                            "evidence is missing task acceptance criterion: " + criterion));
        }

        String manifestReference = root.path("evidenceManifestArtifactId").asText("").strip();
        if (!manifestReference.isBlank()) {
            validateReference(
                    "evidenceManifestArtifactId",
                    manifestReference,
                    artifactsByName,
                    referencedArtifacts,
                    errors
            );
            RepairArtifact manifest = artifactsByName.get(normalizeReference(manifestReference));
            if (manifest != null && manifest.type() != RepairArtifactType.QA_EVIDENCE_MANIFEST) {
                errors.add("evidenceManifestArtifactId must reference a QA_EVIDENCE_MANIFEST artifact");
            } else if (manifest != null) {
                validateManifest(manifest, artifactsByName, errors);
            }
        }

        if (qaRemediationV2Enabled) {
            validateRemediationEvidence(root, artifactsByName, referencedArtifacts, errors);
        }

        JsonNode browserValidation = root.path("browserValidation");
        boolean browserRequired = browserValidation.path("required").asBoolean(false);
        boolean browserPerformed = browserValidation.path("performed").asBoolean(false);
        String decisionSource = browserValidation.path("decisionSource").asText("").strip();
        boolean docsOnlyDecision = "DOCS_ONLY".equals(decisionSource);
        QaDocsOnlyChangeClassifier.Decision docsOnly = DOCS_ONLY_CLASSIFIER.classify(candidateChangedFiles);
        if (docsOnlyDecision) {
            if (docsOnly == QaDocsOnlyChangeClassifier.Decision.UNDETERMINABLE) {
                errors.add("docs-only QA requires a determinable candidate changed-file set; undeterminable changes keep the full browser profile");
            } else if (docsOnly != QaDocsOnlyChangeClassifier.Decision.DOCS_ONLY) {
                errors.add("docs-only QA claim rejected because candidate changes are not docs-only; full browser evidence is required");
            } else if (browserRequired || browserPerformed) {
                errors.add("docs-only QA requires browserValidation.required=false and performed=false");
            }
        } else if (browserRequired && browserPerformed) {
            requireReferencedType(RepairArtifactType.QA_SCREENSHOT, referencedArtifacts, artifactsByName, errors);
            requireReferencedType(RepairArtifactType.QA_TRACE, referencedArtifacts, artifactsByName, errors);
            requireReferencedType(RepairArtifactType.QA_CONSOLE_LOG, referencedArtifacts, artifactsByName, errors);
            requireReferencedType(RepairArtifactType.QA_NETWORK_LOG, referencedArtifacts, artifactsByName, errors);
            requireReferencedScreenshot("desktop", "desktop-1440x900", referencedArtifacts, artifactsByName, errors);
            requireReferencedScreenshot("mobile", "mobile-390x844", referencedArtifacts, artifactsByName, errors);
        }
        return new AgentRoleResultValidation(errors.isEmpty(), List.copyOf(errors));
    }

    private static void validateRemediationEvidence(
            JsonNode root,
            Map<String, RepairArtifact> artifactsByName,
            Set<String> referencedArtifacts,
            List<String> errors
    ) {
        JsonNode request = root.path("remediationRequest");
        if (!request.path("requested").asBoolean(false)) return;

        Map<String, JsonNode> failedAcceptanceById = new LinkedHashMap<>();
        JsonNode acceptanceResults = root.path("acceptanceResults");
        if (acceptanceResults.isArray()) {
            for (JsonNode acceptance : acceptanceResults) {
                if (!"FAILED".equals(acceptance.path("status").asText("").strip())) continue;
                String criteriaId = acceptance.path("criteriaId").asText("").strip();
                if (criteriaId.isBlank()) continue;
                if (failedAcceptanceById.putIfAbsent(criteriaId, acceptance) != null) {
                    errors.add("acceptanceResults contains duplicate FAILED criteriaId: " + criteriaId);
                }
            }
        }

        Set<String> selectedIds = new LinkedHashSet<>();
        request.path("bugFindingIds").forEach(id -> selectedIds.add(id.asText("").strip()));
        JsonNode findings = root.path("bugFindings");
        if (!findings.isArray()) return;
        for (int findingIndex = 0; findingIndex < findings.size(); findingIndex++) {
            JsonNode finding = findings.get(findingIndex);
            String findingId = finding.path("id").asText("").strip();
            if (!selectedIds.contains(findingId)) continue;
            String criteriaId = finding.path("acceptanceCriteriaId").asText("").strip();
            JsonNode failedAcceptance = failedAcceptanceById.get(criteriaId);
            if (failedAcceptance == null) {
                errors.add("bugFindings[" + findingIndex
                        + "].acceptanceCriteriaId must reference a FAILED acceptanceResults.criteriaId");
                continue;
            }
            Set<String> acceptanceEvidence = new LinkedHashSet<>();
            String logArtifactId = normalizeReference(
                    failedAcceptance.path("logArtifactId").asText("").strip()
            );
            if (!logArtifactId.isBlank()) acceptanceEvidence.add(logArtifactId);
            failedAcceptance.path("evidenceArtifactIds").forEach(
                    evidence -> acceptanceEvidence.add(normalizeReference(evidence.asText("").strip()))
            );
            JsonNode evidenceIds = finding.path("evidenceArtifactIds");
            for (int evidenceIndex = 0; evidenceIndex < evidenceIds.size(); evidenceIndex++) {
                String evidenceId = evidenceIds.get(evidenceIndex).asText("").strip();
                String field = "bugFindings[" + findingIndex + "].evidenceArtifactIds[" + evidenceIndex + "]";
                validateReference(field, evidenceId, artifactsByName, referencedArtifacts, errors);
                if (!acceptanceEvidence.contains(normalizeReference(evidenceId))) {
                    errors.add(field + " must also be referenced by its FAILED acceptance");
                }
            }
        }
    }

    /**
     * Agents often label CURRENT rows as {@code AC1: <criterion>}. Accept exact match,
     * or a reported CURRENT/REGRESSION criteria string that contains the required criterion.
     */
    private static boolean coversAcceptanceCriterion(Set<String> reportedCriteria, String required) {
        if (reportedCriteria.contains(required)) {
            return true;
        }
        for (String reported : reportedCriteria) {
            String normalized = reported.replaceFirst("(?i)^AC\\d+\\s*[:：]\\s*", "").strip();
            if (normalized.equals(required)
                    || reported.contains(required)
                    || normalized.contains(required)) {
                return true;
            }
        }
        return false;
    }

    private static void validatePassedBrowserLog(
            int resultIndex,
            JsonNode result,
            String logReference,
            Map<String, RepairArtifact> artifactsByName,
            List<String> errors
    ) {
        if (!"PASSED".equals(result.path("status").asText("").strip())) {
            return;
        }
        String command = result.path("command").asText("").toLowerCase(java.util.Locale.ROOT);
        if (!command.contains("playwright") && !command.contains("browser")) {
            return;
        }
        RepairArtifact artifact = artifactsByName.get(normalizeReference(logReference));
        if (artifact == null) {
            return;
        }
        String preview = artifact.metadataJson().getOrDefault("contentPreview", "");
        if (PLAYWRIGHT_ERROR_RESPONSE.matcher(preview).find()) {
            errors.add("acceptanceResults[" + resultIndex
                    + "] claims PASSED but its command log contains a Playwright isError response");
        }
    }

    private void validateManifest(
            RepairArtifact manifest,
            Map<String, RepairArtifact> artifactsByName,
            List<String> errors
    ) {
        String preview = manifest.metadataJson().getOrDefault("contentPreview", "").strip();
        if (preview.isBlank()) {
            errors.add("QA evidence manifest content is unavailable for integrity validation");
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(preview);
        } catch (JsonProcessingException exception) {
            errors.add("QA evidence manifest is not valid JSON");
            return;
        }
        int manifestVersion = root.path("version").asInt(-1);
        String manifestSchema = root.path("schema").asText("");
        boolean validManifestVersion = manifestVersion == 1
                || manifestSchema.toLowerCase(java.util.Locale.ROOT).contains("v1");
        if (root == null || !root.isObject() || !validManifestVersion
                || !root.path("artifacts").isArray()) {
            errors.add("QA evidence manifest must contain version 1 and an artifacts array");
            return;
        }

        Set<String> manifestPaths = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode entry : root.path("artifacts")) {
            String field = "QA evidence manifest artifacts[" + index + "]";
            if (!entry.isObject()) {
                errors.add(field + " must be an object");
                index++;
                continue;
            }
            String rawPath = entry.path("path").asText("").strip();
            String path = normalizeReference(rawPath);
            if (!safeEvidencePath(path) || path.equals(normalizeReference(manifest.name()))) {
                errors.add(field + " has an invalid evidence path: " + rawPath);
                index++;
                continue;
            }
            if (!manifestPaths.add(path)) {
                errors.add("QA evidence manifest contains duplicate artifact path: " + path);
                index++;
                continue;
            }

            RepairArtifact artifact = artifactsByName.get(path);
            if (artifact == null) {
                errors.add("QA evidence manifest references an uncollected artifact: " + path);
                index++;
                continue;
            }
            String expectedBytes = artifact.metadataJson().getOrDefault("bytes", "").strip();
            long manifestBytes = entry.path("bytes").canConvertToLong() ? entry.path("bytes").longValue() : -1L;
            if (!expectedBytes.equals(String.valueOf(manifestBytes))) {
                errors.add("QA evidence manifest byte count does not match collected artifact: " + path);
            }
            String expectedHash = artifact.metadataJson().getOrDefault("sha256", "").strip();
            String manifestHash = entry.path("sha256").asText("").strip();
            if (!validSha256(manifestHash) || !expectedHash.equalsIgnoreCase(manifestHash)) {
                errors.add("QA evidence manifest sha256 does not match collected artifact: " + path);
            }
            index++;
        }

        artifactsByName.keySet().stream()
                .filter(QaEvidenceBundleValidator::safeEvidencePath)
                .filter(path -> !path.equals(normalizeReference(manifest.name())))
                .filter(path -> !manifestPaths.contains(path))
                .forEach(path -> errors.add(
                        "QA evidence manifest does not cover collected artifact: " + path));
    }

    private JsonNode parseObject(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson == null ? "" : resultJson);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static Map<String, RepairArtifact> artifactsByName(List<RepairArtifact> artifacts) {
        Map<String, RepairArtifact> byName = new LinkedHashMap<>();
        if (artifacts == null) {
            return byName;
        }
        for (RepairArtifact artifact : artifacts) {
            if (artifact != null && !artifact.name().isBlank()) {
                byName.putIfAbsent(normalizeReference(artifact.name()), artifact);
            }
        }
        return byName;
    }

    private static void validateReference(
            String field,
            String reference,
            Map<String, RepairArtifact> artifactsByName,
            Set<String> referencedArtifacts,
            List<String> errors
    ) {
        String normalizedReference = normalizeReference(reference);
        RepairArtifact artifact = artifactsByName.get(normalizedReference);
        if (artifact == null) {
            errors.add(field + " does not resolve to a collected artifact: " + reference);
            return;
        }
        referencedArtifacts.add(normalizedReference);
        String bytes = artifact.metadataJson().getOrDefault("bytes", "").strip();
        if (!positiveLong(bytes)) {
            errors.add(field + " references an empty artifact: " + reference);
        }
        if (!validSha256(artifact.metadataJson().getOrDefault("sha256", "").strip())) {
            errors.add(field + " references an artifact without sha256: " + reference);
        }
    }

    private static void requireReferencedType(
            RepairArtifactType requiredType,
            Set<String> referencedArtifacts,
            Map<String, RepairArtifact> artifactsByName,
            List<String> errors
    ) {
        boolean found = referencedArtifacts.stream()
                .map(artifactsByName::get)
                .anyMatch(artifact -> artifact != null && artifact.type() == requiredType);
        if (!found) {
            errors.add("browser validation requires referenced " + requiredType + " evidence");
        }
    }

    private static void requireReferencedScreenshot(
            String pathMarker,
            String viewport,
            Set<String> referencedArtifacts,
            Map<String, RepairArtifact> artifactsByName,
            List<String> errors
    ) {
        boolean found = referencedArtifacts.stream()
                .filter(path -> path.toLowerCase(java.util.Locale.ROOT).contains(pathMarker))
                .map(artifactsByName::get)
                .anyMatch(artifact -> artifact != null && artifact.type() == RepairArtifactType.QA_SCREENSHOT);
        if (!found) {
            errors.add("browser validation requires referenced " + viewport + " screenshot evidence");
        }
    }

    private static String normalizeReference(String reference) {
        String normalized = reference == null ? "" : reference.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static boolean safeEvidencePath(String path) {
        return path != null
                && path.startsWith("qa-evidence/")
                && !path.startsWith("/")
                && !path.contains("/../")
                && !path.endsWith("/..")
                && !path.contains("//");
    }

    private static boolean validSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private static boolean positiveLong(String value) {
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
