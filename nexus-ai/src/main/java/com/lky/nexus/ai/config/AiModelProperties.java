package com.lky.nexus.ai.config;

import com.lky.nexus.ai.model.ModelRef;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型配置（{@code ai.*}），模型可替换层的唯一配置入口（ADR-003）。
 *
 * <pre>
 * ai:
 *   defaults:
 *     temperature: 0.7
 *   providers:
 *     - name: deepseek
 *       type: OPENAI_COMPATIBLE
 *       base-url: https://api.deepseek.com
 *       api-key: ${AI_CHAT_API_KEY:}
 *       models:
 *         - name: deepseek-chat
 *           context-window: 65536
 *           capabilities: [CHAT, TOOL_CALLING, JSON]
 *   roles:
 *     chat.default: deepseek:deepseek-chat
 *     chat.agent:   deepseek:deepseek-chat
 *     chat.judge:   deepseek:deepseek-chat
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai")
public class AiModelProperties {

    private Defaults defaults = new Defaults();

    /** Agent 工具循环的上限（`ai.tools`）。 */
    private ToolLimits tools = new ToolLimits();

    /** 用户自带模型（BYOK）的约束（`ai.byok.*`）。 */
    private Byok byok = new Byok();

    /** 供应商列表。 */
    private List<Provider> providers = new ArrayList<>();

    /** 逻辑角色 → 模型引用（{@code provider:model}）。业务代码只认这里的 key。 */
    private Map<String, String> roles = new LinkedHashMap<>();

    /** 逻辑角色名常量，避免业务代码里散落字符串。 */
    public static final class Role {
        /** 默认对话模型。 */
        public static final String CHAT_DEFAULT = "chat.default";
        /** Agent 使用的模型（需要较强的工具调用能力）。 */
        public static final String CHAT_AGENT = "chat.agent";
        /** 评测裁判模型（可以用更便宜的模型）。 */
        public static final String CHAT_JUDGE = "chat.judge";
        /** 默认嵌入模型。 */
        public static final String EMBEDDING_DEFAULT = "embedding.default";

        private Role() {
        }
    }

    @Getter
    @Setter
    public static class Defaults {
        /** 默认温度。事实型问答（RAG）建议偏低，创意任务可调高。 */
        private Double temperature = 0.7;
        /** 默认单次最大输出 token；null 表示交给服务端默认值。 */
        private Integer maxTokens;
        /**
         * 单次模型调用的总超时（connect + read，含流式首字节与完整生成）。
         *
         * <p>为什么必须显式设：不设时 Spring AI 底层 OkHttp 用系统默认（可达 5 分钟以上），
         * 一旦上游挂住（非标准错误体未被识别为错误、或网关 silently hang），用户请求会一直挂到
         * TCP 层超时——实测 opencode.ai/zen 额度耗尽时同步请求挂了 180s 不返回。
         * 120s 覆盖 DeepSeek 长输出上限，超过即按 MODEL_TIMEOUT 快速失败。
         */
        private Duration chatTimeout = Duration.ofSeconds(120);

        /**
         * 同步 /ask 的<b>业务总时限</b>：覆盖一次调用里所有 HTTP 尝试 + SDK 自动重试 + 退避。
         *
         * <p>为什么需要它：{@link #chatTimeout} 是 OkHttp callTimeout，只管单次 HTTP call；
         * SDK 默认 maxRetries=2，最坏 3 次 call × 120s = 360s。业务层不能让用户等 6 分钟，
         * 因此在这之上加一道总闸——从一次业务调用开始计时，到点不再等。
         *
         * <p>超时后 {@code future.cancel(true)} 中断底层线程；OkHttp 的 120s callTimeout
         * 是第二道兜底，即使应用层中断没生效，底层最多再跑 120s 自己断。
         */
        private Duration chatTotalTimeout = Duration.ofSeconds(180);
    }

