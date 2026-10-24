package com.lky.nexus.app.automation.model;

/**
 * 一次自动化运行的状态。
 *
 * <p>只有三态，且 RUNNING 是唯一的非终态：调度是"一次跑 = 一条记录"的同步执行，
 * 没有排队环节，所以不需要 QUEUED。前端据此渲染"成功 / 失败 / 运行中"三种图标。
 */
public enum AutomationRunStatus {

    /** 已落库、正在等 Agent 回答。 */
    RUNNING,
    /** Agent 正常返回。 */
    SUCCEEDED,
    /** 调用失败：必带 errorCode + errorMessage 落库（不是只写日志）。 */
    FAILED;

    /** 是否终态（终态记录不再被改写）。 */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
