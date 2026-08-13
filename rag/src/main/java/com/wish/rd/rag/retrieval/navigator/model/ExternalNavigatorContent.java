package com.wish.rd.rag.retrieval.navigator.model;

import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;

/**
 * 一次 L2 正文读取。远端合同只接受 {@code uri}；{@code offset}/{@code limit}
 * 在本地切片，禁止发明未观测的查询参数。HTTP 404 是确定缺席，
 * {@code failureClass=NONE}。
 *
 * @param resourceUri  请求的资源 URI
 * @param content      切片后的正文，缺失时为空串
 * @param offset       本地切片起点
 * @param limit        本地切片长度；{@code <=0} 表示从 offset 读到末尾
 * @param exists       资源当前是否存在
 * @param failureClass 本次查询本身的分类；404 为 {@link ExternalIndexFailureClass#NONE}
 * @param errorCode    错误码
 * @param errorMessage 已脱敏的错误说明
 */
public record ExternalNavigatorContent(
        String resourceUri,
        String content,
        int offset,
        int limit,
        boolean exists,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage
) {

    public ExternalNavigatorContent {
        resourceUri = resourceUri == null ? "" : resourceUri;
        content = content == null ? "" : content;
        failureClass = failureClass == null ? ExternalIndexFailureClass.NONE : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 构造一次读到正文的结果。
     *
     * @param resourceUri 资源 URI
     * @param content     切片后正文
     * @param offset      切片起点
     * @param limit       切片长度
     * @return 成功结果
     */
    public static ExternalNavigatorContent of(String resourceUri, String content, int offset, int limit) {
        return new ExternalNavigatorContent(
                resourceUri, content, offset, limit, true, ExternalIndexFailureClass.NONE, "", "");
    }

    /**
     * 构造一次确定缺席的结果。
     *
     * @param resourceUri 资源 URI
     * @param offset      切片起点
     * @param limit       切片长度
     * @return 缺席结果
     */
    public static ExternalNavigatorContent absent(String resourceUri, int offset, int limit) {
        return new ExternalNavigatorContent(
                resourceUri, "", offset, limit, false, ExternalIndexFailureClass.NONE, "", "");
    }

    /**
     * 构造一次调用失败。
     *
     * @param resourceUri  资源 URI
     * @param offset       切片起点
     * @param limit        切片长度
     * @param failureClass 失败分类
     * @param errorCode    错误码
     * @param errorMessage 已脱敏说明
     * @return 失败结果
     */
    public static ExternalNavigatorContent failed(
            String resourceUri,
            int offset,
            int limit,
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalNavigatorContent(
                resourceUri, "", offset, limit, false, failureClass, errorCode, errorMessage);
    }
}
