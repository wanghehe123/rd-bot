package com.wish.rd.bootstrap.oracle.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Host adapter for {@link AssertionType#HTTP_JSONPATH}.
 * The request action is a read-only {@code GET <path>} and the target is a
 * small JSONPath expression evaluated against the JSON response body.
 */
public final class HttpJsonPathAssertionRunner implements AssertionRunnerPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Fetches a response body for a Host-approved read-only HTTP request.
     */
    @FunctionalInterface
    public interface HttpJsonClient {
        String body(URI uri, Duration timeout) throws Exception;
    }

    private final HttpJsonClient client;

    public HttpJsonPathAssertionRunner() {
        this(defaultClient());
    }

    public HttpJsonPathAssertionRunner(HttpJsonClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public AssertionResult run(
            AssertionSpec spec,
            AssertionEvaluationContext context
    ) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (spec.assertionType() != AssertionType.HTTP_JSONPATH) {
            return AssertionResult.unsupported(spec.id(), spec.assertionType());
        }
        if (context.baseUrl().isBlank()) {
            return AssertionResult.error(
                    spec.id(),
                    AssertionType.HTTP_JSONPATH,
                    "baseUrl is required for HTTP_JSONPATH"
            );
        }

        URI uri;
        String jsonPath;
        try {
            uri = HttpStatusAssertionRunner.resolveUri(context.baseUrl(), requestPath(spec.action()));
            jsonPath = validateJsonPath(spec.target());
            validateOperator(spec.operator());
            if (spec.expected().isBlank()) {
                throw new IllegalArgumentException("HTTP_JSONPATH expected value must not be blank");
            }
        } catch (IllegalArgumentException exception) {
            return AssertionResult.error(spec.id(), AssertionType.HTTP_JSONPATH, exception.getMessage());
        }

        Duration timeout = Duration.ofMillis(Math.max(1L, spec.timeoutMillis()));
        String responseBody;
        try {
            responseBody = client.body(uri, timeout);
        } catch (Exception exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return AssertionResult.error(
                    spec.id(),
                    AssertionType.HTTP_JSONPATH,
                    "HTTP JSON probe failed: " + message
            );
        }

        JsonNode document;
        try {
            document = OBJECT_MAPPER.readTree(responseBody == null ? "" : responseBody);
            if (document == null) {
                throw new IllegalArgumentException("response body is empty");
            }
        } catch (Exception exception) {
            return AssertionResult.error(
                    spec.id(),
                    AssertionType.HTTP_JSONPATH,
                    "HTTP_JSONPATH response is not valid JSON: " + safeMessage(exception)
            );
        }

        JsonNode actual;
        try {
            actual = document.at(toJsonPointer(jsonPath));
        } catch (IllegalArgumentException exception) {
            return AssertionResult.error(spec.id(), AssertionType.HTTP_JSONPATH, exception.getMessage());
        }
        List<String> evidence = List.copyOf(spec.evidenceRequired());
        if (actual.isMissingNode()) {
            return AssertionResult.failed(
                    spec.id(),
                    AssertionType.HTTP_JSONPATH,
                    "JSONPath " + jsonPath + " did not match a response value",
                    evidence
            );
        }

        if (matches(actual, spec.expected())) {
            return AssertionResult.passed(
                    spec.id(),
                    AssertionType.HTTP_JSONPATH,
                    "JSONPath " + jsonPath + " matched for " + uri,
                    evidence
            );
        }
        return AssertionResult.failed(
                spec.id(),
                AssertionType.HTTP_JSONPATH,
                "JSONPath " + jsonPath + " expected " + spec.expected()
                        + " but was " + actual,
                evidence
        );
    }

    private static String requestPath(String action) {
        String normalized = action == null ? "" : action.strip();
        int separator = normalized.indexOf(' ');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw new IllegalArgumentException(
                    "HTTP_JSONPATH action must be a GET request such as 'GET /api'"
            );
        }
        String method = normalized.substring(0, separator).strip().toUpperCase(Locale.ROOT);
        if (!"GET".equals(method)) {
            throw new IllegalArgumentException("HTTP_JSONPATH action must use GET");
        }
        return normalized.substring(separator + 1).strip();
    }

    private static String validateJsonPath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.strip();
        if (path.isBlank() || !path.startsWith("$")) {
            throw new IllegalArgumentException("HTTP_JSONPATH target must start with '$'");
        }
        toJsonPointer(path);
        return path;
    }

    private static void validateOperator(String rawOperator) {
        String operator = rawOperator == null ? "" : rawOperator.strip().toLowerCase(Locale.ROOT);
        if (!operator.equals("eq") && !operator.equals("equals") && !operator.equals("==")) {
            throw new IllegalArgumentException("HTTP_JSONPATH operator must be eq");
        }
    }

    private static String toJsonPointer(String jsonPath) {
        if (!jsonPath.startsWith("$")) {
            throw new IllegalArgumentException("HTTP_JSONPATH target must start with '$'");
        }
        if ("$".equals(jsonPath)) {
            return "";
        }

        StringBuilder pointer = new StringBuilder();
        int index = 1;
        while (index < jsonPath.length()) {
            char current = jsonPath.charAt(index);
            if (current == '.') {
                int start = ++index;
                while (index < jsonPath.length()
                        && jsonPath.charAt(index) != '.'
                        && jsonPath.charAt(index) != '[') {
                    index++;
                }
                if (start == index) {
                    throw new IllegalArgumentException("HTTP_JSONPATH target contains an empty field");
                }
                appendField(pointer, jsonPath.substring(start, index));
                continue;
            }
            if (current == '[') {
                int closing = jsonPath.indexOf(']', index + 1);
                if (closing < 0 || closing == index + 1) {
                    throw new IllegalArgumentException("HTTP_JSONPATH target contains an invalid array index");
                }
                String indexValue = jsonPath.substring(index + 1, closing);
                if (!indexValue.chars().allMatch(Character::isDigit)) {
                    throw new IllegalArgumentException("HTTP_JSONPATH target supports numeric array indexes only");
                }
                pointer.append('/').append(indexValue);
                index = closing + 1;
                continue;
            }
            throw new IllegalArgumentException(
                    "HTTP_JSONPATH target must use dot fields or numeric array indexes"
            );
        }
        return pointer.toString();
    }

    private static void appendField(StringBuilder pointer, String field) {
        pointer.append('/')
                .append(field.replace("~", "~0").replace("/", "~1"));
    }

    private static boolean matches(JsonNode actual, String expected) {
        String normalized = expected == null ? "" : expected.strip();
        try {
            JsonNode expectedNode = OBJECT_MAPPER.readTree(normalized);
            if (expectedNode != null && actual.equals(expectedNode)) {
                return true;
            }
        } catch (Exception ignored) {
            // Plain text expected values remain supported for string fields.
        }
        return actual.isValueNode() && actual.asText("").equals(normalized);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static HttpJsonClient defaultClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        };
    }
}
