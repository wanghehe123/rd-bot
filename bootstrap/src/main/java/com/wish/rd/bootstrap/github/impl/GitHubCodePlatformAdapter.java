package com.wish.rd.bootstrap.github.impl;

import com.wish.rd.bootstrap.github.GitHubCodePlatformProperties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.merge.model.PullRequestMergeStatus;
import com.wish.rd.engine.merge.PullRequestMergeStatusPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.BranchHeadResult;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.FindBranchHeadCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub REST code-platform adapter. HTTP is isolated behind {@link GitHubRequestSender}
 * so tests can validate request construction without network access.
 */
@Component
@ConditionalOnProperty(prefix = "rd.github.code-platform", name = "mode", havingValue = "real")
public class GitHubCodePlatformAdapter implements CodePlatformPort, PullRequestMergeStatusPort {

    private static final String API_VERSION = "2022-11-28";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[[^]]*]\\((https?://[^)\\s]+)\\)");
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[^\\s)]+");
    private static final Pattern OPERATION_MARKER_PATTERN =
            Pattern.compile("(?m)^rd-operation-id:\\s*(\\S+)\\s*$");
    private static final Pattern CANDIDATE_PATCH_MARKER_PATTERN =
            Pattern.compile("(?m)^rd-candidate-patch-sha256:\\s*(\\S+)\\s*$");

    private final GitHubCodePlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final GitHubRequestSender sender;
    private final GitHubCliRunner cliRunner;

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
        this(properties, objectMapper, new JavaHttpGitHubRequestSender(), new ProcessGitHubCliRunner());
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
        this(properties, objectMapper, sender, new ProcessGitHubCliRunner());
    }

    /**
     * 创建可注入 sender 与 gh CLI runner 的 GitHub 适配器，供单元测试避免真实网络和进程调用。
     *
     * @param properties   GitHub 代码平台配置
     * @param objectMapper JSON 解析器
     * @param sender       HTTP sender
     * @param cliRunner    gh CLI runner
     */
    public GitHubCodePlatformAdapter(
            GitHubCodePlatformProperties properties,
            ObjectMapper objectMapper,
            GitHubRequestSender sender,
            GitHubCliRunner cliRunner
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.cliRunner = Objects.requireNonNull(cliRunner, "cliRunner must not be null");
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

        if (properties.getAuthMode() == GitHubCodePlatformProperties.AuthMode.GH_CLI_LOCAL_SMOKE) {
            return createPullRequestWithGhCli(command);
        }
        GitHubHttpRequest request = createPullRequestHttpRequest(command, authorizationHeader());
        GitHubHttpResponse response = sender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub create pull request failed with status " + response.statusCode());
        }
        return toPullRequestResult(command, response.body());
    }

    @Override
    public PullRequestMergeStatus findByUrl(String pullRequestUrl) {
        ParsedPullRequestUrl parsed = parsePullRequestUrl(pullRequestUrl);
        if (!properties.isRepositoryAllowed(parsed.owner(), parsed.repo())) {
            throw new IllegalArgumentException("repository is not allowlisted: " + parsed.repository());
        }
        properties.validateForRealAdapter();

        if (properties.getAuthMode() == GitHubCodePlatformProperties.AuthMode.GH_CLI_LOCAL_SMOKE) {
            return findByUrlWithGhCli(pullRequestUrl, parsed);
        }
        GitHubHttpRequest request = new GitHubHttpRequest(
                "GET",
                apiUrl("/repos/%s/%s/pulls/%s".formatted(
                        path(parsed.owner()),
                        path(parsed.repo()),
                        path(parsed.number())
                )),
                headers(authorizationHeader()),
                ""
        );
        GitHubHttpResponse response = sender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub get pull request failed with status " + response.statusCode());
        }
        return toPullRequestMergeStatus(pullRequestUrl, parsed, response.body());
    }

    @Override
    public Optional<PullRequestResult> findOpenPullRequest(FindOpenPullRequestCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (!properties.isRepositoryAllowed(command.repoOwner(), command.repoName())) {
            throw new IllegalArgumentException(
                    "repository is not allowlisted: " + command.repoOwner() + "/" + command.repoName());
        }
        properties.validateForRealAdapter();
        if (properties.getAuthMode() == GitHubCodePlatformProperties.AuthMode.GH_CLI_LOCAL_SMOKE) {
            return findOpenPullRequestWithGhCli(command);
        }
        GitHubHttpRequest request = findOpenPullRequestHttpRequest(command, authorizationHeader());
        GitHubHttpResponse response = sender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "GitHub list open pull requests failed with status " + response.statusCode());
        }
        return toOpenPullRequestResult(command, response.body());
    }

    @Override
    public Optional<BranchHeadResult> findBranchHead(FindBranchHeadCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (!properties.isRepositoryAllowed(command.repoOwner(), command.repoName())) {
            throw new IllegalArgumentException(
                    "repository is not allowlisted: " + command.repoOwner() + "/" + command.repoName());
        }
        properties.validateForRealAdapter();
        if (properties.getAuthMode() == GitHubCodePlatformProperties.AuthMode.GH_CLI_LOCAL_SMOKE) {
            return findBranchHeadWithGhCli(command);
        }
        GitHubHttpRequest request = findBranchHeadHttpRequest(command, authorizationHeader());
        GitHubHttpResponse response = sender.send(request);
        if (isConfirmedMissingGitRef(response.statusCode(), response.body())) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "GitHub get branch head failed with status " + response.statusCode());
        }
        return toBranchHeadResult(command, response.body());
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

    private PullRequestResult createPullRequestWithGhCli(CreatePullRequestCommand command) {
        GitHubCliResult result = cliRunner.run(List.of(
                properties.getGhCliCommand(),
                "api",
                "repos/%s/%s/pulls".formatted(command.repoOwner(), command.repoName()),
                "--method",
                "POST",
                "-f",
                "title=" + command.title(),
                "-f",
                "head=" + command.workBranch(),
                "-f",
                "base=" + command.baseBranch(),
                "-f",
                "body=" + command.prBody()
        ));
        ensureSuccessfulCliResult("create pull request", result);
        return toPullRequestResult(command, result.stdout());
    }

    private PullRequestMergeStatus findByUrlWithGhCli(String pullRequestUrl, ParsedPullRequestUrl parsed) {
        GitHubCliResult result = cliRunner.run(List.of(
                properties.getGhCliCommand(),
                "api",
                "repos/%s/%s/pulls/%s".formatted(parsed.owner(), parsed.repo(), parsed.number())
        ));
        ensureSuccessfulCliResult("get pull request", result);
        return toPullRequestMergeStatus(pullRequestUrl, parsed, result.stdout());
    }

    private static void ensureSuccessfulCliResult(String action, GitHubCliResult result) {
        if (result.exitCode() == 0) {
            return;
        }
        String stderr = result.stderr().isBlank() ? "" : ": " + result.stderr();
        throw new IllegalStateException("GitHub gh api " + action + " failed with exit code "
                + result.exitCode() + stderr);
    }

    /**
     * GitHub GET /commits/{ref} returns 404 or 422 "No commit found for SHA"
     * when the branch does not exist yet. That is confirmed absence, not an
     * unknown remote result.
     */
    private static boolean isConfirmedMissingGitRef(int statusCode, String body) {
        if (statusCode == 404) {
            return true;
        }
        if (statusCode != 422) {
            return false;
        }
        String haystack = body == null ? "" : body.toLowerCase();
        return haystack.contains("no commit found") || haystack.isBlank();
    }

    private static boolean isConfirmedMissingGitRefCli(GitHubCliResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr().toLowerCase();
        String stdout = result.stdout() == null ? "" : result.stdout().toLowerCase();
        String haystack = stderr + " " + stdout;
        return haystack.contains("404")
                || haystack.contains("422")
                || haystack.contains("not found")
                || haystack.contains("no commit found");
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

    private GitHubHttpRequest findOpenPullRequestHttpRequest(
            FindOpenPullRequestCommand command,
            String authorizationHeader
    ) {
        String head = command.repoOwner() + ":" + command.workBranch();
        String query = "state=open"
                + "&head=" + URLEncoder.encode(head, StandardCharsets.UTF_8)
                + "&base=" + URLEncoder.encode(command.baseBranch(), StandardCharsets.UTF_8)
                + "&per_page=5";
        return new GitHubHttpRequest(
                "GET",
                apiUrl("/repos/%s/%s/pulls?%s".formatted(
                        path(command.repoOwner()),
                        path(command.repoName()),
                        query
                )),
                headers(authorizationHeader),
                ""
        );
    }

    private Optional<PullRequestResult> findOpenPullRequestWithGhCli(FindOpenPullRequestCommand command) {
        String head = command.repoOwner() + ":" + command.workBranch();
        GitHubCliResult result = cliRunner.run(List.of(
                properties.getGhCliCommand(),
                "api",
                "repos/%s/%s/pulls?state=open&head=%s&base=%s&per_page=5".formatted(
                        command.repoOwner(),
                        command.repoName(),
                        head,
                        command.baseBranch()
                ),
                "--method",
                "GET"
        ));
        ensureSuccessfulCliResult("list open pulls", result);
        return toOpenPullRequestResult(command, result.stdout());
    }

    private GitHubHttpRequest findBranchHeadHttpRequest(
            FindBranchHeadCommand command,
            String authorizationHeader
    ) {
        // The commit endpoint supplies both the branch tip SHA and its message markers.
        // path() percent-encodes slashes so feature/x becomes feature%2Fx in the URL path.
        return new GitHubHttpRequest(
                "GET",
                apiUrl("/repos/%s/%s/commits/%s".formatted(
                        path(command.repoOwner()),
                        path(command.repoName()),
                        path(command.branch())
                )),
                headers(authorizationHeader),
                ""
        );
    }

    private Optional<BranchHeadResult> findBranchHeadWithGhCli(FindBranchHeadCommand command) {
        String encodedBranch = path(command.branch());
        GitHubCliResult result = cliRunner.run(List.of(
                properties.getGhCliCommand(),
                "api",
                "repos/%s/%s/commits/%s".formatted(
                        command.repoOwner(),
                        command.repoName(),
                        encodedBranch
                ),
                "--method",
                "GET"
        ));
        if (result.exitCode() != 0) {
            if (isConfirmedMissingGitRefCli(result)) {
                return Optional.empty();
            }
            ensureSuccessfulCliResult("get branch head", result);
        }
        return toBranchHeadResult(command, result.stdout());
    }

    private Optional<BranchHeadResult> toBranchHeadResult(FindBranchHeadCommand command, String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            String sha = root.path("sha").asText("");
            if (sha.isBlank()) {
                // Keep parsing old /git/ref responses while a provider rollout is in progress.
                sha = root.path("object").path("sha").asText("");
            }
            if (sha.isBlank()) {
                return Optional.empty();
            }
            String commitMessage = root.path("commit").path("message").asText("");
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("provider", "github");
            metadata.put("repository", command.repoOwner() + "/" + command.repoName());
            metadata.put("branch", command.branch());
            metadata.put("ref", root.path("ref").asText("refs/heads/" + command.branch()));
            metadata.put("source", "branch-commit-lookup");
            metadata.put("commitMessage", commitMessage);
            metadata.put("operationId", commitMarker(commitMessage, OPERATION_MARKER_PATTERN));
            metadata.put("candidatePatchSha256", commitMarker(commitMessage, CANDIDATE_PATCH_MARKER_PATTERN));
            return Optional.of(new BranchHeadResult(
                    sha,
                    metadata
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse GitHub branch head response", exception);
        }
    }

    private static String commitMarker(String commitMessage, Pattern pattern) {
        String message = commitMessage == null ? "" : commitMessage;
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private Optional<PullRequestResult> toOpenPullRequestResult(FindOpenPullRequestCommand command, String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null || body.isBlank() ? "[]" : body);
            if (!root.isArray() || root.isEmpty()) {
                return Optional.empty();
            }
            JsonNode first = root.get(0);
            String url = first.path("html_url").asText("");
            String number = first.path("number").asText("");
            if (url.isBlank() || number.isBlank()) {
                return Optional.empty();
            }
            String headSha = first.path("head").path("sha").asText("");
            String prBody = first.path("body").asText("");
            return Optional.of(new PullRequestResult(
                    url,
                    number,
                    Map.of(
                            "provider", "github",
                            "repository", command.repoOwner() + "/" + command.repoName(),
                            "headSha", headSha,
                            "body", prBody,
                            "source", "open-pull-lookup"
                    )
                ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse GitHub open pull request list response", exception);
        }
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

    private PullRequestMergeStatus toPullRequestMergeStatus(
            String pullRequestUrl,
            ParsedPullRequestUrl parsed,
            String body
    ) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            return new PullRequestMergeStatus(
                    root.path("html_url").asText(pullRequestUrl),
                    parsed.repository(),
                    parsed.number(),
                    root.path("state").asText(""),
                    root.path("merged").asBoolean(false)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse GitHub pull request status response", exception);
        }
    }

    private static ParsedPullRequestUrl parsePullRequestUrl(String pullRequestUrl) {
        String normalized = extractPullRequestUrl(pullRequestUrl);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("pullRequestUrl must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("pullRequestUrl must be an http(s) URL matching /{owner}/{repo}/pull/{number}", exception);
        }
        String[] rawParts = normalize(uri.getPath()).split("/");
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (String rawPart : rawParts) {
            if (!rawPart.isBlank()) {
                parts.add(rawPart);
            }
        }
        if (parts.size() != 4 || !"pull".equals(parts.get(2)) || !parts.get(3).matches("\\d+")) {
            throw new IllegalArgumentException("pullRequestUrl must match /{owner}/{repo}/pull/{number}");
        }
        return new ParsedPullRequestUrl(parts.get(0), parts.get(1), parts.get(3));
    }

    private static String extractPullRequestUrl(String pullRequestUrl) {
        String normalized = normalize(pullRequestUrl);
        Matcher markdownLink = MARKDOWN_LINK_PATTERN.matcher(normalized);
        if (markdownLink.find()) {
            return normalize(markdownLink.group(1));
        }
        Matcher httpUrl = HTTP_URL_PATTERN.matcher(normalized);
        if (httpUrl.find()) {
            return normalize(httpUrl.group());
        }
        return normalized;
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

    /**
     * gh CLI runner abstraction for token-free local smoke execution.
     */
    @FunctionalInterface
    public interface GitHubCliRunner {

        /**
         * Runs a gh CLI command without shell interpolation.
         *
         * @param command command argv
         * @return CLI result
         */
        GitHubCliResult run(List<String> command);
    }

    /**
     * gh CLI process result.
     *
     * @param exitCode process exit code
     * @param stdout   standard output
     * @param stderr   standard error
     */
    public record GitHubCliResult(int exitCode, String stdout, String stderr) {

        public GitHubCliResult {
            stdout = normalize(stdout);
            stderr = normalize(stderr);
        }
    }

    private static final class JavaHttpGitHubRequestSender implements GitHubRequestSender {

        private final HttpClient httpClient = HttpClient.newHttpClient();

        @Override
        public GitHubHttpResponse send(GitHubHttpRequest request) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()));
                if ("GET".equalsIgnoreCase(request.method()) && request.body().isBlank()) {
                    builder.GET();
                } else {
                    builder.method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()));
                }
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

    private static final class ProcessGitHubCliRunner implements GitHubCliRunner {

        @Override
        public GitHubCliResult run(List<String> command) {
            try {
                Process process = new ProcessBuilder(command).start();
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                return new GitHubCliResult(exitCode, stdout, stderr);
            } catch (IOException exception) {
                throw new IllegalStateException("GitHub gh CLI command failed to start", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GitHub gh CLI command interrupted", exception);
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private record ParsedPullRequestUrl(String owner, String repo, String number) {

        private ParsedPullRequestUrl {
            owner = normalize(owner);
            repo = normalize(repo);
            number = normalize(number);
        }

        private String repository() {
            return owner + "/" + repo;
        }
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
