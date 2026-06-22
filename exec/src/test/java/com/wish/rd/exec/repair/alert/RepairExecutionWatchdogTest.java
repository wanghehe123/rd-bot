package com.wish.rd.exec.repair.alert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairExecutionWatchdogTest {

    @Test
    void shouldEmitTimeoutWarningWhenElapsedMillisExceedsThreshold() {
        RecordingAlertSink alertSink = new RecordingAlertSink();
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1_000L, new BigDecimal("5.00")),
                alertSink
        );

        RepairExecutionWatchdog.EvaluationResult result = watchdog.evaluate(
                "repair-1001",
                "task-1001",
                1_001L,
                new BigDecimal("1.00"),
                10_000L
        );

        assertFalse(result.shouldStop());
        assertEquals(1, result.alerts().size());
        assertEquals(List.of(result.alerts().getFirst()), alertSink.alerts());
        RepairAlert alert = result.alerts().getFirst();
        assertEquals("repair-1001", alert.repairRecordId());
        assertEquals("task-1001", alert.taskId());
        assertEquals(RepairAlertType.TIMEOUT_WARNING, alert.type());
        assertEquals("repair execution elapsed millis exceeded warning threshold", alert.message());
        assertEquals("1001", alert.metadata().get("elapsedMillis"));
        assertEquals("1000", alert.metadata().get("thresholdMillis"));
        assertEquals(10_000L, alert.createdAtEpochMillis());
    }

    @Test
    void shouldEmitBudgetWarningWhenEstimatedSpendExceedsThreshold() {
        RecordingAlertSink alertSink = new RecordingAlertSink();
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1_000L, new BigDecimal("5.00")),
                alertSink
        );

        RepairExecutionWatchdog.EvaluationResult result = watchdog.evaluate(
                "repair-1001",
                "task-1001",
                100L,
                new BigDecimal("5.01"),
                10_000L
        );

        assertFalse(result.shouldStop());
        assertEquals(1, result.alerts().size());
        RepairAlert alert = result.alerts().getFirst();
        assertEquals(RepairAlertType.BUDGET_WARNING, alert.type());
        assertEquals("repair execution estimated spend exceeded warning threshold", alert.message());
        assertEquals("5.01", alert.metadata().get("estimatedSpend"));
        assertEquals("5.00", alert.metadata().get("thresholdSpend"));
    }

    @Test
    void shouldNotEmitWarningsAtExactThresholds() {
        RecordingAlertSink alertSink = new RecordingAlertSink();
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1_000L, new BigDecimal("5.00")),
                alertSink
        );

        RepairExecutionWatchdog.EvaluationResult result = watchdog.evaluate(
                "repair-1001",
                "task-1001",
                1_000L,
                new BigDecimal("5.00"),
                10_000L
        );

        assertFalse(result.shouldStop());
        assertTrue(result.alerts().isEmpty());
        assertTrue(alertSink.alerts().isEmpty());
    }

    @Test
    void shouldNotEmitDuplicateAlertsForSameTaskAndAlertType() {
        RecordingAlertSink alertSink = new RecordingAlertSink();
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1_000L, new BigDecimal("5.00")),
                alertSink
        );

        RepairExecutionWatchdog.EvaluationResult first = watchdog.evaluate(
                "repair-1001",
                "task-1001",
                2_000L,
                new BigDecimal("10.00"),
                10_000L
        );
        RepairExecutionWatchdog.EvaluationResult second = watchdog.evaluate(
                "repair-1001",
                "task-1001",
                3_000L,
                new BigDecimal("11.00"),
                11_000L
        );

        assertEquals(2, first.alerts().size());
        assertTrue(second.alerts().isEmpty());
        assertEquals(2, alertSink.alerts().size());
    }

    @Test
    void shouldRetryAlertWhenSinkPublishFails() {
        FailsOnceAlertSink alertSink = new FailsOnceAlertSink();
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1_000L, new BigDecimal("5.00")),
                alertSink
        );

        assertThrows(IllegalStateException.class, () -> watchdog.evaluate(
                "repair-1001",
                "task-1001",
                2_000L,
                BigDecimal.ZERO,
                10_000L
        ));

        RepairExecutionWatchdog.EvaluationResult retry = watchdog.evaluate(
                "repair-1001",
                "task-1001",
                2_001L,
                BigDecimal.ZERO,
                10_001L
        );

        assertEquals(1, retry.alerts().size());
        assertEquals(1, alertSink.alerts().size());
        assertEquals(RepairAlertType.TIMEOUT_WARNING, alertSink.alerts().getFirst().type());
    }

    @Test
    void shouldReturnShouldStopFalseForWarnings() {
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1L, new BigDecimal("1.00")),
                new RecordingAlertSink()
        );

        RepairExecutionWatchdog.EvaluationResult result = watchdog.evaluate(
                "repair-1001",
                "task-1001",
                2L,
                new BigDecimal("2.00"),
                10_000L
        );

        assertFalse(result.shouldStop());
        assertEquals(2, result.alerts().size());
    }

    @Test
    void shouldNormalizeAlertMetadataAndRejectBlankMetadataKeys() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("present", "value");
        metadata.put("nullable", null);

        RepairAlert alert = new RepairAlert(
                " repair-1001 ",
                " task-1001 ",
                RepairAlertType.MISSING_ARTIFACT,
                null,
                metadata,
                10_000L
        );
        metadata.put("present", "changed");

        assertEquals("repair-1001", alert.repairRecordId());
        assertEquals("task-1001", alert.taskId());
        assertEquals(RepairAlertType.MISSING_ARTIFACT, alert.type());
        assertEquals("", alert.message());
        assertEquals("value", alert.metadata().get("present"));
        assertEquals("", alert.metadata().get("nullable"));
        assertThrows(UnsupportedOperationException.class, () -> alert.metadata().put("other", "value"));

        assertThrows(IllegalArgumentException.class, () -> new RepairAlert(
                "repair-1001",
                "task-1001",
                RepairAlertType.VALIDATION_FAILED,
                "failed",
                Map.of(" ", "blank"),
                10_000L
        ));
        assertThrows(IllegalArgumentException.class, () -> new RepairAlert(
                "repair-1001",
                "task-1001",
                RepairAlertType.VALIDATION_FAILED,
                "failed",
                Map.of("owner", "first", " owner ", "second"),
                10_000L
        ));
    }

    @Test
    void shouldDefaultNullAlertTypeToValidationFailed() {
        RepairAlert alert = new RepairAlert(
                "repair-1001",
                "task-1001",
                null,
                "failed",
                null,
                10_000L
        );

        assertEquals(RepairAlertType.VALIDATION_FAILED, alert.type());
    }

    @Test
    void shouldRejectBlankRequiredIdsForAlertsAndEvaluations() {
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1L, new BigDecimal("1.00")),
                new RecordingAlertSink()
        );

        assertThrows(IllegalArgumentException.class, () -> new RepairAlert(
                " ",
                "task-1001",
                RepairAlertType.TIMEOUT_WARNING,
                "warning",
                null,
                10_000L
        ));
        assertThrows(IllegalArgumentException.class, () -> watchdog.evaluate(
                "repair-1001",
                "",
                2L,
                new BigDecimal("2.00"),
                10_000L
        ));
    }

    @Test
    void shouldNotContainProcessOrContainerTerminationCalls() throws IOException {
        Pattern terminationCall = Pattern.compile("\\.(stop|destroy|destroyForcibly|kill)\\s*\\(");

        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/wish/rd/exec/repair/alert"))) {
            List<Path> javaFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertFalse(javaFiles.isEmpty());
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                assertFalse(terminationCall.matcher(source).find(), javaFile.toString());
                assertFalse(source.contains("org.springframework"), javaFile.toString());
                assertFalse(source.contains("com.wish.rd.bootstrap"), javaFile.toString());
                assertFalse(source.contains("com.wish.rd.engine"), javaFile.toString());
                assertFalse(source.contains("com.wish.rd.rag"), javaFile.toString());
                assertFalse(source.contains("ProcessBuilder"), javaFile.toString());
            }
        }
    }

    private static final class RecordingAlertSink implements RepairAlertSinkPort {

        private final List<RepairAlert> alerts = new ArrayList<>();

        @Override
        public void publish(RepairAlert alert) {
            alerts.add(alert);
        }

        private List<RepairAlert> alerts() {
            return List.copyOf(alerts);
        }
    }

    private static final class FailsOnceAlertSink implements RepairAlertSinkPort {

        private final List<RepairAlert> alerts = new ArrayList<>();
        private boolean shouldFail = true;

        @Override
        public void publish(RepairAlert alert) {
            if (shouldFail) {
                shouldFail = false;
                throw new IllegalStateException("publish failed");
            }
            alerts.add(alert);
        }

        private List<RepairAlert> alerts() {
            return List.copyOf(alerts);
        }
    }
}
