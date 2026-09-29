package com.lky.prisma.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    }

    @Getter
    @Setter
    public static class Provider {
        /** 供应商标识，如 deepseek / ollama；被 roles 引用。 */
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

    @Getter
    @Setter
    public static class ModelEntry {
        /** 模型名，如 deepseek-chat / qwen3:7b / bge-m3。 */
        private String name;
        /** 上下文窗口（token），供上下文裁剪与观测使用。 */
        private Integer contextWindow;
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
