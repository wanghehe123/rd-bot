package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;

/**
 * 版本核验结果。任何一项未通过都不得把 {@code observed_version} 推到 desired。
 *
 * <p>检查项只能是确定性证据。相关性检索命中与否取决于语料规模、查询措辞和嵌入模型，
 * 不是版本正确性的判据，也证明不了版本：真机上打分最高的命中是派生的
 * {@code .abstract.md}，根本不是被核验的 L2 正文。检索健康度走旁路探针。
 *
 * @param verified     全部检查通过时为 true
 * @param failedChecks 未通过的检查名，用于诊断与管理页展示
 * @param failureClass 核验过程本身的分类；核验请求失败时非 {@code NONE}
 * @param errorCode    错误码
 * @param errorMessage 已脱敏的错误说明
 */
public record ExternalKnowledgeVerification(
        boolean verified,
        List<String> failedChecks,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage
) {

    public static final String CHECK_ROOT_URI = "root_uri";
    public static final String CHECK_OWNERSHIP = "ownership_marker";
    public static final String CHECK_SYNC_VERSION = "rd.sync_version";
    public static final String CHECK_CHECKSUM = "rd.checksum";
    public static final String CHECK_ABSTRACT = "l0_abstract";
    public static final String CHECK_OVERVIEW = "l1_overview";
    public static final String CHECK_CONTENT = "l2_content";

    public ExternalKnowledgeVerification {
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        failureClass = failureClass == null ? ExternalIndexFailureClass.NONE : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        if (verified && !failedChecks.isEmpty()) {
            throw new IllegalArgumentException("verified result must not carry failed checks");
        }
    }

    public static ExternalKnowledgeVerification passed() {
        return new ExternalKnowledgeVerification(true, List.of(), ExternalIndexFailureClass.NONE, "", "");
    }

    /**
     * 构造一次内容/marker 不符的核验失败。远端确实响应了，只是值不对。
     *
     * @param failedChecks 未通过的检查名
     * @return 核验失败结果
     */
    public static ExternalKnowledgeVerification mismatched(List<String> failedChecks) {
        return new ExternalKnowledgeVerification(
                false, failedChecks, ExternalIndexFailureClass.MALFORMED_SUCCESS, "VERSION_MARKER_MISMATCH", "");
    }

    /**
     * 构造一次核验请求本身失败的结果。
     *
     * @param failureClass 失败分类
     * @param errorCode    错误码
     * @param errorMessage 已脱敏的错误说明
     * @return 核验失败结果
     */
    public static ExternalKnowledgeVerification unavailable(
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalKnowledgeVerification(false, List.of(), failureClass, errorCode, errorMessage);
    }
}
