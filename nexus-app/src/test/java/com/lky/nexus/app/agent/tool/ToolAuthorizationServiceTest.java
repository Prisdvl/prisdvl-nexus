package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.model.entity.ToolRegistryPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 工具授权判定（P0 §四）：<b>权限从数据库读，READ 直接放行，WRITE/DANGEROUS 必须审批</b>。
 *
 * <p>用 Mockito 而不是 Spring 上下文：这里要钉的是那三步判定的<b>顺序与分支</b>
 * （未注册先拒 → 权限从库读 → 需要审批的看 always 授权），与数据库无关。
 * 真实读写库由 {@code ToolApprovalFlowTest} 覆盖。
 *
 * <p>为什么每条 WRITE/DANGEROUS 断言都要有对应的"有 always 授权"对照组：
 * 只看"WRITE 需要审批"的话，把 {@code requiresApproval} 写成恒 true 也能过；
 * 加上 always 组，才能证明授权这条路真的被读到了。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolAuthorizationService：权限判定与授权放行")
class ToolAuthorizationServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private ToolRegistrationService registrationService;

    @Mock
    private ToolGrantService grantService;

    private ToolAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new ToolAuthorizationService(registrationService, grantService);
    }

    @Test
    @DisplayName("READ：直接执行，不需要审批（检索与计算不该打断用户）")
    void readToolNeedsNoApproval() {
        stubRegistered("knowledge_search", ToolPermission.READ);

        ToolAuthorizationService.Decision decision = service.authorize(USER_ID, "knowledge_search");

        assertThat(decision.permission()).isEqualTo(ToolPermission.READ);
        assertThat(decision.requiresApproval()).isFalse();
    }

    @Test
    @DisplayName("WRITE：没有 always 授权时必须审批")
    void writeToolRequiresApprovalWithoutGrant() {
        stubRegistered("test_write_tool", ToolPermission.WRITE);
        when(grantService.hasAlways(USER_ID, "test_write_tool")).thenReturn(false);

        assertThat(service.authorize(USER_ID, "test_write_tool").requiresApproval()).isTrue();
    }

    @Test
    @DisplayName("DANGEROUS：没有 always 授权时同样必须审批（不能按 ==WRITE 特判漏掉它）")
    void dangerousToolRequiresApprovalWithoutGrant() {
        stubRegistered("test_dangerous_tool", ToolPermission.DANGEROUS);

        assertThat(service.authorize(USER_ID, "test_dangerous_tool").requiresApproval()).isTrue();
    }

    @Test
    @DisplayName("WRITE/DANGEROUS + always 授权：不再询问（授权是唯一能跳过审批的东西）")
    void alwaysGrantSkipsApproval() {
        stubRegistered("test_write_tool", ToolPermission.WRITE);
        stubRegistered("test_dangerous_tool", ToolPermission.DANGEROUS);
        when(grantService.hasAlways(USER_ID, "test_write_tool")).thenReturn(true);
        when(grantService.hasAlways(USER_ID, "test_dangerous_tool")).thenReturn(true);

        // 权限本身不变（仍然是 WRITE/DANGEROUS，审计里要如实记录），只是不再需要人工确认
        ToolAuthorizationService.Decision write = service.authorize(USER_ID, "test_write_tool");
        ToolAuthorizationService.Decision dangerous = service.authorize(USER_ID, "test_dangerous_tool");

        assertThat(write.permission()).isEqualTo(ToolPermission.WRITE);
        assertThat(write.requiresApproval()).isFalse();
        assertThat(dangerous.permission()).isEqualTo(ToolPermission.DANGEROUS);
        assertThat(dangerous.requiresApproval()).isFalse();
    }

    @Test
    @DisplayName("授权是按用户判定的：别人的 always 不该让我的调用免审")
    void grantIsPerUser() {
        stubRegistered("test_write_tool", ToolPermission.WRITE);
        // 只有 USER_ID 有授权；查询一律带 userId，因此另一个用户拿不到这条授权
        when(grantService.hasAlways(USER_ID, "test_write_tool")).thenReturn(true);
        when(grantService.hasAlways(7L, "test_write_tool")).thenReturn(false);

        assertThat(service.authorize(USER_ID, "test_write_tool").requiresApproval()).isFalse();
        assertThat(service.authorize(7L, "test_write_tool").requiresApproval())
                .as("别人被授予的 always 不能让我免审：判定必须只查当前用户")
                .isTrue();
    }

    @Test
    @DisplayName("未注册 / 已禁用：5011 默认拒绝（不进入审批，也不执行）")
    void unregisteredOrDisabledIsRejected() {
        when(registrationService.requireRegistered("ghost_tool"))
                .thenThrow(new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具 ghost_tool 未注册，拒绝执行"));

        assertThatThrownBy(() -> service.authorize(USER_ID, "ghost_tool"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("权限值不可识别：按未注册处理（默认拒绝），而不是当成 READ 放行")
    void unparsablePermissionIsRejected() {
        when(registrationService.requireRegistered("weird_tool"))
                .thenReturn(new ToolRegistryPO());
        when(registrationService.requirePermission("weird_tool"))
                .thenThrow(new BusinessException(ErrorCode.TOOL_NOT_FOUND, "权限配置无法识别，已按默认拒绝处理"));

        assertThatThrownBy(() -> service.authorize(USER_ID, "weird_tool"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND.getCode()));
    }

    private void stubRegistered(String code, ToolPermission permission) {
        ToolRegistryPO row = new ToolRegistryPO();
        row.setCode(code);
        row.setPermission(permission.name());
        row.setEnabled(true);
        when(registrationService.requireRegistered(code)).thenReturn(row);
        when(registrationService.requirePermission(code)).thenReturn(permission);
    }
}
