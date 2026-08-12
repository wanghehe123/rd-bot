package com.wish.rd.bootstrap.oracle.impl;

import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Host runner for required/forbidden log patterns and unredacted sensitive-content scans. */
public final class LogPatternAssertionRunner implements AssertionRunnerPort {

    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|secret|token|password|authorization|cookie)\\b\\s*[:=]\\s*"
                    + "(?!\\[REDACTED\\])[^\\s]+"
    );
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );

    @Override
    public AssertionResult run(AssertionSpec spec, AssertionEvaluationContext context) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        AssertionType type = spec.assertionType();
        if (type != AssertionType.LOG_MUST_MATCH
                && type != AssertionType.LOG_MUST_NOT_MATCH
                && type != AssertionType.LOG_SECRET_SCAN) {
            return AssertionResult.unsupported(spec.id(), type);
        }
        String content;
        try {
            content = readLog(context.workspaceRoot(), spec.target());
        } catch (IOException | IllegalArgumentException exception) {
            return AssertionResult.error(spec.id(), type, exception.getMessage());
        }
        List<String> evidence = List.copyOf(spec.evidenceRequired());
        if (type == AssertionType.LOG_SECRET_SCAN) {
            String classification = sensitiveClassification(content);
            return classification.isBlank()
                    ? AssertionResult.passed(spec.id(), type, "log sensitive-content scan is clean", evidence)
                    : AssertionResult.failed(spec.id(), type,
                            "sensitive content detected in Host log: " + classification, evidence);
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(spec.expected());
        } catch (PatternSyntaxException exception) {
            return AssertionResult.error(spec.id(), type, "log pattern is invalid: " + exception.getDescription());
        }
        boolean matched = pattern.matcher(content).find();
        if (type == AssertionType.LOG_MUST_MATCH) {
            return matched
                    ? AssertionResult.passed(spec.id(), type, "required log pattern matched", evidence)
                    : AssertionResult.failed(spec.id(), type, "required log pattern did not match", evidence);
        }
        return matched
                ? AssertionResult.failed(spec.id(), type, "forbidden log pattern matched", evidence)
                : AssertionResult.passed(spec.id(), type, "forbidden log pattern did not match", evidence);
    }

    private static String readLog(Path workspaceRoot, String target) throws IOException {
        Path path = resolveUnderWorkspace(workspaceRoot, target);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("log assertion target is not a regular file");
        }
        long bytes = Files.size(path);
        if (bytes > MAX_LOG_BYTES) {
            throw new IllegalArgumentException("log assertion target exceeds " + MAX_LOG_BYTES + " bytes");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path resolveUnderWorkspace(Path workspaceRoot, String target) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        String normalizedTarget = target == null ? "" : target.strip().replace('\\', '/');
        if (normalizedTarget.isBlank() || normalizedTarget.startsWith("/") || normalizedTarget.contains("..")) {
            throw new IllegalArgumentException("log assertion target must be workspace-relative");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(normalizedTarget).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("log assertion target escapes workspace root");
        }
        return resolved;
    }

    private static String sensitiveClassification(String content) {
        if (SECRET_ASSIGNMENT.matcher(content).find()) {
            return "secret assignment";
        }
        if (AWS_ACCESS_KEY.matcher(content).find()) {
            return "access key";
        }
        if (EMAIL_ADDRESS.matcher(content).find()) {
            return "email address";
        }
        return "";
    }
}
