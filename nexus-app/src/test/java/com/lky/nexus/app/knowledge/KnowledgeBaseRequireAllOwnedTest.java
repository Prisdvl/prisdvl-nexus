package com.lky.nexus.app.knowledge;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code requireAllOwned}：多知识库检索的入口校验（Phase 5.5）。
 *
 * <p><b>为什么是集成测试而不是 Mockito 单测</b>：这个方法的核心恰恰是那条
 * {@code Wrappers.lambdaQuery()} 构造出来的 SQL 条件，而 lambda wrapper 要靠
 * MyBatis-Plus 的 TableInfo 缓存才能工作——那个缓存由 MyBatis-Plus 的启动过程填充，
 * 纯 Mockito 环境下连把条件造出来都做不到（会抛 "can not find lambda cache"）。
 * 换句话说，在这里"能跑起来"本身就意味着真的走了持久层。
 *
 * <p>而这正好让最关键的那条断言变得更硬：<b>跨租户隔离</b>直接用"数据库里真实存在、
 * 但属于别人的一行"来验，而不是去断言 SQL 字符串里有没有 {@code user_id}。
 * 后者只证明"我写了过滤条件"，前者才证明"这个条件是有效的"。
 *
 * <p>三个必须钉住的语义：
 * <ol>
 *   <li><b>不做部分成功</b>——悄悄跳过查不到的库，会让"绑了三个只搜到两个"变成没有报错的谜；</li>
 *   <li><b>错误信息指明是哪个 id，但不区分"不存在"与"是别人的"</b>——
 *       区分开就等于用错误信息确认了别人知识库的存在；</li>
 *   <li><b>按请求顺序返回并去重</b>——数据库返回顺序不保证，重复的库会白跑一轮检索
 *       并在归并结果里留下重复片段。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeBaseRequireAllOwnedTest {

    /** 高位 id：避开真实注册用户（注册走雪花 id，不会撞上来）。 */
    private static final Long OWNER = 9_000_001L;
    private static final Long STRANGER = 9_000_002L;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private Long kbA;
    private Long kbB;
    /** 真实存在于库里，但属于 STRANGER——越权断言的依据。 */
    private Long foreignKb;

    @BeforeEach
    void setUp() {
        kbA = insertKb(OWNER, "多库A");
        kbB = insertKb(OWNER, "多库B");
        foreignKb = insertKb(STRANGER, "别人的库");
    }

    @Test
    @DisplayName("按请求顺序返回，而不是数据库的返回顺序")
    void returnsInRequestOrder() {
        List<KnowledgeBasePO> result = knowledgeBaseService.requireAllOwned(OWNER, List.of(kbB, kbA));

        assertThat(result).extracting(KnowledgeBasePO::getId).containsExactly(kbB, kbA);
        assertThat(result).extracting(KnowledgeBasePO::getUserId).containsOnly(OWNER);
    }

    @Test
    @DisplayName("重复的库 id 去重且保序：同一库只返回一次")
    void deduplicatesPreservingOrder() {
        List<KnowledgeBasePO> result = knowledgeBaseService.requireAllOwned(OWNER, List.of(kbB, kbA, kbB));

        assertThat(result).extracting(KnowledgeBasePO::getId).containsExactly(kbB, kbA);
    }

    @Test
    @DisplayName("有一个库查不到 → 整批 3001，且错误信息指明是哪个 id")
    void missingIdFailsAndNamesIt() {
        long missing = 9_999_999_999L;

        assertThatThrownBy(() -> knowledgeBaseService.requireAllOwned(OWNER, List.of(kbA, missing)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode()))
                .hasMessageContaining(String.valueOf(missing));
    }

    @Test
    @DisplayName("别人的知识库：数据库里真实存在，但拿不到（SQL 层隔离，不是内存里筛）")
    void doesNotReturnOtherUsersKnowledgeBase() {
        assertThatThrownBy(() -> knowledgeBaseService.requireAllOwned(OWNER, List.of(foreignKb)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode()));
        // 反证：同一个 id 在它真正的主人那里是查得到的——所以上面失败不是因为"这行不存在"
        assertThat(knowledgeBaseService.requireAllOwned(STRANGER, List.of(foreignKb)))
                .extracting(KnowledgeBasePO::getId).containsExactly(foreignKb);
    }

    @Test
    @DisplayName("「不存在」与「存在但属于别人」的文案完全一致：只有 id 不同")
    void doesNotLeakExistence() {
        String foreign = messageOf(() -> knowledgeBaseService.requireAllOwned(OWNER, List.of(foreignKb)));
        long nonexistent = 9_999_999_998L;
        String absent = messageOf(() -> knowledgeBaseService.requireAllOwned(OWNER, List.of(nonexistent)));

        assertThat(foreign.replace(String.valueOf(foreignKb), "X"))
                .as("两条错误信息若只差 id，说明没有泄漏「对方存在」这个事实")
                .isEqualTo(absent.replace(String.valueOf(nonexistent), "X"));
    }

    @Test
    @DisplayName("空入参返回空列表（不抛异常）：\"没绑库\"由调用方决定是报错还是降级")
    void emptyInputReturnsEmpty() {
        assertThat(knowledgeBaseService.requireAllOwned(OWNER, List.of())).isEmpty();
        assertThat(knowledgeBaseService.requireAllOwned(OWNER, null)).isEmpty();
    }

    @Test
    @DisplayName("null 元素视为不合格的 id → 3001（不能把 null 塞进 IN 子句）")
    void nullElementIsRejected() {
        assertThatThrownBy(() ->
                knowledgeBaseService.requireAllOwned(OWNER, Arrays.asList(kbA, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("已软删的知识库查不到：@TableLogic 的过滤在批量路径上同样生效")
    void softDeletedKnowledgeBaseIsNotFound() {
        Long doomed = insertKb(OWNER, "待删除的库");
        knowledgeBaseMapper.deleteById(doomed);

        assertThatThrownBy(() -> knowledgeBaseService.requireAllOwned(OWNER, List.of(doomed)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode()));
    }

    // ------------------------------------------------------------------

    private Long insertKb(Long userId, String namePrefix) {
        KnowledgeBasePO entity = new KnowledgeBasePO();
        // 唯一键是 (user_id, name, deleted)：名字必须唯一，否则同一上下文里跨用例会撞键
        entity.setName(namePrefix + "-" + SEQ.incrementAndGet());
        entity.setUserId(userId);
        // 这三列 NOT NULL 且无默认值（ADR-008：嵌入模型建库时冻结）
        entity.setEmbeddingProvider("ollama");
        entity.setEmbeddingModel("bge-m3");
        entity.setEmbeddingDim(1024);
        knowledgeBaseMapper.insert(entity);
        return entity.getId();
    }

    private static String messageOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("预期抛出 BusinessException，但没有");
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }
}
