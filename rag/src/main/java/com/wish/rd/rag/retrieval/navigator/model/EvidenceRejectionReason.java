package com.wish.rd.rag.retrieval.navigator.model;

/**
 * 证据 allowlist 拒绝原因。顺序与判定步骤一致，一条命中只记最先失败的那一个。
 */
public enum EvidenceRejectionReason {

    /** 命中 URI 在 OPENVIKING 绑定中没有路径前缀。 */
    NO_BINDING,

    /** 绑定不是 IN_SYNC，或 observed_version 与 desired_version 不相等。 */
    VERSION_NOT_VERIFIED,

    /** 文档未启用、已软删除、已被 supersede，或绑定指向的文档不存在。 */
    DOCUMENT_NOT_ACTIVE,

    /** 文档知识库不在本次检索 scope 内。 */
    OUT_OF_SCOPE
}
