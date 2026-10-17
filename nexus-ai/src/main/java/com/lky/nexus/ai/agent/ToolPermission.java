package com.lky.nexus.ai.agent;

import java.util.Locale;

/**
 * 工具权限分级（P0，§五）。
 *
 * <p>它回答的是一个与{@link ToolCategory}完全不同的问题：category 说"这个工具是干什么的"
 * （界面分组用），permission 说"执行它之前要不要用户点头"——后者是**执行拦截**的依据。
 * 两者都放在 {@code nexus-ai}，因为它们与厂商、与"从哪一行读出来"无关。
 *
 * <p>三档语义：
 * <ul>
 *   <li>{@link #READ} —— 直接执行（检索、计算这类无副作用操作）；</li>
 *   <li>{@link #WRITE} —— 会改变状态，执行前必须审批，除非用户已有 {@code always} 授权；</li>
 *   <li>{@link #DANGEROUS} —— 高风险操作，审批策略同上，但**不允许被降级绕过**：
 *       它的拦截判定与 WRITE 共用一条路径，任何一处按 {@code == WRITE} 特判都会漏掉它。</li>
 * </ul>
 *
 * <p><b>代码里的这个枚举只是默认值</b>：运行时权威配置在 {@code ai_tool_registry.permission}
 * （见 V22 文件头）。所以本类<b>没有</b> {@code parse} 之外的第二套判定逻辑——
 * {@code requiresApproval()} 只做"是不是 READ"这一件事，与权限从哪来无关。
 */
public enum ToolPermission {

    READ,

    WRITE,

    DANGEROUS;

    /** 该权限是否需要人工审批（READ 直接执行；WRITE / DANGEROUS 一律要先审批）。 */
    public boolean requiresApproval() {
        return this != READ;
    }

    /** 客户端的 {@code scope} 只会是 once / always / deny，不是这个；这里只用于库值解析。 */
    public static ToolPermission parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("工具权限不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ToolPermission value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的工具权限：" + raw + "（应为 READ / WRITE / DANGEROUS）");
    }
}
