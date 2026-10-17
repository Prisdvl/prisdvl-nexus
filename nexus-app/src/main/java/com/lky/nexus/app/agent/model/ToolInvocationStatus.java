package com.lky.nexus.app.agent.model;

/** 工具调用审计状态（P0，§九）。 */
public enum ToolInvocationStatus {

    PENDING_APPROVAL,

    SUCCEEDED,

    FAILED,

    /** 用户拒绝（不是系统故障——所以与 FAILED 分开）。 */
    DENIED,

    CANCELLED
}
