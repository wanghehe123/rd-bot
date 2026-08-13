package com.wish.rd.bootstrap.openviking.impl;

import com.wish.rd.bootstrap.openviking.OpenVikingErrorTranslator;
import com.wish.rd.bootstrap.openviking.OpenVikingHttpExchange;
import com.wish.rd.bootstrap.openviking.model.OpenVikingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * 基于 JDK HttpClient 的 OpenViking 传输层。
 *
 * <p>失败分两类：连接根本没建立起来（{@link ConnectException}、连接超时）算"没发出去"，
 * 其余算"可能已发出"。这个区分决定了上层是可以重投还是只能查询收敛，
 * 因此宁可保守地把不确定的情况归为已发出。
 *
 * <p>API key 只出现在请求头里；日志与异常文本一律走
 * {@link OpenVikingErrorTranslator#safeMessage(String)}。
 */
public final class JdkOpenVikingHttpExchange implements OpenVikingHttpExchange {

    private static final Logger log = LoggerFactory.getLogger(JdkOpenVikingHttpExchange.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final Supplier<String> apiKeySupplier;
    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkOpenVikingHttpExchange(
            String baseUrl,
            Supplier<String> apiKeySupplier,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKeySupplier = apiKeySupplier;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public OpenVikingResponse get(String path, Map<String, String> query) {
        return exchange("GET", path, query, null, null);
    }

    @Override
    public OpenVikingResponse postJson(String path, Object body) {
        try {
            return exchange("POST", path, Map.of(), "application/json", OBJECT_MAPPER.writeValueAsBytes(body));
        } catch (IOException ex) {
            return OpenVikingResponse.transportFailed(ex, false);
        }
    }

    @Override
    public OpenVikingResponse uploadMarkdown(String fileName, byte[] content) {
        String boundary = "----RdBotOpenViking" + Long.toUnsignedString(System.nanoTime(), 36);
        byte[] header = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[header.length + content.length + footer.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(content, 0, body, header.length, content.length);
        System.arraycopy(footer, 0, body, header.length + content.length, footer.length);
        return exchange("POST", "/api/v1/resources/temp_upload", Map.of(),
                "multipart/form-data; boundary=" + boundary, body);
    }

    private OpenVikingResponse exchange(
            String method,
            String path,
            Map<String, String> query,
            String contentType,
            byte[] body
    ) {
        HttpRequest request;
        try {
            request = buildRequest(method, path, query, contentType, body);
        } catch (RuntimeException ex) {
            return OpenVikingResponse.transportFailed(ex, false);
        }
        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = response.body() == null ? "" : response.body();
            JsonNode parsed = raw.isBlank() ? OBJECT_MAPPER.createObjectNode() : OBJECT_MAPPER.readTree(raw);
            return OpenVikingResponse.of(response.statusCode(), parsed);
        } catch (IOException ex) {
            boolean issued = !(ex instanceof ConnectException || ex instanceof HttpConnectTimeoutException);
            log.warn("openviking {} {} failed: {}", method, path,
                    OpenVikingErrorTranslator.safeMessage(ex.toString()));
            return OpenVikingResponse.transportFailed(ex, issued);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return OpenVikingResponse.transportFailed(ex, true);
        }
    }

    private HttpRequest buildRequest(
            String method,
            String path,
            Map<String, String> query,
            String contentType,
            byte[] body
    ) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : query.entrySet()) {
            joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        String uri = joiner.length() > 0 ? baseUrl + path + "?" + joiner : baseUrl + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri)).timeout(requestTimeout);
        String apiKey = apiKeySupplier.get();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        return builder.build();
    }
}
