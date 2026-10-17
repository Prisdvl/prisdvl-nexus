package com.lky.nexus.app.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.agent.mapper.ToolGrantMapper;
import com.lky.nexus.app.agent.model.entity.ToolGrantPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户级长期工具授权（{@code ai_tool_grant}，P0 §八）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li>只保存 {@code ALWAYS}——{@code once} 是"这一次放行"，{@code deny} 是"这一次拒绝"，
 *       两者落库都会让"下次不再问"或"永久禁止"这种副作用在用户没表达时发生；</li>
 *   <li>只能为**已注册且启用**的工具授权——给一个不存在的 code 授权，等于埋下一颗
 *       "将来有人注册了同名工具，授权立刻生效"的雷；</li>
 *   <li>授权只对当前用户生效（唯一键 {@code (user_id, tool_code)}，查询一律带 user_id）。</li>
 * </ol>
 */
@Service
public class ToolGrantService {

    private static final Logger log = LoggerFactory.getLogger(ToolGrantService.class);

    /** 长期授权的唯一合法 scope（与 V22 的列注释一致）。 */
    public static final String SCOPE_ALWAYS = "ALWAYS";

    private final ToolGrantMapper grantMapper;
    private final ToolRegistrationService registrationService;

    public ToolGrantService(ToolGrantMapper grantMapper, ToolRegistrationService registrationService) {
        this.grantMapper = grantMapper;
        this.registrationService = registrationService;
    }

    /** 当前用户的全部授权（按工具 code 排序，便于界面稳定展示）。 */
    public List<ToolGrantPO> list(Long userId) {
        return grantMapper.selectList(Wrappers.<ToolGrantPO>lambdaQuery()
                .eq(ToolGrantPO::getUserId, userId)
                .orderByAsc(ToolGrantPO::getToolCode));
    }

    public boolean hasAlways(Long userId, String toolCode) {
        return grantMapper.selectCount(Wrappers.<ToolGrantPO>lambdaQuery()
                .eq(ToolGrantPO::getUserId, userId)
                .eq(ToolGrantPO::getToolCode, toolCode)
                .eq(ToolGrantPO::getScope, SCOPE_ALWAYS)) > 0;
    }

    /**
     * 幂等写入 always 授权。
     *
     * <p>先查后写 + 捕获唯一键冲突兜底：并发两次"始终允许"不该有一条失败，
     * 也不该留下两行（唯一键已经保证后者，捕获冲突只是把前者的报错变成成功）。
     */
    @Transactional
    public ToolGrantPO grantAlways(Long userId, String toolCode) {
        registrationService.requireRegistered(toolCode);
        ToolGrantPO existing = find(userId, toolCode);
        if (existing != null) {
            if (!SCOPE_ALWAYS.equals(existing.getScope())) {
                existing.setScope(SCOPE_ALWAYS);
                existing.setUpdatedAt(LocalDateTime.now());
                grantMapper.updateById(existing);
            }
            return existing;
        }
        ToolGrantPO row = new ToolGrantPO();
        row.setUserId(userId);
        row.setToolCode(toolCode);
        row.setScope(SCOPE_ALWAYS);
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            grantMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            // 并发下的另一条请求已经插入：把库里的那行回读出来，行为等价于"我这次也成功"
            log.debug("授权并发插入命中唯一键，回读既有行：userId={} tool={}", userId, toolCode);
            return find(userId, toolCode);
        }
        return row;
    }

    /**
     * 撤销单个授权；返回是否真的删掉了行。
     *
     * <p>幂等语义：重复撤销不报错，返回 false 只表示"本来就没有"。
     * 刻意<b>不</b>去中断任何正在执行的工具调用：撤销是对后续调用的约束，
     * 已经拿到授权的调用不该因为用户点了一下设置页而被半路掐断（§八）。
     */
    @Transactional
    public boolean revoke(Long userId, String toolCode) {
        return grantMapper.delete(Wrappers.<ToolGrantPO>lambdaQuery()
                .eq(ToolGrantPO::getUserId, userId)
                .eq(ToolGrantPO::getToolCode, toolCode)) > 0;
    }

    /** 撤销当前用户全部授权；返回删除条数（0 也是成功，幂等）。 */
    @Transactional
    public int revokeAll(Long userId) {
        return grantMapper.delete(Wrappers.<ToolGrantPO>lambdaQuery()
                .eq(ToolGrantPO::getUserId, userId));
    }

    private ToolGrantPO find(Long userId, String toolCode) {
        return grantMapper.selectOne(Wrappers.<ToolGrantPO>lambdaQuery()
                .eq(ToolGrantPO::getUserId, userId)
                .eq(ToolGrantPO::getToolCode, toolCode)
                .last("LIMIT 1"));
    }
}
