package com.wish.rd.engine.oracle;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative SQL assertion policy shared by the Host compiler and JDBC runner.
 */
public final class ReadOnlySqlPolicy {

    private static final Pattern STARTS_WITH_READ_QUERY = Pattern.compile("^(?:select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN_KEYWORD = Pattern.compile(
            "\\b(?:insert|update|delete|merge|upsert|create|alter|drop|truncate|grant|revoke|copy|call|execute|vacuum|analyze|set)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QUALIFIED_FROM_OR_JOIN = Pattern.compile(
            "\\b(?:from|join)\\s+(?:only\\s+)?(?:\\\"?([A-Za-z_][A-Za-z0-9_$]*)\\\"?)\\s*\\.",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> DEFAULT_ALLOWED_SCHEMAS = Set.of("public");

    private ReadOnlySqlPolicy() {
    }

    /**
     * Validates a single read-only SELECT/CTE statement against allowed schemas.
     *
     * @param rawSql source SQL
     * @param allowedSchemas allowed PostgreSQL schemas, empty means {@code public}
     * @return normalized SQL
     */
    public static String validate(String rawSql, Set<String> allowedSchemas) {
        String sql = rawSql == null ? "" : rawSql.strip();
        if (sql.isBlank()) {
            throw new IllegalArgumentException("SQL assertion target must not be blank");
        }
        if (sql.indexOf(';') >= 0) {
            throw new IllegalArgumentException("SQL assertion must contain one statement without semicolons");
        }
        if (sql.contains("--") || sql.contains("/*") || sql.contains("*/")) {
            throw new IllegalArgumentException("SQL assertion must not contain comments");
        }
        if (!STARTS_WITH_READ_QUERY.matcher(sql).find()) {
            throw new IllegalArgumentException("SQL assertion must use a read-only SELECT or WITH query");
        }
        if (FORBIDDEN_KEYWORD.matcher(sql).find()) {
            throw new IllegalArgumentException("SQL assertion must be read-only and contain no write or DDL keyword");
        }

        Set<String> normalizedSchemas = normalizeSchemas(allowedSchemas);
        Matcher matcher = QUALIFIED_FROM_OR_JOIN.matcher(sql);
        while (matcher.find()) {
            String schema = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!normalizedSchemas.contains(schema)) {
                throw new IllegalArgumentException("SQL assertion references a disallowed schema: " + schema);
            }
        }
        return sql;
    }

    /** Returns the default schema allowlist used by the compiler. */
    public static Set<String> defaultAllowedSchemas() {
        return DEFAULT_ALLOWED_SCHEMAS;
    }

    private static Set<String> normalizeSchemas(Set<String> allowedSchemas) {
        Set<String> source = allowedSchemas == null || allowedSchemas.isEmpty()
                ? DEFAULT_ALLOWED_SCHEMAS
                : allowedSchemas;
        Set<String> normalized = new LinkedHashSet<>();
        for (String schema : source) {
            String value = schema == null ? "" : schema.strip().toLowerCase(Locale.ROOT);
            if (!value.matches("[a-z_][a-z0-9_$]*")) {
                throw new IllegalArgumentException("SQL assertion allowed schema is invalid: " + schema);
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("SQL assertion requires at least one allowed schema");
        }
        return Set.copyOf(normalized);
    }
}
