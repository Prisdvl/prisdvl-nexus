package com.lky.nexus.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.service.impl.ChunkIndexServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引重建的<b>作用域与顺序</b>测试（Mockito，不依赖 Elasticsearch）。
 *
 * <p>这两件事都属于"错了也不会报错"的类型，因此必须被专门钉住：
 * <ul>
 *   <li><b>作用域</b>：重建只重置本知识库的切片。早先重建 KB-A 会把所有知识库共用的那份
 *       ES 索引一起清空，而 B/C 的切片状态仍是 {@code DONE}、不会被重新索引，
 *       于是 B/C 的 BM25 检索静默失效；</li>
 *   <li><b>顺序</b>：先重置状态、再重建索引。反过来的话，重建中途失败会留下
 *       "切片标着已重建、索引其实是空的"——同样是静默失效。</li>
 * </ul>
 *
 * <p>索引真实隔离由 {@code LiveChunkIndexTest#recreateIndexOnlyAffectsItsOwnKnowledgeBase}
 * 在真 ES 上验证；这里验证的是服务层把正确的参数传给了端口。
 */
class ChunkIndexServiceRecreateTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_ID = 1001L;
    private static final Long OTHER_KB_ID = 1002L;

    private KnowledgeBaseService knowledgeBaseService;
    private ChunkMapper chunkMapper;
    private DocumentMapper documentMapper;
    private ChunkIndex chunkIndex;

    private ChunkIndexService service;

    @BeforeEach
    void setUp() {
        // MyBatis-Plus 的 LambdaUpdateWrapper 需要实体元数据缓存（正常由运行时扫描注册）。
        // 纯单元测试里必须手工初始化，否则构造 wrapper 会抛 "can not find lambda cache for this entity"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChunkPO.class);

        knowledgeBaseService = mock(KnowledgeBaseService.class);
        chunkMapper = mock(ChunkMapper.class);
        documentMapper = mock(DocumentMapper.class);
        chunkIndex = mock(ChunkIndex.class);

        KnowledgeBasePO knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);

        service = new ChunkIndexServiceImpl(knowledgeBaseService, chunkMapper, documentMapper,
                chunkIndex, mock(JsonMapper.class));
    }

    @Test
    @DisplayName("重建：只重置本知识库的切片，且只重建本知识库的索引")
    void recreatesOnlyItsOwnKnowledgeBase() {
        service.recreateIndex(USER_ID, KB_ID);

        // 端口被调用时带的是本库 id——这正是"重建 A 不会波及 B"在服务层的落点
        verify(chunkIndex).recreateIndex(KB_ID);
        verify(chunkIndex, never()).recreateIndex(OTHER_KB_ID);

        AbstractWrapper<?, ?, ?> wrapper = capturedUpdateWrapper();
        assertThat(wrapper.getSqlSegment())
                .as("重置条件必须限制在 kb_id 上，否则会波及别的知识库")
                .contains("kb_id");
        assertThat(wrapper.getParamNameValuePairs())
                .as("重置成 PENDING，且过滤值是本库 id")
                .containsValue("PENDING")
                .containsValue(KB_ID);
    }

    @Test
    @DisplayName("先重置切片状态、再重建索引：重建失败时切片不会留在 DONE")
    void resetsStatusBeforeRecreatingSoAFailureSelfHeals() {
        doThrow(new IllegalStateException("ES 挂了")).when(chunkIndex).recreateIndex(KB_ID);

        assertThatThrownBy(() -> service.recreateIndex(USER_ID, KB_ID))
                .isInstanceOf(IllegalStateException.class);

        // 状态已被重置：下次「建索引」会按 chunkId 幂等重写一遍，故障自愈。
        // 若顺序反过来（先重建后重置），这里就不会有 update，切片会永远停在 DONE。
        verify(chunkMapper).update(isNull(), any());

        InOrder order = inOrder(chunkMapper, chunkIndex);
        order.verify(chunkMapper).update(isNull(), any());
        order.verify(chunkIndex).recreateIndex(KB_ID);
    }

    @Test
    @DisplayName("越权：既不动索引、也不动切片状态")
    void rejectsForeignKnowledgeBaseWithoutTouchingAnything() {
        when(knowledgeBaseService.requireOwned(USER_ID, OTHER_KB_ID))
                .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        assertThatThrownBy(() -> service.recreateIndex(USER_ID, OTHER_KB_ID))
                .isInstanceOf(BusinessException.class);

        verify(chunkIndex, never()).recreateIndex(any());
        verify(chunkMapper, never()).update(isNull(), any());
    }

    @SuppressWarnings("unchecked")
    private AbstractWrapper<?, ?, ?> capturedUpdateWrapper() {
        ArgumentCaptor<Wrapper<ChunkPO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chunkMapper).update(isNull(), captor.capture());
        return (AbstractWrapper<?, ?, ?>) captor.getValue();
    }
}
