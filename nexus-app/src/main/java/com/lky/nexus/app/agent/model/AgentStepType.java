package com.lky.nexus.app.agent.model;

/** 运行步骤类型（P0，§五）：THINK / TOOL_CALL / OBSERVATION / ANSWER。 */
public enum AgentStepType {

    /** 阶段标记，不是模型内部推理原文（链式思维不落库、不外发）。 */
    THINK,

    TOOL_CALL,

    OBSERVATION,

    ANSWER
}
