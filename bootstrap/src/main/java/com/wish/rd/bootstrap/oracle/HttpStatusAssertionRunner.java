package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.AssertionResult;
import com.wish.rd.engine.oracle.AssertionRunnerPort;
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
 * Host adapter for {@link AssertionType#HTTP_STATUS}.
 * Requests are constrained to the evaluation {@code baseUrl} host.
 */
public final class HttpStatusAssertionRunner implements AssertionRunnerPort {

    @FunctionalInterface
    public interface HttpStatusClient {
        int status(URI uri, Duration timeout) throws Exception;
    }

    private final HttpStatusClient client;

    public HttpStatusAssertionRunner() {
        this(defaultClient());
    }

    public HttpStatusAssertionRunner(HttpStatusClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public AssertionResult run(AssertionSpec spec, AssertionEvaluationContext context) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (spec.assertionType() != AssertionType.HTTP_STATUS) {
            return AssertionResult.unsupported(spec.id(), spec.assertionType());
        }
        if (context.baseUrl().isBlank()) {
            return AssertionResult.error(spec.id(), AssertionType.HTTP_STATUS, "baseUrl is required for HTTP_STATUS");
        }

        URI uri;
        int expected;
        try {
            uri = resolveUri(context.baseUrl(), spec.target());
            expected = parseExpectedStatus(spec.expected());
        } catch (IllegalArgumentException exception) {
            return AssertionResult.error(spec.id(), AssertionType.HTTP_STATUS, exception.getMessage());
        }

        Duration timeout = Duration.ofMillis(Math.max(1L, spec.timeoutMillis()));
        int actual;
        try {
            actual = client.status(uri, timeout);
        } catch (Exception exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return AssertionResult.error(spec.id(), AssertionType.HTTP_STATUS, "HTTP probe failed: " + message);
        }

        List<String> evidence = List.copyOf(spec.evidenceRequired());
        if (actual == expected) {
            return AssertionResult.passed(
                    spec.id(),
                    AssertionType.HTTP_STATUS,
                    "HTTP status " + actual + " for " + uri,
                    evidence
            );
        }
        return AssertionResult.failed(
                spec.id(),
                AssertionType.HTTP_STATUS,
                "expected HTTP status " + expected + " but was " + actual + " for " + uri,
                evidence
        );
    }

    static URI resolveUri(String baseUrl, String target) {
        URI base = URI.create(baseUrl.strip());
        if (base.getScheme() == null || base.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL");
        }
        String scheme = base.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("baseUrl scheme must be http or https");
        }

        String rawTarget = target == null ? "" : target.strip();
        if (rawTarget.isBlank()) {
            throw new IllegalArgumentException("HTTP_STATUS target must not be blank");
        }
        if (rawTarget.contains("://") || rawTarget.startsWith("//")) {
            throw new IllegalArgumentException("HTTP_STATUS target must be path-relative to baseUrl");
        }
        if (rawTarget.contains("..")) {
            throw new IllegalArgumentException("HTTP_STATUS target must not contain '..'");
        }

        String path = rawTarget.startsWith("/") ? rawTarget : "/" + rawTarget;
        URI resolved = base.resolve(path);
        if (!schemeEquals(base, resolved) || !hostEquals(base, resolved)) {
            throw new IllegalArgumentException("HTTP_STATUS target escapes baseUrl host");
        }
        return resolved;
    }

    private static int parseExpectedStatus(String expected) {
        String value = expected == null ? "" : expected.strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException("HTTP_STATUS expected status must not be blank");
        }
        try {
            int status = Integer.parseInt(value);
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("HTTP_STATUS expected status out of range: " + status);
            }
            return status;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("HTTP_STATUS expected must be an integer status code");
        }
    }

    private static boolean schemeEquals(URI left, URI right) {
        return left.getScheme() != null
                && right.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme());
    }

    private static boolean hostEquals(URI left, URI right) {
        return left.getHost() != null
                && right.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && left.getPort() == right.getPort();
    }

    private static HttpStatusClient defaultClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        };
    }
}
