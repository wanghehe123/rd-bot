package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * WP-0 合同测试用的最小 OpenViking HTTP 客户端。日志与异常不得包含 API key。
 */
final class OpenVikingContractHttp {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient client;
    private final Duration requestTimeout;

    OpenVikingContractHttp(String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout)
                .build();
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        return exchange("GET", path, Map.of(), null, null);
    }

    JsonNode get(String path, Map<String, String> query) throws IOException, InterruptedException {
        return exchange("GET", path, query, null, null);
    }

    JsonNode delete(String path, Map<String, String> query) throws IOException, InterruptedException {
        return exchange("DELETE", path, query, null, null);
    }

    JsonNode postJson(String path, Object body) throws IOException, InterruptedException {
        return exchange("POST", path, Map.of(), "application/json", OBJECT_MAPPER.writeValueAsBytes(body));
    }

    JsonNode uploadMarkdown(String fileName, byte[] markdown) throws IOException, InterruptedException {
        String boundary = "----RdBotOpenViking" + Long.toUnsignedString(System.nanoTime(), 36);
        byte[] header = (
                "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: text/markdown\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[header.length + markdown.length + footer.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(markdown, 0, body, header.length, markdown.length);
        System.arraycopy(footer, 0, body, header.length + markdown.length, footer.length);
        return exchange(
                "POST",
                "/api/v1/resources/temp_upload",
                Map.of(),
                "multipart/form-data; boundary=" + boundary,
                body
        );
    }

    int statusOfLast;
    String rawOfLast = "";

    private JsonNode exchange(
            String method,
            String path,
            Map<String, String> query,
            String contentType,
            byte[] body
    ) throws IOException, InterruptedException {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : query.entrySet()) {
            joiner.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }
        String uri = baseUrl + path;
        if (joiner.length() > 0) {
            uri += "?" + joiner;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .timeout(requestTimeout);
        if (!apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        statusOfLast = response.statusCode();
        String responseBody = response.body() == null ? "" : response.body();
        rawOfLast = redactSecrets(responseBody);
        if (responseBody.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        return OBJECT_MAPPER.readTree(responseBody);
    }

    static Map<String, String> query(String... pairs) {
        LinkedHashMap<String, String> mapped = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            mapped.put(pairs[index], pairs[index + 1]);
        }
        return mapped;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String redactSecrets(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return body
                .replaceAll("(\"user_key\"\\s*:\\s*\")[^\"]*\"", "$1REDACTED\"")
                .replaceAll("(\"admin_key\"\\s*:\\s*\")[^\"]*\"", "$1REDACTED\"")
                .replaceAll("(\"api_key\"\\s*:\\s*\")[^\"]*\"", "$1REDACTED\"");
    }
}
