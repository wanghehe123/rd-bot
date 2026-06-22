package com.wish.rd.bootstrap.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * GitHub REST code-platform adapter. HTTP is isolated behind {@link GitHubRequestSender}
 * so tests can validate request construction without network access.
 */
@Component
@ConditionalOnProperty(prefix = "rd.github.code-platform", name = "mode", havingValue = "real")
public class GitHubCodePlatformAdapter implements CodePlatformPort {

    private static final String API_VERSION = "2022-11-28";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final GitHubCodePlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final GitHubRequestSender sender;

    /**
     * 创建真实 GitHub 适配器。
     *
     * @param properties   GitHub 代码平台配置
     * @param objectMapper JSON 解析器
     */
    @Autowired
    public GitHubCodePlatformAdapter(
            GitHubCodePlatformProperties properties,
            ObjectMapper objectMapper
    ) {
        this(properties, objectMapper, new JavaHttpGitHubRequestSender());
    }

    /**
     * 创建可注入 sender 的 GitHub 适配器，供单元测试避免真实网络。
     *
     * @param properties   GitHub 代码平台配置
     * @param objectMapper JSON 解析器
     * @param sender       HTTP sender
     */
    public GitHubCodePlatformAdapter(
            GitHubCodePlatformProperties properties,
            ObjectMapper objectMapper,
            GitHubRequestSender sender
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
    }

    @Override
    public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (!properties.isRepositoryAllowed(command.repoOwner(), command.repoName())) {
            throw new IllegalArgumentException("repository is not allowlisted: " + command.repoOwner() + "/" + command.repoName());
        }
        properties.validateForRealAdapter();

