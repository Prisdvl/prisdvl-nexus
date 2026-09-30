package com.lky.nexus.app.workspace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

/**
 * 工作区服务（Phase 2 最小版）。
 *
 * <p>入口：{@link #requirePersonalWorkspace(Long)}——取用户的个人工作区，缺失时创建。
 * 知识库等资源创建时由 app 层控制器用它解析 workspaceId 传给领域服务，
 * 这样 knowledge 层不依赖 app 的 workspace（依赖方向不被破坏）。
 */
@Service
public class WorkspaceService {

    /** 个人工作区类型。 */
    public static final String TYPE_PERSONAL = "PERSONAL";

    private final WorkspaceMapper workspaceMapper;

    public WorkspaceService(WorkspaceMapper workspaceMapper) {
        this.workspaceMapper = workspaceMapper;
    }

    /** 取个人工作区；缺失（例如历史数据、直接造库的测试）则补齐一个，幂等。 */
    public Long requirePersonalWorkspace(Long userId) {
        WorkspacePO existing = workspaceMapper.selectOne(
                Wrappers.<WorkspacePO>lambdaQuery()
                        .eq(WorkspacePO::getUserId, userId)
                        .eq(WorkspacePO::getType, TYPE_PERSONAL)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        WorkspacePO workspace = new WorkspacePO();
        workspace.setUserId(userId);
        workspace.setName("个人工作区");
        workspace.setDescription("默认工作区（注册时自动创建）");
        workspace.setType(TYPE_PERSONAL);
        workspace.setCreatedAt(java.time.LocalDateTime.now());
        workspace.setUpdatedAt(java.time.LocalDateTime.now());
        workspace.setDeleted(0);
        workspaceMapper.insert(workspace);
        return workspace.getId();
    }
}