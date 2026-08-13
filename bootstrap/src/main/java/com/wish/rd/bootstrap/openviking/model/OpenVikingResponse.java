package com.wish.rd.bootstrap.openviking.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * 一次 OpenViking 调用的结果。
 *
 * <p>{@code requestIssued} 是整套幂等设计的地基：请求字节已经交给连接之后失败，
 * 远端可能已经执行了写入，调用方不得当作"没发生过"。
 *
 * @param status         HTTP 状态码，传输层失败时为 0
 * @param body           已解析的响应体
 * @param transportFailure 传输层异常，成功时为 {@code null}
 * @param requestIssued  请求字节是否已经写出
 */
public record OpenVikingResponse(
        int status,
        JsonNode body,
        Throwable transportFailure,
        boolean requestIssued
) {

    public OpenVikingResponse {
        body = body == null ? MissingNode.getInstance() : body;
    }

    public static OpenVikingResponse of(int status, JsonNode body) {
        return new OpenVikingResponse(status, body, null, true);
    }

    public static OpenVikingResponse transportFailed(Throwable failure, boolean requestIssued) {
        return new OpenVikingResponse(0, MissingNode.getInstance(), failure, requestIssued);
    }

    public boolean transportFailed() {
        return transportFailure != null;
    }

    public boolean successful() {
        return !transportFailed() && status >= 200 && status < 300;
    }

    public JsonNode result() {
        return body.path("result");
    }
}
