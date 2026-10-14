package com.lky.nexus.app.auth;

import com.lky.nexus.app.auth.email.EmailCodeService;
import com.lky.nexus.app.auth.email.EmailPurpose;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.service.AuthService;
import com.lky.nexus.app.auth.service.EmailVerificationService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验邮服务失败策略：落库失败不得向客户端宣称已验证成功。
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private EmailCodeService emailCodeService;
    @Mock
    private AuthService authService;

    @InjectMocks
    private EmailVerificationService service;

    @Test
    @DisplayName("落库抛异常：不返回成功；验证码已消费，需重新发送")
    void dbUpdateFailureDoesNotClaimSuccess() {
        SysUserPO user = new SysUserPO();
        user.setId(1L);
        user.setEmail("a@b.com");
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        doNothing().when(emailCodeService).verify(eq("a@b.com"), eq(EmailPurpose.VERIFY_EMAIL), eq("123456"));
        when(sysUserMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.verify(1L, "123456"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
                    assertThat(be.getMessage()).contains("重新获取");
                });

        verify(authService, never()).currentUser(any());
    }

    @Test
    @DisplayName("落库 rows≠1（邮箱已变）：PARAM_INVALID，不宣称成功")
    void zeroRowsDoesNotClaimSuccess() {
        SysUserPO user = new SysUserPO();
        user.setId(1L);
        user.setEmail("a@b.com");
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        doNothing().when(emailCodeService).verify(anyString(), eq(EmailPurpose.VERIFY_EMAIL), anyString());
        when(sysUserMapper.update(ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(0);

        assertThatThrownBy(() -> service.verify(1L, "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());

        verify(authService, never()).currentUser(any());
    }

    @Test
    @DisplayName("SMTP/发信失败向上抛出，接口层不会得到成功")
    void sendFailurePropagates() {
        SysUserPO user = new SysUserPO();
        user.setId(1L);
        user.setEmail("a@b.com");
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试"))
                .when(emailCodeService).send(eq("a@b.com"), eq(EmailPurpose.VERIFY_EMAIL), eq("127.0.0.1"));

        assertThatThrownBy(() -> service.send(1L, "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
    }
}
