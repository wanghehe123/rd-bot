package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.security.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Host-owned Pi provider proxy. The Pi sidecar presents an opaque lease plus fixed
 * task identity; this service validates the lease, injects the Host credential, and
 * forwards only the frozen provider method/path policy.
 */
@Service
public class PiCredentialRelayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PiCredentialRelayService.class);
    private static final int UNAUTHORIZED = 401;
    private static final int BAD_GATEWAY = 502;
    /** Bounded, redacted excerpt length for non-2xx upstream bodies in Host logs. */
    private static final int ERROR_BODY_LOG_MAX_BYTES = 512;
    private static final List<String> REQUEST_HEADER_ALLOWLIST = List.of(
            "accept",
            "content-type",
            "anthropic-version",
            "anthropic-beta",
            "openai-organization",
            "openai-project",
            "user-agent"
    );
    private static final List<String> RESPONSE_HEADER_ALLOWLIST = List.of(
            "content-type",
            "cache-control",
            "request-id",
            "x-request-id",
            "anthropic-ratelimit-requests-limit",
            "anthropic-ratelimit-requests-remaining",
            "anthropic-ratelimit-tokens-limit",
            "anthropic-ratelimit-tokens-remaining"
    );

    private final PiCredentialLeaseIssuer issuer;
    private final UpstreamClient upstreamClient;
    private final Consumer<RelayAuditEvent> auditSink;

    /**
     * Creates the production Host proxy backed by the configured lease issuer.
     *
     * @param issuer issuer that owns lease expiry, binding, and call-budget semantics
     */
    @Autowired
    public PiCredentialRelayService(PiCredentialLeaseIssuer issuer) {
        this(issuer, new JdkUpstreamClient(), PiCredentialRelayService::logAuditEvent);
    }

    /**
     * Creates an injectable Host proxy for focused tests and alternate trusted transports.
     *
     * @param issuer         issuer that owns lease authorization
     * @param upstreamClient client used only after a grant has been authorized
     * @param auditSink      redacted structured audit receiver
     */
    public PiCredentialRelayService(
            PiCredentialLeaseIssuer issuer,
            UpstreamClient upstreamClient,
            Consumer<RelayAuditEvent> auditSink
    ) {
        if (issuer == null) {
            throw new IllegalArgumentException("issuer must not be null");
        }
        if (upstreamClient == null) {
            throw new IllegalArgumentException("upstreamClient must not be null");
        }
        this.issuer = issuer;
        this.upstreamClient = upstreamClient;
        this.auditSink = auditSink == null ? PiCredentialRelayService::logAuditEvent : auditSink;
    }

    /**
     * Forwards one authorized provider request without exposing its Host credential.
     * Invalid lease, identity, method, path, size, and expiry requests all return an
     * indistinguishable empty 401 response so Pi does not learn a useful oracle.
     *
     * @param token      opaque Pi lease token
     * @param taskId     task identity injected by the trusted sidecar
     * @param stageRunId stage identity injected by the trusted sidecar
     * @param providerId provider identity injected by the trusted sidecar
     * @param method     provider request method
     * @param path       provider-relative request path
     * @param headers    provider request headers
     * @param body       provider request body
     * @return proxied upstream response or a redacted terminal status
     */
    public ProxyResponse proxy(
            String token,
            String taskId,
            String stageRunId,
            String providerId,
            String method,
            String path,
            Map<String, String> headers,
            byte[] body
    ) {
        byte[] safeBody = body == null ? new byte[0] : body.clone();
        String safeMethod = normalizeMethod(method);
        String safePath = normalizePath(path);
        Optional<PiCredentialLeaseIssuer.RelayGrant> grant = issuer.authorize(
                token,
                taskId,
                stageRunId,
                providerId,
                safeMethod,
                safePath,
                safeBody.length
        );
        if (grant.isEmpty()) {
            publishAudit("REJECTED", taskId, stageRunId, providerId, safeMethod, safePath, safeBody.length, 0, UNAUTHORIZED);
            return ProxyResponse.empty(UNAUTHORIZED);
        }

        PiCredentialLeaseIssuer.RelayGrant authorized = grant.get();
        try {
            URI upstreamUri = upstreamUri(authorized.relayPolicy().upstreamBaseUrl(), safePath);
            Map<String, String> outboundHeaders = sanitizedRequestHeaders(headers);
            injectCredential(outboundHeaders, authorized);
            UpstreamResponse upstream = upstreamClient.forward(new UpstreamRequest(
                    upstreamUri,
                    safeMethod,
                    outboundHeaders,
                    safeBody,
                    authorized.relayPolicy().requestTimeout(),
                    authorized.relayPolicy().maxResponseBytes()
            ));
            if (upstream == null || upstream.body().length > authorized.relayPolicy().maxResponseBytes()) {
                publishAudit("RESPONSE_LIMIT", taskId, stageRunId, providerId, safeMethod, safePath,
                        safeBody.length, upstream == null ? 0 : upstream.body().length, BAD_GATEWAY);
                return ProxyResponse.empty(BAD_GATEWAY);
            }
            byte[] redactedBody = redactCredential(upstream.body(), authorized.rawCredential());
            Map<String, String> responseHeaders = sanitizedResponseHeaders(upstream.headers());
            publishAudit("FORWARDED", taskId, stageRunId, providerId, safeMethod, safePath,
                    safeBody.length, redactedBody.length, upstream.status());
            logUpstreamErrorBody(taskId, stageRunId, providerId, safeMethod, safePath, upstream.status(), redactedBody);
            return new ProxyResponse(upstream.status(), responseHeaders, redactedBody);
        } catch (ResponseTooLargeException exception) {
            publishAudit("RESPONSE_LIMIT", taskId, stageRunId, providerId, safeMethod, safePath,
                    safeBody.length, 0, BAD_GATEWAY);
            return ProxyResponse.empty(BAD_GATEWAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            publishAudit("UPSTREAM_INTERRUPTED", taskId, stageRunId, providerId, safeMethod, safePath,
                    safeBody.length, 0, BAD_GATEWAY);
            return ProxyResponse.empty(BAD_GATEWAY);
        } catch (Exception exception) {
            publishAudit("UPSTREAM_FAILED", taskId, stageRunId, providerId, safeMethod, safePath,
                    safeBody.length, 0, BAD_GATEWAY);
            return ProxyResponse.empty(BAD_GATEWAY);
        }
    }

    private void publishAudit(
            String outcome,
            String taskId,
            String stageRunId,
            String providerId,
            String method,
            String path,
            int requestBytes,
            int responseBytes,
            int status
    ) {
        auditSink.accept(new RelayAuditEvent(
                outcome,
                taskId,
                stageRunId,
                providerId,
                method,
                path,
                requestBytes,
                responseBytes,
                status
        ));
    }

    private static void logAuditEvent(RelayAuditEvent event) {
        LOGGER.info(
                "pi_credential_relay outcome={} taskId={} stageRunId={} providerId={} method={} path={} requestBytes={} responseBytes={} status={}",
                event.outcome(),
                event.taskId(),
                event.stageRunId(),
                event.providerId(),
                event.method(),
                event.path(),
                event.requestBytes(),
                event.responseBytes(),
                event.status()
        );
    }

    private static void logUpstreamErrorBody(
            String taskId,
            String stageRunId,
            String providerId,
            String method,
            String path,
            int status,
            byte[] redactedBody
    ) {
        if (status >= 200 && status < 300) {
            return;
        }
        LOGGER.warn(
                "pi_credential_relay upstream_error taskId={} stageRunId={} providerId={} method={} path={} status={} responseBodyExcerpt={}",
                SecretRedactor.redactFreeform(taskId == null ? "" : taskId),
                SecretRedactor.redactFreeform(stageRunId == null ? "" : stageRunId),
                SecretRedactor.redactFreeform(providerId == null ? "" : providerId),
                SecretRedactor.redactFreeform(method == null ? "" : method),
                SecretRedactor.redactFreeform(path == null ? "" : path),
                status,
                boundedErrorBodyExcerpt(redactedBody, ERROR_BODY_LOG_MAX_BYTES)
        );
    }

    /**
     * Builds a Host-safe excerpt of a non-2xx upstream body. Credentials must already be
     * stripped from {@code body}; this also redacts Authorization/api-key patterns and
     * truncates to a fixed byte budget so logs stay diagnosable without leaking secrets.
     *
     * @param body     credential-redacted upstream body
     * @param maxBytes maximum UTF-8 byte length of the returned excerpt
     * @return redacted excerpt, never null
     */
    static String boundedErrorBodyExcerpt(byte[] body, int maxBytes) {
        if (body == null || body.length == 0 || maxBytes <= 0) {
            return "";
        }
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');
        String redacted = SecretRedactor.redactFreeform(text);
        byte[] utf8 = redacted.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.length <= maxBytes) {
            return redacted;
        }
        final byte[] ellipsis = "...".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int end = Math.max(0, Math.min(maxBytes - ellipsis.length, utf8.length));
        // Truncate on UTF-8 byte boundaries without splitting a multi-byte character.
        while (end > 0 && end < utf8.length && (utf8[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(utf8, 0, end, java.nio.charset.StandardCharsets.UTF_8) + "...";
    }

    private static URI upstreamUri(String upstreamBaseUrl, String requestPath) {
        URI base = URI.create(upstreamBaseUrl);
        String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        String combinedPath = requestPath.startsWith(basePath + "/") || requestPath.equals(basePath)
                ? requestPath
                : basePath + requestPath;
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), combinedPath, null, null);
        } catch (Exception exception) {
            throw new IllegalArgumentException("relay upstream URI is invalid", exception);
        }
    }

    private static Map<String, String> sanitizedRequestHeaders(Map<String, String> source) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (source == null) {
            return headers;
        }
        source.forEach((key, value) -> {
            String normalizedKey = normalizeHeaderName(key);
            if (!REQUEST_HEADER_ALLOWLIST.contains(normalizedKey)) {
                return;
            }
            String safeValue = normalizeHeaderValue(value);
            if (!safeValue.isBlank()) {
                headers.put(canonicalHeaderName(normalizedKey), safeValue);
            }
        });
        return headers;
    }

    private static Map<String, String> sanitizedResponseHeaders(Map<String, String> source) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (source == null) {
            return headers;
        }
        source.forEach((key, value) -> {
            String normalizedKey = normalizeHeaderName(key);
            if (!RESPONSE_HEADER_ALLOWLIST.contains(normalizedKey)) {
                return;
            }
            String safeValue = normalizeHeaderValue(value);
            if (!safeValue.isBlank()) {
                headers.put(canonicalHeaderName(normalizedKey), safeValue);
            }
        });
        return headers;
    }

    private static void injectCredential(
            Map<String, String> headers,
            PiCredentialLeaseIssuer.RelayGrant grant
    ) {
        headers.remove("Authorization");
        headers.remove("X-Api-Key");
        if (grant.relayPolicy().upstreamAuthHeader()) {
            headers.put("Authorization", "Bearer " + grant.rawCredential());
        } else {
            headers.put("X-Api-Key", grant.rawCredential());
        }
    }

    private static String normalizeMethod(String method) {
        return method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.strip();
    }

    private static String normalizeHeaderName(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeHeaderValue(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.contains("\r") || normalized.contains("\n") ? "" : normalized;
    }

    private static String canonicalHeaderName(String lowerCaseName) {
        return switch (lowerCaseName) {
            case "content-type" -> "Content-Type";
            case "cache-control" -> "Cache-Control";
            case "user-agent" -> "User-Agent";
            case "request-id" -> "Request-Id";
            case "x-request-id" -> "X-Request-Id";
            case "x-api-key" -> "X-Api-Key";
            default -> lowerCaseName;
        };
    }

    private static byte[] redactCredential(byte[] source, String rawCredential) {
        byte[] safeSource = source == null ? new byte[0] : source.clone();
        byte[] secret = rawCredential == null ? new byte[0] : rawCredential.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (secret.length == 0 || safeSource.length < secret.length) {
            return safeSource;
        }
        byte[] replacement = SecretRedactor.REDACTED.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(safeSource.length);
        for (int index = 0; index < safeSource.length; ) {
            if (matches(safeSource, index, secret)) {
                output.writeBytes(replacement);
                index += secret.length;
            } else {
                output.write(safeSource[index]);
                index++;
            }
        }
        return output.toByteArray();
    }

    private static boolean matches(byte[] source, int offset, byte[] candidate) {
        if (offset + candidate.length > source.length) {
            return false;
        }
        for (int index = 0; index < candidate.length; index++) {
            if (source[offset + index] != candidate[index]) {
                return false;
            }
        }
        return true;
    }

    /** Trusted transport abstraction used by the Host relay after a lease is authorized. */
    @FunctionalInterface
    public interface UpstreamClient {

        /**
         * Sends one authorized request to the frozen provider origin.
         *
         * @param request Host-authorized request
         * @return provider response
         * @throws Exception when the trusted transport cannot complete the request
         */
        UpstreamResponse forward(UpstreamRequest request) throws Exception;
    }

    /**
     * Host-authorized outbound provider request. It is never serialized into Pi output.
     *
     * @param uri              frozen upstream URI
     * @param method           frozen allowed method
     * @param headers          filtered headers plus Host credential injection
     * @param body             bounded Pi request body
     * @param timeout          Host provider deadline
     * @param maxResponseBytes response size ceiling
     */
    public record UpstreamRequest(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maxResponseBytes
    ) {
        public UpstreamRequest {
            if (uri == null || method == null || method.isBlank() || timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("upstream request is incomplete");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body.clone();
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException("maxResponseBytes must be positive");
            }
        }
    }

    /**
     * Provider response returned to the Host proxy before response header filtering and secret redaction.
     *
     * @param status  upstream HTTP status
     * @param headers upstream response headers
     * @param body    bounded upstream response body
     */
    public record UpstreamResponse(int status, Map<String, String> headers, byte[] body) {
        public UpstreamResponse {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("upstream status must be a valid HTTP status");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body.clone();
        }
    }

    /**
     * Redacted proxy result written back to the trusted sidecar, never containing a provider credential.
     *
     * @param status  HTTP status
     * @param headers filtered safe response headers
     * @param body    response body with any echoed Host credential removed
     */
    public record ProxyResponse(int status, Map<String, String> headers, byte[] body) {
        public ProxyResponse {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body.clone();
        }

        private static ProxyResponse empty(int status) {
            return new ProxyResponse(status, Map.of(), new byte[0]);
        }
    }

    /**
     * Structured redacted audit signal. The lease token, raw credential, and request/response bodies are omitted.
     *
     * @param outcome       relay outcome category
     * @param taskId        bound task id
     * @param stageRunId    bound stage run id
     * @param providerId    bound provider id
     * @param method        normalized method
     * @param path          normalized path
     * @param requestBytes  request byte count
     * @param responseBytes response byte count
     * @param status        terminal status
     */
    public record RelayAuditEvent(
            String outcome,
            String taskId,
            String stageRunId,
            String providerId,
            String method,
            String path,
            int requestBytes,
            int responseBytes,
            int status
    ) {
        public RelayAuditEvent {
            outcome = safeAuditText(outcome);
            taskId = safeAuditText(taskId);
            stageRunId = safeAuditText(stageRunId);
            providerId = safeAuditText(providerId);
            method = safeAuditText(method);
            path = safeAuditText(path);
            requestBytes = Math.max(0, requestBytes);
            responseBytes = Math.max(0, responseBytes);
        }

        private static String safeAuditText(String value) {
            return SecretRedactor.redactFreeform(value == null ? "" : value.strip());
        }
    }

    private static final class JdkUpstreamClient implements UpstreamClient {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        @Override
        public UpstreamResponse forward(UpstreamRequest request) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .timeout(request.timeout())
                    .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
            request.headers().forEach(builder::header);
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return new UpstreamResponse(
                        response.statusCode(),
                        firstHeaderValues(response.headers().map()),
                        readBounded(body, request.maxResponseBytes())
                );
            }
        }

        private static Map<String, String> firstHeaderValues(Map<String, List<String>> headers) {
            Map<String, String> values = new LinkedHashMap<>();
            if (headers != null) {
                headers.forEach((key, list) -> {
                    if (list != null && !list.isEmpty()) {
                        values.put(key, list.getFirst());
                    }
                });
            }
            return values;
        }

        private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    throw new ResponseTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() {
            super("relay upstream response exceeded the configured byte limit");
        }
    }
}