    /**
     * 工具循环上限。
     *
     * <p><b>为什么不写在 {@code spring.ai.tools.limits.*} 里</b>：那两个 key 是 Spring AI
     * 为<b>自动配置出来的</b> ChatModel 准备的，而本项目关闭了模型自动配置
     * （{@code spring.ai.model.chat=none}，见 {@code AiModelRegistry} 类注释），
     * 模型由 {@code AiModelRegistry} 手工构建。手工构建时若不给
     * {@code ToolCallingManager}，框架会自己造一个默认的（40 / 150 / 超限抛异常），
     * 它<b>不会</b>去读容器里的 {@code spring.ai.tools} 属性。
     * 所以把上限写进 {@code spring.ai.tools.limits.*} 就是一个假旋钮：
     * 改了没有任何效果，却让人以为已经配置过（实测于 2026-10-01，见 decisions.md B.43）。
     *
     * <p>默认值与框架的 {@code DefaultToolCallingManager} 一致——默认值不应该是新数字，
     * 只是把它变成"看得见、改得动、有测试钉住"的配置。
     */
    @Getter
    @Setter
    public static class ToolLimits {

        /** 单个工具在一次生成里最多被调用几次（框架默认 40）。 */
        private int maxCallsPerToolDefault = 40;

        /** 一次生成里所有工具合计最多被调用几次（框架默认 150）。 */
        private int maxTotalToolCalls = 150;
    }

    @Getter
    @Setter
    public static class Provider {        /** 供应商标识，如 deepseek / ollama；被 roles 引用。 */
        private String name;
        private ProviderType type;
        /** OpenAI 兼容端点或 Ollama 地址。 */
        private String baseUrl;
        /** API Key；Ollama 本地服务留空。 */
        private String apiKey;
        /**
         * 附加请求头（随每次调用发送）。
         *
         * <p>为什么需要它：很多"OpenAI 兼容"网关并非只兼容路径，还要求额外的头。真实例子：
         * <ul>
         *   <li>OpenCode Zen：要求 {@code x-opencode-session}（会话路由与 prompt 缓存）
         *       与自定义 {@code User-Agent}；</li>
         *   <li>OpenRouter：要求 {@code HTTP-Referer} / {@code X-Title}；</li>
         *   <li>Azure OpenAI：要求 {@code api-version}。</li>
         * </ul>
         * 把这类差异收敛进配置，业务代码与注册表逻辑都不需要感知具体网关。
         */
        private Map<String, String> headers = new LinkedHashMap<>();
        private List<ModelEntry> models = new ArrayList<>();
    }

    /**
     * 用户自带模型（BYOK）的约束（{@code ai.byok.*}）。
     *
     * <p>为什么嵌入需要白名单而对话不需要：对话模型换供应商只影响单次生成的文字；
     * 嵌入模型决定<b>向量空间</b>，而向量列宽在建库时就写死并冻结（ADR-008）。
     * 让用户随便填一个 768 维模型，写进去的向量宽度就是错的——而且不会报错，
     * 只会表现为"检索结果莫名其妙"。所以白名单与维度是<b>平台侧</b>的硬约束，
     * 不是可以交给用户自己负责的偏好。
     */
    @Getter
    @Setter
    public static class Byok {

        private EmbeddingByok embedding = new EmbeddingByok();

        @Getter
        @Setter
        public static class EmbeddingByok {

            /**
             * 允许用户配置的嵌入模型白名单，元素形如 {@code provider:model}。
             *
             * <p>放配置而不是代码枚举：加一个经 LIVE 验证过的模型不必改代码、不必发版。
             * 但<b>不是</b>可以随便放宽的旋钮——每加一项都意味着"这个模型的维度与端点
             * 已经被真实验证过"，见 {@code docs/roadmap.md} 的路线 A 验收标准。
             *
             * <p>默认只含百炼 {@code text-embedding-v4}（1024 维，与现有
             * {@code vector(1024)} 列和既有知识库冻结值一致，可平滑替换）。
             */
            private List<String> allowedModels = new ArrayList<>(List.of("bailian:text-embedding-v4"));

