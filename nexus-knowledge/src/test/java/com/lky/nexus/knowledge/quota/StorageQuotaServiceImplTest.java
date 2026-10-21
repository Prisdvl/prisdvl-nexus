package com.lky.nexus.knowledge.quota;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 存储配额：判定口径与两条硬线。
 *
 * <p>要钉住的三件事：
 * <ol>
 *   <li><b>判定用"已用 + 这次要放进去的"，不是"已用"</b>。只比已用，1.9 GB 的库还能再塞 1.9 GB；</li>
 *   <li><b>上限配成 0 表示不限</b>，不能当成"上限为零 → 什么都放不下"；</li>
 *   <li><b>incomingBytes &lt;= 0 不去查库</b>：批量导入里有些条目就是 0 字节（空文件），
 *       为它们各查两次 SUM 是白花的往返。</li>
 * </ol>
 */
class StorageQuotaServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_ID = 1001L;
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;

    private DocumentMapper documentMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    }

    private StorageQuotaServiceImpl service(long totalLimit, long perKbLimit) {
        return new StorageQuotaServiceImpl(documentMapper, knowledgeBaseMapper, totalLimit, perKbLimit);
    }

    @Test
    @DisplayName("余量够时放行，且判定用的是「已用 + 新文件」")
    void allowWhenRoomIsEnough() {
        when(documentMapper.sumFileSizeByKb(KB_ID)).thenReturn(100 * MB);
        when(documentMapper.sumFileSizeByUser(USER_ID)).thenReturn(200 * MB);

        assertThatCode(() -> service(GB, GB).ensureRoom(USER_ID, KB_ID, 300 * MB, "a.pdf"))
                .doesNotThrowAnyException();

        verify(documentMapper).sumFileSizeByKb(KB_ID);
        verify(documentMapper).sumFileSizeByUser(USER_ID);
    }

    @Test
    @DisplayName("单库超限：报 7002，文案带上「超出多少」，且不再去查总账")
    void rejectWhenSingleKnowledgeBaseIsFull() {
        when(documentMapper.sumFileSizeByKb(KB_ID)).thenReturn(900 * MB);

        assertThatThrownBy(() -> service(GB, GB).ensureRoom(USER_ID, KB_ID, 200 * MB, "big.pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("big.pdf")
                .hasMessageContaining("超出")
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.QUOTA_EXHAUSTED.getCode()));

        verify(documentMapper, never()).sumFileSizeByUser(anyLong());
    }

    @Test
    @DisplayName("账号总量超限：单库还有余量也要拦")
    void rejectWhenAccountTotalIsFull() {
        when(documentMapper.sumFileSizeByKb(KB_ID)).thenReturn(10 * MB);
        when(documentMapper.sumFileSizeByUser(USER_ID)).thenReturn(GB - 1);

        assertThatThrownBy(() -> service(GB, 4 * GB).ensureRoom(USER_ID, KB_ID, 10 * MB, "b.pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存储空间已用")
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.QUOTA_EXHAUSTED.getCode()));
    }

    @Test
    @DisplayName("上限配 0 表示不限：满了也不拦")
    void zeroLimitMeansUnlimited() {
        assertThatCode(() -> service(0, 0).ensureRoom(USER_ID, KB_ID, 999 * GB, "c.pdf"))
                .doesNotThrowAnyException();
        verifyNoInteractions(documentMapper);
    }

    @Test
    @DisplayName("0 / 负数字节不查库直接放行（空文件不该产生两次 SUM 往返）")
    void skipForNonPositiveBytes() {
        assertThatCode(() -> service(GB, GB).ensureRoom(USER_ID, KB_ID, 0, "empty.txt"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service(GB, GB).ensureRoom(USER_ID, KB_ID, -1, "weird.txt"))
                .doesNotThrowAnyException();
        verifyNoInteractions(documentMapper);
    }

    @Test
    @DisplayName("usage()：用量、条数、库数与两条上限原样带出")
    void reportUsage() {
        when(documentMapper.sumFileSizeByUser(USER_ID)).thenReturn(123 * MB);
        when(documentMapper.countByUser(USER_ID)).thenReturn(4L);
        when(knowledgeBaseMapper.countByUser(USER_ID)).thenReturn(2L);

        StorageUsage usage = service(GB, 500 * MB).usage(USER_ID);

        assertThat(usage.usedBytes()).isEqualTo(123 * MB);
        assertThat(usage.documentCount()).isEqualTo(4L);
        assertThat(usage.knowledgeBaseCount()).isEqualTo(2L);
        assertThat(usage.totalLimitBytes()).isEqualTo(GB);
        assertThat(usage.perKnowledgeBaseLimitBytes()).isEqualTo(500 * MB);
    }
}
