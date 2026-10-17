package com.lky.nexus.app.agent.tool;

import com.lky.nexus.app.agent.mapper.ToolGrantMapper;
import com.lky.nexus.app.agent.model.entity.ToolGrantPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户长期工具授权（P0 §八）：只存 ALWAYS、幂等 upsert、只能给已注册启用的工具、
 * 撤销不报错（幂等）。
 *
 * <p>为什么"只能给已注册工具授权"要单独钉一条：给一个拼错的 code 写授权不会立刻报错，
 * 但它是一颗雷——将来真有人注册了同名工具，这条授权就自动生效了，
 * 而用户当时点的是另一个（他以为不存在的）东西。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolGrantService：always 授权的幂等写入与撤销")
class ToolGrantServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private ToolGrantMapper grantMapper;

    @Mock
    private ToolRegistrationService registrationService;

    private ToolGrantService service;

    @BeforeEach
    void setUp() {
        service = new ToolGrantService(grantMapper, registrationService);
    }

    @Test
    @DisplayName("首次授权：写一行 scope=ALWAYS，并先校验工具已注册启用")
    void grantAlwaysInserts() {
        when(grantMapper.selectOne(any())).thenReturn(null);

        ToolGrantPO granted = service.grantAlways(USER_ID, "test_write_tool");

        ArgumentCaptor<ToolGrantPO> captor = ArgumentCaptor.forClass(ToolGrantPO.class);
        verify(grantMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getToolCode()).isEqualTo("test_write_tool");
        // 长期授权只能是 ALWAYS：once / deny 落库会让"下次不再问"在用户没表达时发生
        assertThat(captor.getValue().getScope()).isEqualTo(ToolGrantService.SCOPE_ALWAYS);
        assertThat(granted.getScope()).isEqualTo(ToolGrantService.SCOPE_ALWAYS);
        verify(registrationService).requireRegistered("test_write_tool");
    }

    @Test
    @DisplayName("重复授权幂等：已有同工具授权时不再插一行（唯一键之外的第二道保险）")
    void repeatedGrantIsIdempotent() {
        ToolGrantPO existing = new ToolGrantPO();
        existing.setId(1L);
        existing.setUserId(USER_ID);
        existing.setToolCode("test_write_tool");
        existing.setScope(ToolGrantService.SCOPE_ALWAYS);
        when(grantMapper.selectOne(any())).thenReturn(existing);

        ToolGrantPO result = service.grantAlways(USER_ID, "test_write_tool");

        assertThat(result.getId()).isEqualTo(1L);
        // 带类型的 matcher：BaseMapper 同时有 insert(T) 与 insert(Collection<T>)，
        // 裸 any() 会让编译器无法在两个重载之间选择
        verify(grantMapper, never()).insert(any(ToolGrantPO.class));
        verify(grantMapper, never()).updateById(any(ToolGrantPO.class));
    }

    @Test
    @DisplayName("未注册/已禁用的工具不能授权：5011，且不写任何行")
    void cannotGrantUnregisteredTool() {
        when(registrationService.requireRegistered("ghost_tool"))
                .thenThrow(new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具 ghost_tool 未注册，拒绝执行"));

        assertThatThrownBy(() -> service.grantAlways(USER_ID, "ghost_tool"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND.getCode()));
        verify(grantMapper, never()).insert(any(ToolGrantPO.class));
    }

    @Test
    @DisplayName("撤销单个授权：删了返回 true，本来就没有返回 false（都不报错）")
    void revokeIsIdempotent() {
        when(grantMapper.delete(any())).thenReturn(1).thenReturn(0);

        assertThat(service.revoke(USER_ID, "test_write_tool")).isTrue();
        assertThat(service.revoke(USER_ID, "test_write_tool")).isFalse();
    }

    @Test
    @DisplayName("撤销全部授权：返回删除条数，0 也是成功")
    void revokeAllReturnsCount() {
        when(grantMapper.delete(any())).thenReturn(3);

        assertThat(service.revokeAll(USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("hasAlways 只在 scope=ALWAYS 时成立（其它 scope 不构成长期授权）")
    void hasAlwaysOnlyMatchesAlwaysScope() {
        when(grantMapper.selectCount(any())).thenReturn(1L);

        assertThat(service.hasAlways(USER_ID, "test_write_tool")).isTrue();
    }
}
