package com.wish.rd.engine.oracle.impl;

import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Host runner for {@link AssertionType#FILE_EXISTS} and {@link AssertionType#FILE_FORBIDDEN}.
 * Paths are resolved under the evaluation workspace root only.
 */
public final class FileAssertionRunner implements AssertionRunnerPort {

    @Override
    public AssertionResult run(AssertionSpec spec, AssertionEvaluationContext context) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        AssertionType type = spec.assertionType();
        if (type != AssertionType.FILE_EXISTS && type != AssertionType.FILE_FORBIDDEN) {
            return AssertionResult.unsupported(spec.id(), type);
        }
        Path target;
        try {
            target = resolveUnderWorkspace(context.workspaceRoot(), spec.target());
        } catch (IllegalArgumentException exception) {
            return AssertionResult.error(spec.id(), type, exception.getMessage());
        }
        boolean exists = Files.exists(target);
        boolean expectExists = type == AssertionType.FILE_EXISTS;
        boolean matches = expectExists == exists;
        String message = expectExists
                ? (exists ? "file exists: " + spec.target() : "file missing: " + spec.target())
                : (exists ? "forbidden file present: " + spec.target() : "forbidden file absent: " + spec.target());
        List<String> evidence = List.copyOf(spec.evidenceRequired());
        return matches
                ? AssertionResult.passed(spec.id(), type, message, evidence)
                : AssertionResult.failed(spec.id(), type, message, evidence);
    }

    static Path resolveUnderWorkspace(Path workspaceRoot, String relativeTarget) {
        String target = relativeTarget == null ? "" : relativeTarget.strip();
        if (target.isBlank()) {
            throw new IllegalArgumentException("file assertion target must not be blank");
        }
        if (target.startsWith("/") || target.matches("^[A-Za-z]:\\\\.*")) {
            throw new IllegalArgumentException("file assertion target must be workspace-relative");
        }
        String normalized = target.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("file assertion target must not contain '..'");
        }
        Path resolved = workspaceRoot.resolve(target).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("file assertion target escapes workspace root");
        }
        return resolved;
    }
}
