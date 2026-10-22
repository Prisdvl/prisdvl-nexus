package com.lky.nexus.app.workflow.model.dto;

import java.util.List;

/**
 * 内置工作流模板的清单项（不含图，图在实例化时才写进用户的草稿）。
 *
 * @param category 分类 code：{@code qa} / {@code content} / {@code ops} / {@code review}，
 *                 前端按它分组展示（分类名是文案，由前端字典翻译，接口只给 code）
 * @param tags     一句话看点，前端在卡片上以 chip 展示
 */
public record WorkflowPresetResponse(
        String code,
        String name,
        String category,
        String description,
        List<String> tags,
        int nodeCount
) {
}
