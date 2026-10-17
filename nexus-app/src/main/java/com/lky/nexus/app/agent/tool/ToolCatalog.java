package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.ToolCategory;
import com.lky.nexus.ai.agent.ToolDefinition;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.ai.agent.ToolSource;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内置工具清单（4.3，§十九 Tool Definition 的"代码那一侧"）。
 *
 * <p><b>它解决的问题是"两份硬编码"</b>（核对过，不是照抄设计稿的"三份"）：在这次改动之前，
 * {@code AgentRuntime.configure} 里写死了一句 {@code ToolCallbacks.from(knowledgeSearchTool)}——
 * 一次性调用与流式对话都走它（流式自 4.1 起已委托 {@code configure}，{@code ChatStreamServiceImpl}
 * 里留下的只是一个没人用的 import，随本增量删掉）；而 {@code McpEndpoint} 又<b>手写</b>了一份
 * {@code knowledge_search} 的名字、描述与 JSON Schema（真正需要消灭的是这第二份——它随时会与
 * 实现漂移，而漂移的症状就是"模型偶尔不调用工具"这种最难查的东西）。
 *
 * <p>现在唯一的来源是<b>实现本身</b>：{@code @Tool} 方法的名字、描述与参数 schema 由
 * Spring AI 从方法签名反射出来，本类只是把它们收集起来。加一个内置工具的完整步骤是
 * "写一个 {@code @Tool} 类 + 在下面登记一行"——名字与描述永远不可能与实现不一致，
 * 因为根本没有第二份。
 *
 * <p>显示名与分类（{@link Declaration}）是唯一"代码里额外声明"的东西：它们只用于界面，
 * 模型看不到，因此不影响模型行为。
 */
@Component
public class ToolCatalog {

    /**
     * 注册表里的一项：{@code code} 与描述/schema 全部来自实现，本类只加上显示名与分类。
     *
     * <p>{@link #callback()} 是<b>未包壳</b>的原始回调；执行壳（日志、计数、把异常变成
     * 明确的观察结果）由 {@link AgentToolFactory} 装配时套上——这样清单本身保持纯粹，
     * 也能被"直接调用"的测试路径原样使用。
     *
     * <p><b>{@code selfReports} 是给执行壳兜底用的</b>（4.3 真机实测的第二个回归，见
     * decisions.md B.40）：过程痕迹（{@code onCall} / {@code onResult}）原先全靠<b>工具自己</b>调
     * {@code AgentContext.record} 上报，于是"这个工具忘了上报"就等于"它在痕迹、SSE 事件、
     * 界面卡片、评测计数里<b>不存在</b>"——而 {@code ExecutingToolCallback} 自己并不知道
     * 工具有没有报过。多工具之前这条永远不会露头（当时唯一那个工具自己报，计数不可能错）。
     *
     * <p>修法不是"记得报"，而是<b>把兜底责任挪到壳上</b>：{@code selfReports == false} 的工具，
     * 由壳在调用前后各补一条通用痕迹。不变量因此变成
     * <b>"每一次成功的工具调用恰好产生一条痕迹"</b>——它由
     * {@code ToolResourceTest#everyToolLeavesExactlyOneTrace} 对<b>注册表里的每一个工具</b>断言，
     * 所以这个标志位写错了会红，而不是静静地少报一条。
     */
    public record Registered(String code,
                            String displayName,
                            ToolCategory category,
                            ToolPermission permission,
                            String description,
                            String jsonSchema,
                            ToolCallback callback,
                            boolean selfReports) {

        /** 转成运行期值对象；{@code enabled} 来自用户态（没有覆盖行就是 true）。 */
        public ToolDefinition toDefinition(boolean enabled) {
            return new ToolDefinition(code, displayName, description, ToolSource.BUILTIN,
                    category, permission, jsonSchema, enabled, true);
        }

        public String describe() {
            return "%s(%s)".formatted(code, displayName);
        }
    }

    private final Map<String, Registered> byCode;

    /**
     * P0 起工具来源由 {@link ToolProvider} 决定（Spring 按 {@code @Order} 注入有序列表），
     * 而不是在构造器里写死两条。行为不变：内置 provider 的顺序仍是
     * knowledge_search → calculator。
     */
    public ToolCatalog(List<ToolProvider> providers) {
        this.byCode = register(providers);
    }

    /** 全部内置工具（按登记顺序：界面与 MCP tools/list 用它）。 */
    public List<Registered> all() {
        return List.copyOf(byCode.values());
    }

    public Optional<Registered> find(String code) {
        return Optional.ofNullable(code == null ? null : byCode.get(code.trim()));
    }

    private Map<String, Registered> register(List<ToolProvider> providers) {
        Map<String, Registered> registered = new LinkedHashMap<>();
        for (ToolDeclaration declaration : providers.stream().flatMap(p -> p.declare().stream()).toList()) {
            ToolCallback[] callbacks = ToolCallbacks.from(declaration.implementation());
            if (callbacks.length == 0) {
                // 登记了却没有 @Tool 方法：这种"看起来注册了其实什么也没有"的静默缺口必须在启动时炸掉
                throw new IllegalStateException("工具实现 %s 没有任何 @Tool 方法（注册错了？）"
                        .formatted(declaration.implementation().getClass().getSimpleName()));
            }
            for (ToolCallback callback : callbacks) {
                // 全限定名是必要的：本类同时用到两个 ToolDefinition——
                // 我们的值对象 com.lky.nexus.ai.agent.ToolDefinition（模型与平台视角的"工具定义"）
                // 与 Spring AI 的 org.springframework.ai.tool.definition.ToolDefinition（框架反射出的
                // 名字/描述/schema）。Java 没有 import 别名，所以框架那个内联写全。
                org.springframework.ai.tool.definition.ToolDefinition definition = callback.getToolDefinition();
                String code = definition.name();
                if (!StringUtils.hasText(definition.description())) {
                    // 描述就是模型决定"何时调用它"的全部依据，缺了它工具等于不可用
                    throw new IllegalStateException("工具 %s 没有描述：模型据此决定何时调用，不能为空".formatted(code));
                }
                if (!StringUtils.hasText(definition.inputSchema())) {
                    throw new IllegalStateException("工具 %s 没有入参 schema（反射失败？）——MCP 的 tools/list 直接读它".formatted(code));
                }
                Registered previous = registered.put(code, new Registered(code, declaration.displayName(),
                        declaration.category(), declaration.permission(), definition.description(),
                        definition.inputSchema(), callback, declaration.selfReports()));
                if (previous != null) {
                    // code 撞车会让"按 code 绑定"这件事产生歧义（绑的是哪一个？），启动时就该停
                    throw new IllegalStateException("内置工具的 code 重复：%s（%s 与 %s 同名，模型只能看到一个）"
                            .formatted(code, previous.describe(), declaration.implementation().getClass().getSimpleName()));
                }
            }
        }
        // 刻意不用 Map.copyOf：它不保证迭代顺序，会让 tools/list 与界面上的工具顺序随机变化。
        // 登记顺序（检索在前、计算在后）是有意义的——它是给用户看的顺序。
        return Collections.unmodifiableMap(registered);
    }
}
