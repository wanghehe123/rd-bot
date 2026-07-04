package com.wish.rd.exec.repair.result;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredResultValidatorTest {

    private final StructuredResultValidator validator = new StructuredResultValidator();

    @Test
    void validSuccessResultPasses() {
        StructuredResultValidation validation = validator.validate(validSuccessJson());

        assertTrue(validation.valid());
        assertNotNull(validation.result());
        assertTrue(validation.errors().isEmpty());
        assertEquals("SUCCESS", validation.result().status());
        assertEquals(List.of("src/main/java/App.java"), validation.result().changedFiles());
    }

    @Test
    void protocolShapedSuccessResultPassesWithoutPatchArtifact() {
        StructuredResultValidation validation = validator.validate("""
                {
                  "status": "SUCCESS",
                  "summary": "修复摘要",
                  "changedFiles": ["src/main/java/example/OrderService.java"],
                  "testCommands": ["./mvnw -pl service test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "prBody": "PR 描述正文",
                  "needHumanAction": false
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void extraModelFieldsDoNotInvalidateKnownProtocolFields() {
        StructuredResultValidation validation = validator.validate("""
                {
                  "taskId": "7474757310175383552",
                  "status": "SUCCESS",
                  "summary": "已增加 orders.amount 为空校验",
                  "changedFiles": ["src/main/java/com/example/OrderService.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "prBody": "修复金额为空时订单创建失败的问题",
                  "needHumanAction": false
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
        assertNotNull(validation.result());
        assertEquals("SUCCESS", validation.result().status());
    }

    @Test
    void missingSummaryFails() {
        StructuredResultValidation validation = validator.validate("""
                {
                  "status": "SUCCESS",
                  "prBody": "Fix details",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "needHumanAction": false
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("summary must not be blank"));
        assertNotNull(validation.result());
    }

    @Test
    void successWithBlankPrBodyFails() {
        StructuredResultValidation validation = validator.validate(validSuccessJson("""
                "prBody": "   "
                """));

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("prBody must not be blank when status is SUCCESS"));
    }

    @Test
    void invalidStatusFails() {
        StructuredResultValidation validation = validator.validate(validSuccessJson("""
                "status": "FAILED_VALIDATION"
                """));

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("status must be one of SUCCESS, FAILED, NEED_INFO, UNSAFE"));
    }

    @Test
    void failedWithFailedTestsDoesNotRequirePatchArtifact() {
        StructuredResultValidation validation = validator.validate("""
                {
                  "status": "FAILED",
                  "summary": "Tests still fail",
                  "prBody": "",
                  "changedFiles": [],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "FAILED",
                  "riskLevel": "MEDIUM",
                  "needHumanAction": true
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void needInfoRequiresNeedHumanActionTrue() {
        StructuredResultValidation validation = validator.validate("""
                {
                  "status": "NEED_INFO",
                  "summary": "Need reproduction steps",
                  "prBody": "",
                  "changedFiles": [],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "SKIPPED",
                  "riskLevel": "LOW",
                  "needHumanAction": false
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("needHumanAction must be true when status is NEED_INFO"));
    }

    @Test
    void invalidJsonReturnsParseError() {
        StructuredResultValidation validation = validator.validate("{not-json");

        assertFalse(validation.valid());
        assertEquals(null, validation.result());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("parse error")));
    }

    @Test
    void nonObjectJsonRootsReturnValidationErrorsWithoutThrowing() {
        for (String json : List.of("null", "[]", "\"text\"", "42", "true")) {
            StructuredResultValidation validation = validator.validate(json);

            assertFalse(validation.valid(), json);
            assertEquals(null, validation.result());
            assertTrue(validation.errors().contains("result.json root must be an object"), json);
        }
    }

    @Test
    void changedFilesMustBePresentAndMayBeEmptyOnlyForNeedInfoOrFailed() {
        StructuredResultValidation missing = validator.validate(validSuccessJsonWithout("changedFiles"));
        StructuredResultValidation emptySuccess = validator.validate(validSuccessJson("""
                "changedFiles": []
                """));
        StructuredResultValidation emptyNeedInfo = validator.validate("""
                {
                  "status": "NEED_INFO",
                  "summary": "Need repository access",
                  "prBody": "",
                  "changedFiles": [],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "SKIPPED",
                  "riskLevel": "LOW",
                  "needHumanAction": true
                }
                """);

        assertFalse(missing.valid());
        assertTrue(missing.errors().contains("changedFiles must be present"));
        assertFalse(emptySuccess.valid());
        assertTrue(emptySuccess.errors().contains("changedFiles may be empty only when status is NEED_INFO or FAILED"));
        assertTrue(emptyNeedInfo.valid(), () -> String.join(", ", emptyNeedInfo.errors()));
    }

    @Test
    void changedFilesMustBeArrayOfNonBlankStrings() {
        StructuredResultValidation scalar = validator.validate(validSuccessJson("""
                "changedFiles": "src/main/java/App.java"
                """));
        StructuredResultValidation nullArray = validator.validate(validSuccessJson("""
                "changedFiles": null
                """));
        StructuredResultValidation nonStringElement = validator.validate(validSuccessJson("""
                "changedFiles": [1]
                """));
        StructuredResultValidation blankElement = validator.validate(validSuccessJson("""
                "changedFiles": ["   "]
                """));

        assertFalse(scalar.valid());
        assertTrue(scalar.errors().contains("changedFiles must be an array"));
        assertFalse(nullArray.valid());
        assertTrue(nullArray.errors().contains("changedFiles must be an array"));
        assertFalse(nonStringElement.valid());
        assertTrue(nonStringElement.errors().contains("changedFiles entries must be strings"));
        assertFalse(blankElement.valid());
        assertTrue(blankElement.errors().contains("changedFiles entries must not be blank"));
    }

    @Test
    void testCommandsMustBePresentAndMayBeEmptyOnlyWhenTestStatusIsSkipped() {
        StructuredResultValidation missing = validator.validate(validSuccessJsonWithout("testCommands"));
        StructuredResultValidation emptyPassed = validator.validate(validSuccessJson("""
                "testCommands": []
                """));
        StructuredResultValidation emptySkipped = validator.validate("""
                {
                  "status": "SUCCESS",
                  "summary": "Fixed null pointer",
                  "prBody": "Implementation and tests",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": [],
                  "testStatus": "SKIPPED",
                  "riskLevel": "LOW",
                  "needHumanAction": false
                }
                """);

        assertFalse(missing.valid());
        assertTrue(missing.errors().contains("testCommands must be present"));
        assertFalse(emptyPassed.valid());
        assertTrue(emptyPassed.errors().contains("testCommands may be empty only when testStatus is SKIPPED"));
        assertTrue(emptySkipped.valid(), () -> String.join(", ", emptySkipped.errors()));
    }

    @Test
    void testCommandsMustBeArrayOfNonBlankStrings() {
        StructuredResultValidation scalar = validator.validate(validSuccessJson("""
                "testCommands": "./mvnw test"
                """));
        StructuredResultValidation nullArray = validator.validate(validSuccessJson("""
                "testCommands": null
                """));
        StructuredResultValidation nonStringElement = validator.validate(validSuccessJson("""
                "testCommands": [true]
                """));
        StructuredResultValidation blankElement = validator.validate(validSuccessJson("""
                "testCommands": ["   "]
                """));

        assertFalse(scalar.valid());
        assertTrue(scalar.errors().contains("testCommands must be an array"));
        assertFalse(nullArray.valid());
        assertTrue(nullArray.errors().contains("testCommands must be an array"));
        assertFalse(nonStringElement.valid());
        assertTrue(nonStringElement.errors().contains("testCommands entries must be strings"));
        assertFalse(blankElement.valid());
        assertTrue(blankElement.errors().contains("testCommands entries must not be blank"));
    }

    @Test
    void testStatusMustBePassedFailedOrSkipped() {
        StructuredResultValidation validation = validator.validate(validSuccessJson("""
                "testStatus": "UNKNOWN"
                """));

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("testStatus must be one of PASSED, FAILED, SKIPPED"));
    }

    @Test
    void riskLevelMustBeLowMediumOrHigh() {
        StructuredResultValidation validation = validator.validate(validSuccessJson("""
                "riskLevel": "CRITICAL"
                """));

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("riskLevel must be one of LOW, MEDIUM, HIGH"));
    }

    @Test
    void needHumanActionMustBeTrueWhenStatusIsUnsafeOrTestStatusIsFailed() {
        StructuredResultValidation unsafe = validator.validate(validSuccessJson("""
                "status": "UNSAFE",
                "needHumanAction": false
                """));
        StructuredResultValidation failedTests = validator.validate(validSuccessJson("""
                "testStatus": "FAILED",
                "needHumanAction": false
                """));

        assertFalse(unsafe.valid());
        assertTrue(unsafe.errors().contains("needHumanAction must be true when status is UNSAFE"));
        assertFalse(failedTests.valid());
        assertTrue(failedTests.errors().contains("needHumanAction must be true when testStatus is FAILED"));
    }

    @Test
    void needHumanActionMayBeOmittedButMustBeBooleanWhenPresent() {
        StructuredResultValidation missing = validator.validate("""
                {
                  "status": "SUCCESS",
                  "summary": "Fixed null pointer",
                  "prBody": "Implementation and tests",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW"
                }
                """);
        StructuredResultValidation nullValue = validator.validate("""
                {
                  "status": "SUCCESS",
                  "summary": "Fixed null pointer",
                  "prBody": "Implementation and tests",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "needHumanAction": null
                }
                """);
        StructuredResultValidation stringValue = validator.validate("""
                {
                  "status": "SUCCESS",
                  "summary": "Fixed null pointer",
                  "prBody": "Implementation and tests",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "needHumanAction": "false"
                }
                """);

        assertTrue(missing.valid());
        assertFalse(nullValue.valid());
        assertTrue(nullValue.errors().contains("needHumanAction must be boolean"));
        assertFalse(stringValue.valid());
        assertTrue(stringValue.errors().contains("needHumanAction must be boolean"));
    }

    @Test
    void invalidBusinessFieldsReturnAllDetectedErrors() {
        StructuredResultValidation validation = validator.validate("""
                {
                  "status": "INVALID",
                  "summary": "",
                  "prBody": "",
                  "changedFiles": [],
                  "testCommands": [],
                  "testStatus": "UNKNOWN",
                  "riskLevel": "CRITICAL",
                  "needHumanAction": false
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("status must be one of SUCCESS, FAILED, NEED_INFO, UNSAFE"));
        assertTrue(validation.errors().contains("summary must not be blank"));
        assertTrue(validation.errors().contains("changedFiles may be empty only when status is NEED_INFO or FAILED"));
        assertTrue(validation.errors().contains("testCommands may be empty only when testStatus is SKIPPED"));
        assertTrue(validation.errors().contains("testStatus must be one of PASSED, FAILED, SKIPPED"));
        assertTrue(validation.errors().contains("riskLevel must be one of LOW, MEDIUM, HIGH"));
    }

    @Test
    void resultValidationClassesDoNotImportDockerGithubSpringOrFilesystemPackages() throws IOException {
        Path resultPackage = resultSourceRoot();
        List<String> forbiddenImports = List.of(
                "import com.wish.rd.exec.repair.docker.",
                "import org.kohsuke.github.",
                "import org.springframework.",
                "import java.io.",
                "import java.nio.file."
        );

        try (var files = Files.list(resultPackage)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> assertForbiddenImports(path, forbiddenImports));
        }
    }

    private static void assertForbiddenImports(Path path, List<String> forbiddenImports) {
        try {
            String source = Files.readString(path);
            forbiddenImports.forEach(forbidden -> assertFalse(
                    source.contains(forbidden),
                    path + " must not import " + forbidden
            ));
        } catch (IOException e) {
            throw new AssertionError("failed to read " + path, e);
        }
    }

    private static Path resultSourceRoot() {
        Path currentDirectory = Path.of("").toAbsolutePath();
        Path moduleRelative = currentDirectory.resolve("src/main/java/com/wish/rd/exec/repair/result");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = currentDirectory.resolve("exec/src/main/java/com/wish/rd/exec/repair/result");
        if (Files.isDirectory(reactorRelative)) {
            return reactorRelative;
        }
        throw new IllegalStateException("Cannot find result source root from " + currentDirectory);
    }

    private static String validSuccessJson() {
        return validSuccessJson("");
    }

    private static String validSuccessJson(String overrideFields) {
        String overrides = overrideFields == null ? "" : overrideFields.strip();
        return """
                {
                  "status": "SUCCESS",
                  "summary": "Fixed null pointer",
                  "prBody": "Implementation and tests",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "needHumanAction": false
                }
                """.replace(overriddenFields(overrides), overrides.isBlank() ? "" : overrides + ",");
    }

    private static String validSuccessJsonWithout(String fieldName) {
        return validSuccessJson().replaceAll("(?s)\\s+\"" + fieldName + "\"\\s*:\\s*(\\[[^]]*]|\"[^\"]*\"|false|true),?", "");
    }

    private static String overriddenFields(String overrides) {
        if (overrides.isBlank()) {
            return "";
        }
        String fieldName = overrides.substring(overrides.indexOf('"') + 1, overrides.indexOf('"', overrides.indexOf('"') + 1));
        return switch (fieldName) {
            case "status" -> "\"status\": \"SUCCESS\",";
            case "prBody" -> "\"prBody\": \"Implementation and tests\",";
            case "changedFiles" -> "\"changedFiles\": [\"src/main/java/App.java\"],";
            case "testCommands" -> "\"testCommands\": [\"./mvnw test\"],";
            case "testStatus" -> "\"testStatus\": \"PASSED\",";
            case "riskLevel" -> "\"riskLevel\": \"LOW\",";
            case "needHumanAction" -> "\"needHumanAction\": false";
            default -> throw new IllegalArgumentException("unsupported override field: " + fieldName);
        };
    }
}
