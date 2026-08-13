package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;

/**
 * 把 OpenViking 的 HTTP 状态与错误信封翻译成领域分类。
 *
 * <p>只允许使用 WP-0 真实采集到的字段（见
 * {@code bootstrap/src/test/resources/openviking/contracts/}）；不得按供应商文档补字段。
 */
public final class OpenVikingErrorTranslator {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private OpenVikingErrorTranslator() {
    }

    /**
     * 翻译传输层异常。区分"一定没发出去"与"可能已发出但拿不到结果"，
     * 后者禁止直接重放。
     *
     * @param failure       客户端异常
     * @param requestIssued 请求字节是否已经交给连接
     * @return 失败分类
     */
    public static ExternalIndexFailureClass classifyTransport(Throwable failure, boolean requestIssued) {
        if (requestIssued) {
            return ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT;
        }
        return ExternalIndexFailureClass.RETRYABLE_NOT_SENT;
    }

    /**
     * 翻译一次已拿到 HTTP 状态的响应。
     *
     * <p>调用方若对 404 有自己的语义（任务保留期、删除后 stat），必须在调用本方法前处理。
     *
     * @param httpStatus HTTP 状态码
     * @param body       已解析的响应体，可为空节点
     * @return 失败分类；2xx 且信封合法时为 {@link ExternalIndexFailureClass#NONE}
     */
    public static ExternalIndexFailureClass classify(int httpStatus, JsonNode body) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return ExternalIndexFailureClass.NONE;
        }
        String errorCode = errorCode(body);
        if (httpStatus == 409) {
            JsonNode details = body == null ? null : body.path("error").path("details");
            boolean pathBusy = details != null && "path_busy".equals(details.path("conflict_type").asText());
            return pathBusy ? ExternalIndexFailureClass.RETRYABLE_BUSY : ExternalIndexFailureClass.CONTRACT_OR_DATA_ERROR;
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return ExternalIndexFailureClass.CONFIGURATION_BLOCKED;
        }
        if (httpStatus == 429) {
            return ExternalIndexFailureClass.RETRYABLE;
        }
        if (httpStatus >= 500) {
            return ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT;
        }
        if ("UNAUTHENTICATED".equals(errorCode) || "PERMISSION_DENIED".equals(errorCode)) {
            return ExternalIndexFailureClass.CONFIGURATION_BLOCKED;
        }
        return ExternalIndexFailureClass.CONTRACT_OR_DATA_ERROR;
    }

    /**
     * 读取错误码，缺失时返回空串。
     *
     * @param body 响应体
     * @return {@code error.code}
     */
    public static String errorCode(JsonNode body) {
        if (body == null) {
            return "";
        }
        return body.path("error").path("code").asText("");
    }

    /**
     * 生成可安全落库和展示的错误说明：脱敏 key 并截断。
     *
     * @param raw 原始文本
     * @return 已脱敏且有界的说明
     */
    public static String safeMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String redacted = raw
                .replaceAll("(\"?user_key\"?\\s*[:=]\\s*\"?)[^\",}\\s]+", "$1REDACTED")
                .replaceAll("(\"?admin_key\"?\\s*[:=]\\s*\"?)[^\",}\\s]+", "$1REDACTED")
                .replaceAll("(\"?api_key\"?\\s*[:=]\\s*\"?)[^\",}\\s]+", "$1REDACTED")
                .replaceAll("(?i)(X-API-Key\\s*[:=]\\s*)\\S+", "$1REDACTED");
        return redacted.length() <= MAX_MESSAGE_LENGTH
                ? redacted
                : redacted.substring(0, MAX_MESSAGE_LENGTH);
    }
}
