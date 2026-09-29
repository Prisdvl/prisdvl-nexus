package com.lky.nexus.ai.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

/**
 * 模型引用：{@code provider:model} 形式的字符串唯一标识，例如 {@code deepseek:deepseek-chat}。
 *
 * <p><b>为什么用"字符串引用 + 逻辑角色"两层映射</b>（ADR-003）：
 * 业务代码只写角色名（{@code chat.agent}），角色到具体模型由 yaml 决定。
 * 换模型 = 改一行 yaml，业务代码零改动；换供应商 = 加一个 provider 块。
 *
 * <p><b>解析细节（坑）</b>：Ollama 的模型名自带冒号，例如 {@code qwen3:7b}。
 * 因此这里必须按<b>第一个</b>冒号切分：{@code ollama:qwen3:7b} → provider={@code ollama}，
 * model={@code qwen3:7b}。若用 {@code split(":")} 取两段就会把模型名截断成 {@code qwen3}，
 * 报错时会显示"模型不存在"，但真实原因是解析错了。
 */
public record ModelRef(String provider, String model) {

    private static final String SEPARATOR = ":";

    public ModelRef {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "非法的模型引用（应为 provider:model）：" + provider + SEPARATOR + model);
        }
    }

    /** 解析 {@code provider:model}；格式非法时抛出 4006（配置问题，不是用户输入问题）。 */
    public static ModelRef parse(String rawReference) {
        if (rawReference == null || rawReference.isBlank()) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED, "模型引用不能为空");
        }
        int separatorIndex = rawReference.indexOf(SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == rawReference.length() - 1) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "模型引用格式非法（应为 provider:model）：" + rawReference);
        }
        return new ModelRef(
                rawReference.substring(0, separatorIndex),
                rawReference.substring(separatorIndex + 1));
    }

    /** 规范化字符串，用于注册表 key 与日志。 */
    public String value() {
        return provider + SEPARATOR + model;
    }

    @Override
    public String toString() {
        return value();
    }
}
