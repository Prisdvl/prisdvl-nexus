package com.lky.nexus.app.agent.model;

/** 审批记录状态（P0，§六）。 */
public enum ApprovalStatus {

    PENDING,

    APPROVED,

    DENIED,

    /** 运行被取消：审批随之作废，且**不能**被当成批准。 */
    CANCELLED,

    /** 审批超时：同样不能执行；用户可以重新发起运行。 */
    EXPIRED
}
