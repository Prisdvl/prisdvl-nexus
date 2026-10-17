package com.lky.nexus.app.agent.model;

/** 步骤状态（P0）：一条轨迹是"做成了 / 没做成 / 被拒 / 被取消"。 */
public enum RunStepStatus {

    OK,

    FAILED,

    DENIED,

    CANCELLED
}
