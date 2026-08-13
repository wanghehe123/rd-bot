package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;

/**
 * 一次远端目录列举。只用于对账发现孤儿/外来资源，不作为删除许可。
 *
 * @param entries      列举到的条目
 * @param failureClass 本次查询本身的分类
 * @param errorCode    错误码
 * @param errorMessage 已脱敏的错误说明
 */
public record ExternalTreeListing(
        List<Entry> entries,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage
) {

    public ExternalTreeListing {
        entries = entries == null ? List.of() : List.copyOf(entries);
        failureClass = failureClass == null ? ExternalIndexFailureClass.NONE : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ExternalTreeListing of(List<Entry> entries) {
        return new ExternalTreeListing(entries, ExternalIndexFailureClass.NONE, "", "");
    }

    public static ExternalTreeListing failed(
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalTreeListing(List.of(), failureClass, errorCode, errorMessage);
    }

    /**
     * 远端树的一条目。{@code owner} 来自条目自带的标签，缺失时为空串，
     * 对账引擎再用 {@code inspectResource} 补 ownership。
     *
     * @param uri       条目 URI
     * @param name      展示名（rel_path 或末段）
     * @param directory 是否为目录
     * @param owner     所有权标记，未知时为空
     */
    public record Entry(String uri, String name, boolean directory, String owner) {

        public Entry {
            uri = uri == null ? "" : uri;
            name = name == null ? "" : name;
            owner = owner == null ? "" : owner;
        }
    }
}
