package com.wish.rd.rag.retrieval.navigator.model;

import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;

/**
 * 一次 L1 overview 读取。HTTP 404 与 {@code fs/attrs} 同义：资源确定不存在，
 * {@code failureClass=NONE}，不是调用失败。
 *
 * @param resourceUri  请求的资源 URI
 * @param overview     overview 文本，缺失时为空串
 * @param exists       资源当前是否存在
 * @param failureClass 本次查询本身的分类；404 为 {@link ExternalIndexFailureClass#NONE}
 * @param errorCode    错误码
 * @param errorMessage 已脱敏的错误说明
 */
public record ExternalNavigatorDocument(
        String resourceUri,
        String overview,
        boolean exists,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage
) {

    public ExternalNavigatorDocument {
        resourceUri = resourceUri == null ? "" : resourceUri;
        overview = overview == null ? "" : overview;
        failureClass = failureClass == null ? ExternalIndexFailureClass.NONE : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 构造一次读到 overview 的结果。
     *
     * @param resourceUri 资源 URI
     * @param overview    文本
     * @return 成功结果
     */
    public static ExternalNavigatorDocument of(String resourceUri, String overview) {
        return new ExternalNavigatorDocument(
                resourceUri, overview, true, ExternalIndexFailureClass.NONE, "", "");
    }

    /**
     * 构造一次确定缺席的结果。
     *
     * @param resourceUri 资源 URI
     * @return 缺席结果
     */
    public static ExternalNavigatorDocument absent(String resourceUri) {
        return new ExternalNavigatorDocument(
                resourceUri, "", false, ExternalIndexFailureClass.NONE, "", "");
    }

    /**
     * 构造一次调用失败。
     *
     * @param resourceUri  资源 URI
     * @param failureClass 失败分类
     * @param errorCode    错误码
     * @param errorMessage 已脱敏说明
     * @return 失败结果
     */
    public static ExternalNavigatorDocument failed(
            String resourceUri,
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalNavigatorDocument(resourceUri, "", false, failureClass, errorCode, errorMessage);
    }
}
