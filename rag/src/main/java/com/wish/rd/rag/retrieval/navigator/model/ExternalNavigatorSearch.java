package com.wish.rd.rag.retrieval.navigator.model;

import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;

import java.util.List;

/**
 * 一次 L0 摘要检索的结果。失败分类复用投影侧的
 * {@link ExternalIndexFailureClass}：读路径与写路径面对的是同一组已观测 HTTP 码，
 * 再造一套枚举只会让 404/401 的翻译分叉。
 *
 * @param hits         命中列表，失败时为空
 * @param failureClass 本次查询本身的分类
 * @param errorCode    错误码
 * @param errorMessage 已脱敏的错误说明
 */
public record ExternalNavigatorSearch(
        List<Hit> hits,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage
) {

    public ExternalNavigatorSearch {
        hits = hits == null ? List.of() : List.copyOf(hits);
        failureClass = failureClass == null ? ExternalIndexFailureClass.NONE : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 构造一次成功检索。
     *
     * @param hits 命中
     * @return 成功结果
     */
    public static ExternalNavigatorSearch of(List<Hit> hits) {
        return new ExternalNavigatorSearch(hits, ExternalIndexFailureClass.NONE, "", "");
    }

    /**
     * 构造一次失败检索。
     *
     * @param failureClass 失败分类
     * @param errorCode    错误码
     * @param errorMessage 已脱敏说明
     * @return 失败结果
     */
    public static ExternalNavigatorSearch failed(
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalNavigatorSearch(List.of(), failureClass, errorCode, errorMessage);
    }

    /**
     * L0 的一条命中。字段名对齐冻结合同 {@code find.json} 的 resources 元素，
     * {@code abstract} 在 Java 里不能做组件名，因此叫 {@code abstractText}。
     *
     * @param uri          资源 URI
     * @param level        合同里的 level
     * @param score        相关性分数
     * @param abstractText 摘要文本
     * @param tags         远端标签
     */
    public record Hit(
            String uri,
            int level,
            double score,
            String abstractText,
            List<String> tags
    ) {

        public Hit {
            uri = uri == null ? "" : uri;
            abstractText = abstractText == null ? "" : abstractText;
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}
