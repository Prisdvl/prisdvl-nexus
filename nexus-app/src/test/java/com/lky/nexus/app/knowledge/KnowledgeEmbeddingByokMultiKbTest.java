package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BYOK 嵌入凭据在<b>多知识库扇出</b>上的边界（路线 A 验收）。
 *
 * <p>为什么单库通过还不够：{@code searchMany} 在扇出<b>之前</b>逐个库做嵌入模型预检，
 * 一个库不合格就整批失败（不做部分成功），因为跨库的向量分数只有在"所有库同一个模型"时
 * 才可比。这条不变式一旦被 BYOK 打破，后果是"结果看起来正常但排序错乱"——最难发现的那种。
 *
 * <p>本类用 {@code BM25} 模式把断言钉在<b>预检</b>这一步：关键词那一路不调用嵌入模型，
 * 因此不需要网络，也不会真的去连模型；测试 profile 下索引未启用（{@code nexus.search.enabled=false}），
 * 所以"预检通过"表现为后续的索引不可用错误（4005），而不是模型不一致（4006）。
 * 两种结果可以精确区分，这正是本类要的判据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("嵌入 BYOK 多库边界：同模型可同查 / 不一致整批拒绝 / 禁用回落")
class KnowledgeEmbeddingByokMultiKbTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String BYOK_BASE_URL = "https://byok-tenant.example.com/compatible-mode/v1";
    private static final String BYOK_KEY = "sk-byok-abcdefghijklmnop123456";
    private static final String GLOBAL_MODEL = "bailian:text-embedding-v4";
    private static final String OTHER_ALLOWED_MODEL = "bailian:text-embedding-v3";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ChunkSearchService chunkSearchService;

    @Autowired
    private KnowledgeEmbeddingResolver embeddingResolver;

    @Autowired
    private EmbeddingClientFactory embeddingClientFactory;

    @Test
    @DisplayName("同一模型的多库：预检通过，可一起查（不会因模型不一致被拒）")
    void sameModelKnowledgeBasesShareOneEmbeddingSpace() throws Exception {
        registerAndLogin("byok-multi-same");
        Long userId = userIdOf("byok-multi-same");
        KnowledgeBasePO first = createKnowledgeBase("byok-multi-same", "同模型库 1");
        KnowledgeBasePO second = createKnowledgeBase("byok-multi-same", "同模型库 2");

        // 两个库都用全局模型冻结；解析到同一个客户端实例 ⇒ 向量空间一致、分数可比
        EmbeddingClient global = embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT);
        assertThat(embeddingResolver.resolve(first)).isSameAs(global);
        assertThat(embeddingResolver.resolve(second)).isSameAs(global);

        // 走真实多库入口：预检通过 ⇒ 失败点落在"索引未启用"（4005），而不是模型不一致（4006）
        BusinessException failure = searchManyFailure(userId, List.of(first.getId(), second.getId()));
        assertThat(failure.getCode())
                .as("同模型多库不应因嵌入模型被拒（4006），实际：%s", failure.getMessage())
                .isEqualTo(ErrorCode.VECTOR_STORE_UNAVAILABLE.getCode());
        assertThat(failure.getMessage()).doesNotContain("冻结的嵌入模型");
    }

    @Test
    @DisplayName("同一个人先后用两个模型建的库：整批 4006 拒绝，报错点名是哪个库、为什么")
    void mixedFrozenModelsFailWholeBatch() throws Exception {
        String token = registerAndLogin("byok-multi-mixed");
        Long userId = userIdOf("byok-multi-mixed");

        saveConfig(token, OTHER_ALLOWED_MODEL, true);
        KnowledgeBasePO frozenV3 = createKnowledgeBase("byok-multi-mixed", "冻结在 v3 的库");
        saveConfig(token, GLOBAL_MODEL, true);
        KnowledgeBasePO frozenV4 = createKnowledgeBase("byok-multi-mixed", "冻结在 v4 的库");

        assertThat(frozenV3.getEmbeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(frozenV4.getEmbeddingModel()).isEqualTo("text-embedding-v4");

        BusinessException failure = searchManyFailure(userId, List.of(frozenV3.getId(), frozenV4.getId()));
        assertThat(failure.getCode()).isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
        // 报错必须能定位到具体库（否则多库场景下用户不知道去修哪一个）
        assertThat(failure.getMessage())
                .contains("冻结在 v3 的库")
                .contains("id=" + frozenV3.getId())
                .contains("冻结的嵌入模型")
                .contains("用户自带的嵌入配置");
        // 预检在扇出之前：它不该是"某个库失败后隔离"的结果（那会是 4005/其它）
        assertThat(failure.getCode()).isNotEqualTo(ErrorCode.VECTOR_STORE_UNAVAILABLE.getCode());

        // 反向顺序也点名对应的库（顺序敏感不是意外，是保序透传）
        BusinessException reversed = searchManyFailure(userId, List.of(frozenV4.getId(), frozenV3.getId()));
        assertThat(reversed.getCode()).isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
    }

    @Test
    @DisplayName("禁用 BYOK 后回落全局：新库与全局库可同查；但冻结在旧模型上的库仍被拒（禁用不救旧库）")
    void disablingConfigFallsBackToGlobalWithoutRescuingOldKnowledgeBases() throws Exception {
        String token = registerAndLogin("byok-multi-disable");
        Long userId = userIdOf("byok-multi-disable");

        saveConfig(token, OTHER_ALLOWED_MODEL, true);
        KnowledgeBasePO frozenV3 = createKnowledgeBase("byok-multi-disable", "旧模型库");
        saveConfig(token, GLOBAL_MODEL, false);   // 关掉开关：回落全局
        KnowledgeBasePO afterDisable = createKnowledgeBase("byok-multi-disable", "禁用后新建的库");

        assertThat(afterDisable.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(embeddingResolver.resolve(afterDisable))
                .isSameAs(embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT));

        // 禁用后：与全局同模型的新库能参与多库检索
        BusinessException ok = searchManyFailure(userId, List.of(afterDisable.getId()));
        assertThat(ok.getCode()).isEqualTo(ErrorCode.VECTOR_STORE_UNAVAILABLE.getCode());

        // 但旧库不会因为"关掉 BYOK"就自动切回全局模型——必须显式重建
        BusinessException stillRejected = searchManyFailure(userId, List.of(frozenV3.getId(), afterDisable.getId()));
        assertThat(stillRejected.getCode()).isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
        assertThat(stillRejected.getMessage()).contains("旧模型库");
    }

    @Test
    @DisplayName("凭据按所有者隔离：A 的 BYOK 不影响 B 的多库检索")
    void credentialsAreScopedToKnowledgeBaseOwner() throws Exception {
        String tokenA = registerAndLogin("byok-multi-owner-a");
        String tokenB = registerAndLogin("byok-multi-owner-b");
        saveConfig(tokenA, OTHER_ALLOWED_MODEL, true);

        KnowledgeBasePO kbA = createKnowledgeBase("byok-multi-owner-a", "A 的库");
        KnowledgeBasePO kbB1 = createKnowledgeBase("byok-multi-owner-b", "B 的库 1");
        KnowledgeBasePO kbB2 = createKnowledgeBase("byok-multi-owner-b", "B 的库 2");

        // A 的库走他自己的模型与客户端；B 的库完全走全局
        assertThat(kbA.getEmbeddingModel()).isEqualTo("text-embedding-v3");
        EmbeddingClient global = embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT);
        assertThat(embeddingResolver.resolve(kbA)).isNotSameAs(global);
        assertThat(embeddingResolver.resolve(kbB1)).isSameAs(global);
        assertThat(embeddingResolver.resolve(kbB2)).isSameAs(global);

        // B 的多库检索不受 A 的配置影响：预检通过，失败点是索引未启用
        BusinessException failure = searchManyFailure(userIdOf("byok-multi-owner-b"),
                List.of(kbB1.getId(), kbB2.getId()));
        assertThat(failure.getCode()).isEqualTo(ErrorCode.VECTOR_STORE_UNAVAILABLE.getCode());
        assertThat(failure.getMessage()).doesNotContain("冻结的嵌入模型");

        // 跨用户的库混在一起时先被归属校验拦下（3001），不会走到模型预检
        BusinessException crossUser = searchManyFailure(userIdOf("byok-multi-owner-b"),
                List.of(kbB1.getId(), kbA.getId()));
        assertThat(crossUser.getCode()).isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------------

    /** 跑一次多库检索并把异常取出来（这些用例的失败是<b>预期结果</b>，所以不写成 assertThatThrownBy 链）。 */
    private BusinessException searchManyFailure(Long userId, List<Long> knowledgeBaseIds) {
        try {
            chunkSearchService.searchMany(userId, knowledgeBaseIds, "redis 缓存击穿", 5,
                    SearchMode.BM25, RetrievalFilters.none());
            throw new AssertionError("预期多库检索失败，但返回了结果（知识库 " + knowledgeBaseIds + "）");
        } catch (BusinessException ex) {
            return ex;
        }
    }

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

    private void saveConfig(String token, String reference, boolean enabled) throws Exception {
        String[] parts = reference.split(":", 2);
        mockMvc.perform(put("/api/users/me/embedding-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"%s","modelName":"%s","baseUrl":"%s",
                                 "apiKey":"%s","enabled":%s}
                                """.formatted(parts[0], parts[1], BYOK_BASE_URL, BYOK_KEY, enabled)))
                .andExpect(status().isOk());
    }

    private KnowledgeBasePO createKnowledgeBase(String username, String name) {
        try {
            String token = loginOf(username);
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
        } catch (Exception ex) {
            throw new IllegalStateException("建库失败：" + name, ex);
        }
    }

    /** 用例内多次建库要复用同一个 token；这里简单地再登一次（账号已存在）。 */
    private String loginOf(String username) throws Exception {
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }

    private Long userIdOf(String username) {
        SysUserPO user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserPO>().eq(SysUserPO::getUsername, username));
        assertThat(user).as("注册后应能查到用户 %s", username).isNotNull();
        return user.getId();
    }
}
