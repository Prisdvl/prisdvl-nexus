package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.model.entity.ToolRegistryPO;
import org.springframework.stereotype.Service;

/**
 * 工具执行前的授权判定（P0 §四）。
 *
 * <p>判定只有三步，顺序不能换：
 * <ol>
 *   <li>工具必须已注册且启用（否则 5011，默认拒绝）；</li>
 *   <li>权限从<b>数据库</b>读（不是代码声明、不是注解）——"运行时权威"就落在这里；</li>
 *   <li>需要审批的权限，只有在存在 {@code always} 授权时才放行。</li>
 * </ol>
 *
 * <p>它是 {@code ExecutingToolCallback} 唯一的判定来源：把这段逻辑写在调用点旁边，
 * 迟早会出现"某个 provider 的工具忘了检查"。
 */
@Service
public class ToolAuthorizationService {

    private final ToolRegistrationService registrationService;
    private final ToolGrantService grantService;

    public ToolAuthorizationService(ToolRegistrationService registrationService,
                                    ToolGrantService grantService) {
        this.registrationService = registrationService;
        this.grantService = grantService;
    }

    /**
     * 本次调用的授权结论。
     *
     * @param permission        数据库中实际生效的权限（审计里落的就是它）
     * @param requiresApproval  是否必须走人工审批（READ=false；WRITE/DANGEROUS 且无 always 授权=true）
     * @param registry          注册元数据（审批原因文案会用到名字）
     */
    public record Decision(ToolPermission permission, boolean requiresApproval, ToolRegistryPO registry) {
    }

    /** 非法/未注册/已禁用的工具在这里直接抛业务异常（调用方会把它变成 observation）。 */
    public Decision authorize(Long userId, String toolCode) {
        ToolRegistryPO registry = registrationService.requireRegistered(toolCode);
        ToolPermission permission = registrationService.requirePermission(toolCode);
        boolean requiresApproval = permission.requiresApproval() && !grantService.hasAlways(userId, toolCode);
        return new Decision(permission, requiresApproval, registry);
    }
}
