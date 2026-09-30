package com.lky.nexus.app.agent.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 试跑请求（§十八 Test Chat）。
 *
 * <p>只有一个字段：试跑是"用当前配置真跑一遍"，不该带任何能改变 Agent 配置的参数——
 * 否则用户看到的结果就不是"这个 Agent 的表现"了。
 */
public record TestAgentRequest(

        @NotBlank(message = "问题不能为空")
        String question) {
}