            /**
             * 首期唯一允许的嵌入维度。
             *
             * <p>向量列是 {@code vector(1024)}，换维度必须新建列/表并重建索引
             * （{@code db/vector-migration/V1__vector_store.sql} 的注释）。
             * 多维度分表属路线 B，本轮不实施；这里固定成 1024，遇到别的维度直接拒绝。
             */
            private int requiredDimensions = 1024;

            /**
             * 是否允许用户填<b>白名单之外</b>的 OpenAI 兼容端点（自行填 Base URL + 模型名）。
             *
             * <p>与白名单不冲突：白名单里的模型是「平台验证过、拿来即用」的快路径；
             * 自定义端点是「用户自己的网关/自建模型」，保存时必须当场探针成功
             * 且返回维度等于 {@link #requiredDimensions}（见 {@code CustomEmbeddingProbe}）。
             *
             * <p>默认开启。关掉它就等于「只许用平台这几种模型」——企业内网部署常用这个姿势。
             */
            private boolean allowCustomEndpoint = true;

            /**
             * 探针是否允许访问内网 / 本机地址。
             *
             * <p>默认 {@code false}：Base URL 是用户输入，允许它指向内网就等于把服务端变成
             * SSRF 探测器。只有本机开发与测试 profile 打开——那里的「自定义端点」是
             * localhost 上的桩服务。
             */
            private boolean allowPrivateEndpoints = false;
        }
    }

    @Getter
    @Setter
    public static class ModelEntry {
        /** 模型名，如 deepseek-chat / qwen3:7b / bge-m3。 */
        private String name;
        /** 上下文窗口（token），供上下文裁剪与观测使用。 */
        private Integer contextWindow;
        /**
         * 嵌入维度（仅嵌入模型需要）。
         *
         * <p>它必须在知识库创建时被**冻结**（ADR-008）：向量库的列宽、HNSW 索引上限
         * 都依赖这个数字，中途改维度会让已入库的向量全部失效——而且不会报错，
         * 只会表现得"检索结果莫名其妙"。
         */
        private Integer dimensions;
        /** 能力标签，决定该模型能否承担某类角色。 */
        private List<ModelCapability> capabilities = new ArrayList<>();

        public boolean supports(ModelCapability capability) {
            return capabilities != null && capabilities.contains(capability);
        }
    }

    /** 供应商协议类型。 */
    public enum ProviderType {
        /**
         * OpenAI 兼容协议：DeepSeek、阿里云百炼兼容模式、智谱、SiliconFlow、vLLM、Ollama 的 /v1 端点都属此类。
         * 这是本项目的默认协议——换供应商时通常只需要改 base-url 与模型名。
         */
        OPENAI_COMPATIBLE,
        /** Ollama 原生协议（/api/chat），用于本地模型与嵌入。 */
        OLLAMA
    }

    /** 按名字查供应商。 */
    public Optional<Provider> findProvider(String name) {
        return providers.stream().filter(provider -> name.equals(provider.getName())).findFirst();
    }

    /**
     * 按 {@code provider:model} 引用查模型条目。
     *
     * <p>知识库创建时用它把 {@code embedding.default} 角色解析成具体模型名与维度并冻结（ADR-008）。
     */
    public Optional<ModelEntry> findModel(ModelRef ref) {
        return findProvider(ref.provider())
                .flatMap(provider -> provider.getModels().stream()
                        .filter(entry -> ref.model().equals(entry.getName()))
                        .findFirst());
    }

    /** 模型能力标签。 */
    public enum ModelCapability {
        CHAT,
        EMBEDDING,
        /** 支持 Tool Calling —— Agent 阶段的前提条件。 */
        TOOL_CALLING,
        /** 支持强制 JSON 输出。 */
        JSON
    }
}