        GitHubHttpRequest request = createPullRequestHttpRequest(command, authorizationHeader());
        GitHubHttpResponse response = sender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub create pull request failed with status " + response.statusCode());
        }
        return toPullRequestResult(command, response.body());
    }

    private GitHubHttpRequest createPullRequestHttpRequest(CreatePullRequestCommand command, String authorizationHeader) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", command.title());
        body.put("head", command.workBranch());
        body.put("base", command.baseBranch());
        body.put("body", command.prBody());
        return new GitHubHttpRequest(
                "POST",
                apiUrl("/repos/%s/%s/pulls".formatted(path(command.repoOwner()), path(command.repoName()))),
                headers(authorizationHeader),
                json(body)
        );
    }

    private String authorizationHeader() {
        if (properties.getAuthMode() == GitHubCodePlatformProperties.AuthMode.PAT_LOCAL_SMOKE) {
            return "Bearer " + properties.getPatToken();
        }
        return "Bearer " + installationAccessToken();
    }

    private String installationAccessToken() {
        GitHubHttpRequest request = new GitHubHttpRequest(
                "POST",
                apiUrl("/app/installations/%s/access_tokens".formatted(path(properties.getInstallationId()))),
                headers("Bearer " + githubAppJwt()),
                "{}"
        );
        GitHubHttpResponse response = sender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub App installation token request failed with status " + response.statusCode());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("GitHub App installation token response did not include token");
            }
            return token;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse GitHub App installation token response", exception);
        }
    }

    private String githubAppJwt() {
        try {
            String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
            long now = Instant.now().getEpochSecond();
            String payload = base64Url("""
                    {"iat":%d,"exp":%d,"iss":"%s"}
                    """.formatted(now - 60L, now + 540L, properties.getAppId()).strip());
            String signingInput = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(readPrivateKey(properties.getPrivateKeyRef()));
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + BASE64_URL.encodeToString(signature.sign());
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("failed to create GitHub App JWT from privateKeyRef", exception);
        }
    }

    private PrivateKey readPrivateKey(String privateKeyRef) throws IOException, GeneralSecurityException {
        String pem;
        if (privateKeyRef.startsWith("env:")) {
            pem = System.getenv(privateKeyRef.substring("env:".length()));
        } else if (privateKeyRef.startsWith("file:")) {
            pem = Files.readString(Path.of(URI.create(privateKeyRef)), StandardCharsets.UTF_8);
        } else if (Path.of(privateKeyRef).isAbsolute()) {
            pem = Files.readString(Path.of(privateKeyRef), StandardCharsets.UTF_8);
        } else {
            throw new IllegalStateException("GitHub App privateKeyRef must use env:, file:, or an absolute file path");
        }
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("GitHub App privateKeyRef resolved to empty private key");
        }
        byte[] privateKeyDer;
        if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            privateKeyDer = wrapPkcs1PrivateKey(pemContent(pem, "RSA PRIVATE KEY"));
        } else if (pem.contains("-----BEGIN PRIVATE KEY-----")) {
            privateKeyDer = pemContent(pem, "PRIVATE KEY");
        } else {
            throw new IllegalStateException("GitHub App privateKeyRef resolved to unsupported private key format");
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
    }

    private PullRequestResult toPullRequestResult(CreatePullRequestCommand command, String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            String url = root.path("html_url").asText("");
            String number = root.path("number").asText("");
            String headSha = root.path("head").path("sha").asText("");
            return new PullRequestResult(
                    url,
                    number,
                    Map.of(
                            "provider", "github",
                            "repository", command.repoOwner() + "/" + command.repoName(),
                            "headSha", headSha
                    )
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse GitHub create pull request response", exception);
        }
    }

    private Map<String, String> headers(String authorizationHeader) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("Authorization", authorizationHeader);
        headers.put("Content-Type", "application/json");
        headers.put("X-GitHub-Api-Version", API_VERSION);
        return Map.copyOf(headers);
    }

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize GitHub request body", exception);
        }
    }

    private String apiUrl(String path) {
        return properties.getApiBaseUrl().replaceAll("/+$", "") + path;
    }

    private static String path(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String base64Url(String value) {
        return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * GitHub HTTP sender abstraction for network-free tests.
     */
    @FunctionalInterface
    public interface GitHubRequestSender {

        /**
         * Sends a GitHub REST request.
         *
         * @param request request to send
         * @return HTTP response
         */
        GitHubHttpResponse send(GitHubHttpRequest request);
    }

    /**
     * GitHub REST request value.
     *
     * @param method  HTTP method
     * @param url     absolute URL
     * @param headers request headers
     * @param body    JSON request body
     */
    public record GitHubHttpRequest(
            String method,
            String url,
            Map<String, String> headers,
            String body
    ) {

        public GitHubHttpRequest {
            method = normalize(method);
            url = normalize(url);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = normalize(body);
        }

        @Override
        public String toString() {
            return "GitHubHttpRequest[method=%s, url=%s, headers=%s, body=%s]"
                    .formatted(method, url, redactedHeaders(headers), body);
        }
    }

    /**
     * GitHub REST response value.
     *
     * @param statusCode HTTP status code
     * @param body       response body
     */
    public record GitHubHttpResponse(int statusCode, String body) {

        public GitHubHttpResponse {
            body = normalize(body);
        }
    }

    private static final class JavaHttpGitHubRequestSender implements GitHubRequestSender {

        private final HttpClient httpClient = HttpClient.newHttpClient();

        @Override
        public GitHubHttpResponse send(GitHubHttpRequest request) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                        .method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()));
                request.headers().forEach(builder::header);
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                return new GitHubHttpResponse(response.statusCode(), response.body());
            } catch (IOException exception) {
                throw new IllegalStateException("GitHub HTTP request failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GitHub HTTP request interrupted", exception);
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static Map<String, String> redactedHeaders(Map<String, String> headers) {
        Map<String, String> redacted = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if ("authorization".equalsIgnoreCase(key)) {
                redacted.put(key, "<redacted>");
            } else {
                redacted.put(key, value);
            }
        });
        return Map.copyOf(redacted);
    }

    private static byte[] pemContent(String pem, String type) throws GeneralSecurityException {
        String content = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("invalid GitHub App private key PEM content", exception);
        }
    }

    private static byte[] wrapPkcs1PrivateKey(byte[] pkcs1PrivateKey) {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] rsaAlgorithmIdentifier = new byte[]{
                0x30, 0x0d,
                0x06, 0x09,
                0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] privateKeyOctetString = derValue(0x04, pkcs1PrivateKey);
        return derValue(0x30, concat(version, rsaAlgorithmIdentifier, privateKeyOctetString));
    }

    private static byte[] derValue(int tag, byte[] body) {
        byte[] length = derLength(body.length);
        byte[] result = new byte[1 + length.length + body.length];
        result[0] = (byte) tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(body, 0, result, 1 + length.length, body.length);
        return result;
    }

    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[]{(byte) length};
        }
        int bytes = 0;
        int value = length;
        while (value > 0) {
            bytes++;
            value >>= 8;
        }
        byte[] encoded = new byte[1 + bytes];
        encoded[0] = (byte) (0x80 | bytes);
        for (int index = bytes; index > 0; index--) {
            encoded[index] = (byte) (length & 0xff);
            length >>= 8;
        }
        return encoded;
    }

    private static byte[] concat(byte[]... entries) {
        int length = 0;
        for (byte[] entry : entries) {
            length += entry.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] entry : entries) {
            System.arraycopy(entry, 0, result, offset, entry.length);
            offset += entry.length;
        }
        return result;
    }
}
