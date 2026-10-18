package com.lky.nexus.app.knowledge;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BYOK 嵌入凭据的<b>真实调用</b>验收（默认跳过，不伪造通过）。
 *
 * <p>本类的存在意义：其余 BYOK 用例（{@code KnowledgeEmbeddingByokTest} 等）都止步于
 * "构建了哪个客户端"，**没有任何一条真正把请求发到用户自己的端点上**。而 BYOK 要成立，
 * 恰恰需要这一条：解密出来的 Key 能通过真实鉴权、用户端点返回的向量宽度与平台冻结维度一致。
 *
 * <p>启用方式（密钥只从环境变量读，不写进仓库、不进日志）：
 * <pre>
 * $env:BYOK_LIVE_EMBEDDING_KEY='&lt;用户自己的百炼 Key&gt;'
 * $env:BYOK_LIVE_EMBEDDING_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode/v1'   # 可省
 * mvn -pl nexus-app test -Dtest=EmbeddingByokLiveTest
 * </pre>
 *
 * <p><b>为什么"全局 Key 是假值"反而是这里的证据</b>：test profile 里全局
 * {@code embedding.default} 用的是假 Key（{@code sk-test-dummy…}）。所以只要下面这次真实调用
 * 成功，就说明请求确实走了<b>用户自己</b>的凭据——回落全局会直接 401。
 * 这也让"两个客户端不是同一个实例"这条身份断言有了行为层面的佐证。
 *
 * <p><b>刻意不做的事</b>：不调 {@code /search}。向量检索需要 pgvector（Docker），
 * 本环境未启用；"检索"这一段在集成测试里由预检与回退行为覆盖，真正的向量往返留给有基础设施的环境。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "BYOK_LIVE_EMBEDDING_KEY", matches = ".+")
@DisplayName("BYOK 嵌入：真实端点调用（1024 维 + 语义可用 + 确实走用户凭据）")
class EmbeddingByokLiveTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String USERNAME = "byok-live";
    private static final String MODEL = "text-embedding-v4";
    private static final String PROVIDER = "bailian";
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private KnowledgeEmbeddingResolver embeddingResolver;

    @Autowired
    private EmbeddingClientFactory embeddingClientFactory;

    @Test
    @DisplayName("用户凭据 → 真实嵌入请求 → 建库：1024 维、语义排序正确、且用的不是全局客户端")
    void userCredentialsReachTheRealEndpoint() throws Exception {
        String key = System.getenv("BYOK_LIVE_EMBEDDING_KEY");
        String baseUrl = System.getenv().getOrDefault("BYOK_LIVE_EMBEDDING_BASE_URL", DEFAULT_BASE_URL);
        String token = registerAndLogin(USERNAME);

        mockMvc.perform(put("/api/users/me/embedding-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"%s","modelName":"%s","baseUrl":"%s",
                                 "apiKey":"%s","enabled":true}
                                """.formatted(PROVIDER, MODEL, baseUrl, key)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .as("接口不得回显明文 Key")
                        .doesNotContain(key));

        KnowledgeBasePO kb = createKnowledgeBase(token, "LIVE 验收库");
        assertThat(kb.getEmbeddingProvider()).isEqualTo(PROVIDER);
        assertThat(kb.getEmbeddingModel()).isEqualTo(MODEL);
        assertThat(kb.getEmbeddingDim()).isEqualTo(1024);

        EmbeddingClient global = embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT);
        EmbeddingClient userClient = embeddingResolver.resolve(kb);
        assertThat(userClient)
                .as("解析出的必须是按用户凭据构建的客户端，不是全局那个")
                .isNotSameAs(global);
        assertThat(userClient.reference()).isEqualTo(PROVIDER + ":" + MODEL);

        // ↓↓↓ 真实网络请求（1 次维度探测 + 2 次嵌入）↓↓↓
        assertThat(userClient.dimensions()).as("用户端点实际返回的维度").isEqualTo(1024);

        float[] a = userClient.embed("Redis 缓存击穿是指热点 key 失效，大量请求直接打到数据库");
        float[] b = userClient.embed("用互斥锁保证只有一个请求去数据库加载数据，其余等待");
        float[] unrelated = userClient.embed("今天天气不错，适合出门散步");

        assertThat(a).hasSize(1024);
        assertThat(nonZeroRatio(a)).as("向量不应全为零（模型没真正工作时会全零）").isGreaterThan(0.9);
        double related = cosine(a, b);
        double unrelatedScore = cosine(a, unrelated);
        assertThat(related)
                .as("同主题相似度 %.4f 应显著高于无关 %.4f", related, unrelatedScore)
                .isGreaterThan(unrelatedScore + 0.1);
    }

    // ------------------------------------------------------------------

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }

    private KnowledgeBasePO createKnowledgeBase(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long id = Long.valueOf(JsonPath.read(body, "$.data.id").toString());
        return knowledgeBaseMapper.selectById(id);
    }

    private double nonZeroRatio(float[] vector) {
        long nonZero = 0;
        for (float value : vector) {
            if (value != 0f) {
                nonZero++;
            }
        }
        return (double) nonZero / vector.length;
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
