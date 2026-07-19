package com.wish.rd.rag.qa.model;

import java.util.List;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Editable task or project QA validation profile. */
public record QaValidationProfileCommand(
        String mode,
        String baseUrl,
        String startCommand,
        String healthPath,
        List<String> allowedHosts,
        List<String> regressionCommands
) {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9.-]+");
    private static final Pattern CREDENTIAL_LITERAL = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key|access[_-]?key|authorization|cookie)"
                    + "(?:\\s*=|\\s+)[\\s]*[^$\\s][^\\s]*"
    );

    public QaValidationProfileCommand {
        mode = normalizeMode(mode);
        baseUrl = text(baseUrl);
        startCommand = text(startCommand);
        healthPath = text(healthPath);
        allowedHosts = strings(allowedHosts);
        regressionCommands = strings(regressionCommands);
        validateUrlAndHosts(mode, baseUrl, healthPath, allowedHosts);
        validateCommand(startCommand, "startCommand");
        regressionCommands.forEach(command -> validateCommand(command, "regressionCommands"));
        if ("REQUIRED".equals(mode)
                && (baseUrl.isBlank() || startCommand.isBlank() || allowedHosts.isEmpty())) {
            throw new IllegalArgumentException(
                    "REQUIRED QA profile needs baseUrl, startCommand and allowedHosts"
            );
        }
    }

    private static String normalizeMode(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "REQUIRED", "DISABLED").contains(normalized)) {
            throw new IllegalArgumentException("QA profile mode must be AUTO, REQUIRED or DISABLED");
        }
        return normalized;
    }

    private static void validateUrlAndHosts(
            String mode,
            String baseUrl,
            String healthPath,
            List<String> allowedHosts
    ) {
        for (String host : allowedHosts) {
            if (!HOST_PATTERN.matcher(host).matches() || host.startsWith(".") || host.endsWith(".")) {
                throw new IllegalArgumentException("allowedHosts contains an invalid host");
            }
        }
        if (!healthPath.isBlank() && (!healthPath.startsWith("/") || healthPath.contains("\n"))) {
            throw new IllegalArgumentException("healthPath must be an absolute HTTP path");
        }
        if (baseUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl must be a valid HTTP URL", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP URL without credentials");
        }
        if ("REQUIRED".equals(mode) && allowedHosts.stream().noneMatch(uri.getHost()::equalsIgnoreCase)) {
            throw new IllegalArgumentException("allowedHosts must include the baseUrl host");
        }
    }

    private static void validateCommand(String command, String fieldName) {
        if (command.isBlank()) {
            return;
        }
        if (command.length() > 2_000 || command.indexOf('\0') >= 0 || command.contains("\n") || command.contains("\r")) {
            throw new IllegalArgumentException(fieldName + " contains unsupported control characters or is too long");
        }
        if (CREDENTIAL_LITERAL.matcher(command).find()) {
            throw new IllegalArgumentException(fieldName + " must not contain credential literals");
        }
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().map(QaValidationProfileCommand::text)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
