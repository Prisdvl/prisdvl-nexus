package com.lky.nexus.ai.agent;

import java.util.Objects;

/**
 * 工具的<b>不可变定义</b>（平台化 §十九：Tool Definition）。
 *
 * <p>定位与 {@link AgentDefinition} 一致：装配给 Agent 时只认这个值对象，不认 MyBatis 实体
 * （实体里的 owner_id/deleted/updated_at 对"怎么给模型一个工具"没有意义）。
 * 但两者的<b>来源结构不同</b>，别照搬：{@code AgentDefinition} 的每个字段都在 {@code agent} 行上，
 * 而本记录是<b>两个来源拼出来的</b>——描述性字段来自代码侧清单（{@code ToolCatalog}），
 * {@code enabled} 才来自该用户的 {@code tool} 覆盖行（<b>那行可以不存在</b>，不存在即启用）。
 * 库里没有 name/description/jsonSchema 的副本，这是有意的（见 V13 文件头）。
 *
 * <p><b>字段分工有一条硬边界</b>，写在这里免得以后混掉：
 * <ul>
 *   <li><b>代码拥有</b>：{@code name} / {@code description} / {@code jsonSchema}——它们是实现的一部分。
 *       {@code @Tool} 方法上的描述就是模型看到的描述，改它等于改模型行为，所以由 ToolCatalog
 *       每次从实现反射生成、<b>既不落库也不缓存</b>（没有第二份，就没有漂移）；</li>
 *   <li><b>用户拥有</b>：{@code enabled}——今天唯一"用户改了真的会变"的字段。停用的工具
 *       <b>不会装给任何 Agent</b>，模型连它的存在都看不到（§5.3 的 DENY 语义）。</li>
 * </ul>
 *
 * <p>刻意<b>没有</b>的字段：{@code permission} / {@code approvalRequired} / {@code timeoutMs} /
 * {@code rateLimit}。今天没有任何一处会读它们（审批要等 §5.4 的挂起-恢复机制，超时/限流要等
 * 执行壳真的会拦截）。先放进来只会得到"配了但不生效"的假旋钮——与 V12 不建
 * {@code max_iterations} 是同一条理由（decisions.md B.35 §7）。
 * 它们与 P1 的 Tool Permission / Approval 一起落地。
 *
 * <p>为什么放在 {@code nexus-ai}：它是"工具是什么"的抽象，与厂商无关，也与"从哪一行读出来"无关；
 * {@code nexus-app} 决定"从哪来"（数据库 + 代码里的实现）。依赖方向因此是 {@code app → ai}。
 */
public record ToolDefinition(

        /** 工具标识，也是**模型看到的名字**（如 {@code knowledge_search}）；不给模型看中文名。 */
        String code,

        /** 显示名（只用于界面）。 */
        String name,

        /** 给模型看的描述：模型据此决定何时调用它。 */
        String description,

        /** 来源：今天只有代码里的 BUILTIN，MCP 后置。 */
        ToolSource source,

        /** 分类（界面分组用，不参与任何判定）。 */
        ToolCategory category,

        /**
         * 权限分级（P0）：执行前是否需要人工审批的**唯一依据**。
         *
         * <p>注意这里放的是"代码声明的默认值"。运行时真正生效的那一份在
         * {@code ai_tool_registry.permission}（V22）：管理员把它改成 DANGEROUS 之后，
         * 执行拦截读的是库里的值。两者不一致时以库为准——理由是安全默认：
         * 降级必须显式发生在数据里，不能被"代码里写着 READ"悄悄覆盖。
         */
        ToolPermission permission,

        /**
         * 入参 JSON Schema：由实现的 {@code @Tool} 方法反射生成，<b>只活在内存里</b>
         * （{@code tool} 表没有这一列）。MCP 的 {@code tools/list} 直接读它——
         * 正是为了让 MCP 客户端看到的 schema 与模型看到的永远是同一份。
         */
        String jsonSchema,

        /** 该用户是否启用；<b>停用 = 不装给任何 Agent</b>（今天唯一真拦截点）。 */
        boolean enabled,

        /**
         * 是否系统内置：实现活在代码里，因此<b>不可删除</b>（可停用——停用就真的不装给任何 Agent）。
         *
         * <p>今天恒为 {@code true}：清单里只有内置工具，外部 MCP 工具落地后它才第一次可能为 {@code false}。
         * 与 {@code AgentService.resolveDefault} 那个内置 Agent 不同，内置工具<b>不为用户补数据行</b>：
         * 没有覆盖行就是启用（见 V13 文件头），所以"清库/新用户"不会让工具消失。
         */
        boolean builtin) {

    public ToolDefinition {
        code = requireText(code, "code");
        name = requireText(name, "name");
        description = requireText(description, "description");
        Objects.requireNonNull(source, "ToolDefinition.source 不能为空");
        Objects.requireNonNull(category, "ToolDefinition.category 不能为空");
        Objects.requireNonNull(permission, "ToolDefinition.permission 不能为空："
                + "缺失等于「权限未知」，而未知权限必须默认拒绝——不要用 null 表示 READ");
        Objects.requireNonNull(jsonSchema, "ToolDefinition.jsonSchema 不能为空："
                + "它由实现的 @Tool 方法反射生成，缺失说明装配环节出了问题（不要拿 null 去糊 MCP 的 tools/list）");
    }

    /** 给日志与痕迹用的一句话描述。 */
    public String describe() {
        return "%s(%s, %s, %s%s)".formatted(code, name, source, permission,
                builtin ? ", 内置" : "");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ToolDefinition.%s 不能为空".formatted(field));
        }
        return value;
    }
}
