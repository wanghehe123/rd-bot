package com.wish.rd.exec.repair;

/**
 * 可复用修复资产类型。
 *
 * <p>修 bug 的资产中心是原因、策略和过程；脚本只是其中一种可复用资产。
 */
public enum RepairAssetType {
    BUG_CAUSE,
    FIX_STRATEGY,
    REPAIR_PROCESS,
    REPRO_STEPS,
    ACCEPTANCE_PLAN,
    ACCEPTANCE_SCRIPT,
    VALIDATION_RESULT,
    API_SEMANTIC_HINT,
    PRODUCT_SPEC,
    TECHNICAL_DESIGN,
    QA_REPORT,
    REQUIREMENT_REVIEW,
    LESSON_LEARNED,
    DELIVERY_REPORT,
    OTHER
}
