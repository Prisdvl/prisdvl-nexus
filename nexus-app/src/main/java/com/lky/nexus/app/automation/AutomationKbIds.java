package com.lky.nexus.app.automation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code automation.kb_ids} 这一列的编解码。
 *
 * <p>为什么是逗号串而不是 JSON 列或关联表：
 * <ul>
 *   <li>关联表（{@code automation_kb}）为了"最多几十个 ID"多一张表、多一次 join，
 *       而这段数据从不需要按 kbId 反查任务；</li>
 *   <li>JSON 列要挂 type handler（{@code JsonTextTypeHandler}），读出来还要再包一层 List；</li>
 *   <li>逗号串在 MySQL 与 H2 MODE=MySQL 下行为一致，DBA 也能一眼读懂。</li>
 * </ul>
 * 代价是不能用 SQL 过滤"哪些任务搜这个库"——目前没有这个需求。
 */
final class AutomationKbIds {

    private AutomationKbIds() {
    }

    /**
     * 解析成有序、去重的 ID 列表；脏数据（非数字、非正数）跳过而不抛异常
     * （调度是无人值守路径，一行脏数据不该让整个扫描失败）。
     *
     * @param raw 库里存的逗号串
     * @return 不可变列表；空串/空白返回空列表
     */
    static List<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(trimmed);
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 脏 token 跳过：见方法注释
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 编码成逗号串。
     *
     * @param ids 知识库 ID；null 或空返回 null（列为 NULL 表示"不覆盖 Agent 绑定"）
     * @return 逗号分隔串
     */
    static String join(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(ids.size());
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id != null && id > 0) {
                parts.add(Long.toString(id));
            }
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }
}
