package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fails fast unless the latest evidence ledger proves all 15 production acceptance points passed.
 */
final class ProductionAcceptanceFinalGate {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int REQUIRED_ACCEPTANCE_POINT_COUNT = 15;

    Path assertLatestLedgerPassed(Path evidenceRoot) throws IOException {
        Path safeEvidenceRoot = evidenceRoot == null
                ? MultiAgentProductionAcceptanceReport.resolveReportRoot("qa-runs/multi-agent-production-acceptance")
                : evidenceRoot.toAbsolutePath().normalize();
        Path latestLedger = latestLedger(safeEvidenceRoot)
                .orElseThrow(() -> new AssertionError(
                        "Missing production-acceptance-evidence-ledger-*.json under " + safeEvidenceRoot
                ));
        JsonNode root = OBJECT_MAPPER.readTree(latestLedger.toFile());
        if (ledgerPassed(root)) {
            return latestLedger;
        }
        throw new AssertionError(failureMessage(safeEvidenceRoot, latestLedger, root));
    }

    private Optional<Path> latestLedger(Path evidenceRoot) throws IOException {
        if (!Files.isDirectory(evidenceRoot)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> paths = Files.list(evidenceRoot)) {
            return paths.filter(this::isLedgerJson)
                    .max(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        }
    }

    private boolean isLedgerJson(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith("production-acceptance-evidence-ledger-") && fileName.endsWith(".json");
    }

    private boolean ledgerPassed(JsonNode root) {
        return "PASSED_PRODUCTION_ACCEPTANCE_LEDGER".equals(root.path("conclusion").asText(""))
                && root.path("totalAcceptancePointCount").asInt(0) == REQUIRED_ACCEPTANCE_POINT_COUNT
                && root.path("passedCount").asInt(0) == REQUIRED_ACCEPTANCE_POINT_COUNT
                && root.path("failedCount").asInt(-1) == 0
                && root.path("notRunCount").asInt(-1) == 0
                && rowsAllPassed(root.path("rows"));
    }

    private boolean rowsAllPassed(JsonNode rows) {
        if (!rows.isArray() || rows.size() != REQUIRED_ACCEPTANCE_POINT_COUNT) {
            return false;
        }
        for (JsonNode row : rows) {
            if (!"PASSED".equals(row.path("status").asText(""))) {
                return false;
            }
        }
        return true;
    }

    private String failureMessage(Path evidenceRoot, Path latestLedger, JsonNode root) {
        return "Production acceptance gate failed: ledger=" + evidenceRoot.relativize(latestLedger)
                + ", conclusion=" + oneLine(root.path("conclusion").asText("UNKNOWN"))
                + ", passed=" + root.path("passedCount").asInt(0)
                + ", failed=" + root.path("failedCount").asInt(0)
                + ", notRun=" + root.path("notRunCount").asInt(0)
                + ", nextActions=" + nextActions(root.path("nextActions"));
    }

    private String nextActions(JsonNode nextActions) {
        if (!nextActions.isArray() || nextActions.isEmpty()) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        for (JsonNode nextAction : nextActions) {
            String item = oneLine(nextAction.path("item").asText(""));
            String reason = oneLine(nextAction.path("reason").asText(""));
            String action = oneLine(nextAction.path("action").asText(""));
            String evidence = oneLine(nextAction.path("evidence").asText(""));
            items.add(item + ":" + reason + ":" + action + ":" + evidence);
        }
        return String.join("; ", items);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.strip().replaceAll("[\\r\\n|]+", " ");
    }
}
