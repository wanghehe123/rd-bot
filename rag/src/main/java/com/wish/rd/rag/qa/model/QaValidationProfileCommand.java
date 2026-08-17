package com.wish.rd.rag.qa.model;

import java.util.List;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Editable task or project QA validation profile.
 *
 * <p>{@code buildCommands} / {@code staticCommands} treat {@code null} as undeclared
 * (continue to a lower layer) and an empty list as an explicit skip.
 */
public record QaValidationProfileCommand(
        String mode,
        String baseUrl,
        String startCommand,
        String healthPath,
        List<String> allowedHosts,
        List<String> regressionCommands,
        List<String> buildCommands,
        List<String> staticCommands,
        boolean buildCommandsDeclared,
        boolean staticCommandsDeclared
) {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9.-]+");
    private static final Pattern CREDENTIAL_LITERAL = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key|access[_-]?key|authorization|cookie)"
                    + "(?:\\s*=|\\s+)[\\s]*[^$\\s][^\\s]*"
    );
    private static final Pattern NPM_RUN_DEV = Pattern.compile(
            "(?:^|\\s)npm\\s+run\\s+dev(?:\\s|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEXT_DEV = Pattern.compile(
            "(?:^|\\s)next\\s+dev(?:\\s|$)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Creates a profile command.
     *
     * @param mode QA mode
     * @param baseUrl browser base URL
     * @param startCommand QA start command
     * @param healthPath HTTP health path
     * @param allowedHosts allowed browser hosts
     * @param regressionCommands QA regression commands
     * @param buildCommands host BUILD commands; {@code null} means undeclared
     * @param staticCommands host STATIC commands; {@code null} means undeclared
     * @throws IllegalArgumentException when a field is unsafe or a BUILD command starts a dev server
     */
    public QaValidationProfileCommand(
            String mode,
            String baseUrl,
            String startCommand,
            String healthPath,
            List<String> allowedHosts,
            List<String> regressionCommands,
            List<String> buildCommands,
            List<String> staticCommands
    ) {
        this(
                mode,
                baseUrl,
                startCommand,
                healthPath,
                allowedHosts,
                regressionCommands,
                strings(buildCommands),
                strings(staticCommands),
                buildCommands != null,
                staticCommands != null
        );
    }

    public QaValidationProfileCommand {
        mode = normalizeMode(mode);
        baseUrl = text(baseUrl);
        startCommand = text(startCommand);
        healthPath = text(healthPath);
        allowedHosts = strings(allowedHosts);
        regressionCommands = strings(regressionCommands);
        buildCommands = buildCommandsDeclared ? strings(buildCommands) : List.of();
        staticCommands = staticCommandsDeclared ? strings(staticCommands) : List.of();
        validateUrlAndHosts(mode, baseUrl, healthPath, allowedHosts);
        validateCommand(startCommand, "startCommand");
        regressionCommands.forEach(command -> validateCommand(command, "regressionCommands"));
        buildCommands.forEach(command -> validateCommand(command, "buildCommands"));
        staticCommands.forEach(command -> validateCommand(command, "staticCommands"));
        rejectDevServerBuild(buildCommands);
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

    private static void rejectDevServerBuild(List<String> buildCommands) {
        for (String command : buildCommands) {
            if (NPM_RUN_DEV.matcher(command).find()
                    || NEXT_DEV.matcher(command).find()
                    || isViteDevServerWithHost(command)) {
                throw new IllegalArgumentException("buildCommands must not start a dev server");
            }
        }
    }

    /**
     * Rejects {@code vite --host} style dev servers, including {@code npx vite --host}.
     * Allows {@code vite build --host} because that is a production bind, not {@code vite} dev.
     */
    private static boolean isViteDevServerWithHost(String command) {
        for (String part : command.split("[;&|]+")) {
            String[] tokens = part.strip().split("\\s+");
            int viteAt = -1;
            for (int i = 0; i < tokens.length; i++) {
                if ("vite".equalsIgnoreCase(tokens[i])) {
                    viteAt = i;
                    break;
                }
            }
            if (viteAt < 0) {
                continue;
            }
            // vite build --host 是生产构建绑 host，不是 vite 开发服务器
            if (viteAt + 1 < tokens.length && "build".equalsIgnoreCase(tokens[viteAt + 1])) {
                continue;
            }
            for (int i = viteAt + 1; i < tokens.length; i++) {
                String token = tokens[i].toLowerCase(Locale.ROOT);
                if ("--host".equals(token) || token.startsWith("--host=")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().map(QaValidationProfileCommand::text)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
