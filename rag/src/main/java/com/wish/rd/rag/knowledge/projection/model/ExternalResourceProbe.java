package com.wish.rd.rag.knowledge.projection.model;

import java.util.Map;

/**
 * 远端资源的一次只读探测。404 是"不存在"，不是调用失败。
 *
 * @param exists       资源当前是否存在
 * @param tags         ownership/version 标签，缺失时为空表
 * @param failureClass 本次查询本身的分类；404 为 {@link ExternalIndexFailureClass#NONE}
 * @param errorCode    错误码
 * @param errorMessage 已脱敏的错误说明
 */
public record ExternalResourceProbe(
        boolean exists,
        Map<String, String> tags,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage
) {

    public ExternalResourceProbe {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        failureClass = failureClass == null ? ExternalIndexFailureClass.NONE : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ExternalResourceProbe present(Map<String, String> tags) {
        return new ExternalResourceProbe(true, tags, ExternalIndexFailureClass.NONE, "", "");
    }

    public static ExternalResourceProbe absent() {
        return new ExternalResourceProbe(false, Map.of(), ExternalIndexFailureClass.NONE, "", "");
    }

    public static ExternalResourceProbe unavailable(
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalResourceProbe(false, Map.of(), failureClass, errorCode, errorMessage);
    }
}
