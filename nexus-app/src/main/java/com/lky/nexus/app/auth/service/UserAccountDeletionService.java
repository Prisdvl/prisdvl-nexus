package com.lky.nexus.app.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.agent.mapper.AgentMapper;
import com.lky.nexus.app.agent.mapper.AgentRunMapper;
import com.lky.nexus.app.agent.model.entity.AgentPO;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.SysUserSessionMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.model.entity.SysUserSessionPO;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.llm.UserEmbeddingConfigMapper;
import com.lky.nexus.app.llm.UserEmbeddingConfigPO;
import com.lky.nexus.app.llm.UserLlmConfigMapper;
import com.lky.nexus.app.llm.UserLlmConfigPO;
import com.lky.nexus.app.workflow.mapper.WorkflowMapper;
import com.lky.nexus.app.workflow.mapper.WorkflowRunMapper;
import com.lky.nexus.app.workflow.mapper.WorkflowVersionMapper;
import com.lky.nexus.app.workflow.model.entity.WorkflowPO;
import com.lky.nexus.app.workflow.model.entity.WorkflowRunPO;
import com.lky.nexus.app.workflow.model.entity.WorkflowVersionPO;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销当前账号：软删账号本体，硬删其业务数据。
 *
 * <p><b>为什么要软删账号</b>：审计表（{@code ai_tool_invocation} / {@code ai_tool_approval} /
 * {@code kb_qa_record}）里留着「谁在什么时候让模型干了什么」的证据。整行删掉等于用户一句话
 * 抹掉审计痕迹，所以 {@code sys_user} 只置 {@code status=0 / deleted=1}，并把
 * {@code username} / {@code email} 改写掉——既让账号不可登录、也让用户名与邮箱可以被重新注册。
 *
 * <p><b>范围</b>：会话、模型/嵌入凭据、对话与消息、Agent 与运行、工作流（含版本与运行）。
 * 知识库不在其中：删库必须走 {@code KnowledgeBaseService.delete}（要同时清 pgvector 与 ES 索引），
 * 而按用户枚举知识库需要它的查询服务——这一段单独做，见 {@code docs/roadmap.md}。
 *
 * <p>整个方法在一个事务里；任何一步失败整体回滚，不会留下「账号删了、数据还在」的半成品。
 */
@Service
public class UserAccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountDeletionService.class);

    private final SysUserMapper sysUserMapper;
    private final SysUserSessionMapper sessionMapper;
    private final UserLlmConfigMapper llmConfigMapper;
    private final UserEmbeddingConfigMapper embeddingConfigMapper;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final AgentRunMapper agentRunMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowRunMapper workflowRunMapper;

    public UserAccountDeletionService(
            SysUserMapper sysUserMapper,
            SysUserSessionMapper sessionMapper,
            UserLlmConfigMapper llmConfigMapper,
            UserEmbeddingConfigMapper embeddingConfigMapper,
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AgentMapper agentMapper,
            AgentRunMapper agentRunMapper,
            WorkflowMapper workflowMapper,
            WorkflowVersionMapper workflowVersionMapper,
            WorkflowRunMapper workflowRunMapper) {
        this.sysUserMapper = sysUserMapper;
        this.sessionMapper = sessionMapper;
        this.llmConfigMapper = llmConfigMapper;
        this.embeddingConfigMapper = embeddingConfigMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.agentMapper = agentMapper;
        this.agentRunMapper = agentRunMapper;
        this.workflowMapper = workflowMapper;
        this.workflowVersionMapper = workflowVersionMapper;
        this.workflowRunMapper = workflowRunMapper;
    }

    /**
     * 注销账号。
     *
     * @param userId 只来自 JWT（{@code CurrentUser.requireId()}），不接受请求参数
     */
    @Transactional
    public void deleteAccount(Long userId) {
        SysUserPO user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        // 顺序：先删「引用别人的」再删「被引用的」，避免留下悬挂外键
        messageMapper.delete(Wrappers.<ChatMessagePO>lambdaQuery()
                .inSql(ChatMessagePO::getConversationId,
                        "select id from chat_conversation where user_id = " + userId));
        conversationMapper.delete(Wrappers.<ChatConversationPO>query().eq("user_id", userId));

        agentRunMapper.delete(Wrappers.<AgentRunPO>query().eq("user_id", userId));
        agentMapper.delete(Wrappers.<AgentPO>query().eq("user_id", userId));

        workflowRunMapper.delete(Wrappers.<WorkflowRunPO>query().eq("user_id", userId));
        workflowVersionMapper.delete(Wrappers.<WorkflowVersionPO>lambdaQuery()
                .inSql(WorkflowVersionPO::getWorkflowId,
                        "select id from wf_workflow where user_id = " + userId));
        workflowMapper.delete(Wrappers.<WorkflowPO>query().eq("user_id", userId));

        // 凭据是密文，没有任何保留价值；会话必须立刻失效
        llmConfigMapper.delete(Wrappers.<UserLlmConfigPO>query().eq("user_id", userId));
        embeddingConfigMapper.delete(Wrappers.<UserEmbeddingConfigPO>query().eq("user_id", userId));
        sessionMapper.delete(Wrappers.<SysUserSessionPO>query().eq("user_id", userId));

        // 账号本体：软删 + 释放用户名与邮箱（唯一索引不允许它们被永久占用）
        String releasedName = releaseName(user.getUsername(), userId);
        sysUserMapper.update(null, Wrappers.<SysUserPO>update()
                .eq("id", userId)
                .set("status", 0)
                .set("deleted", 1)
                .set("username", releasedName)
                .set("email", null)
                .set("updated_at", new Date()));

        log.info("账号已注销 userId={}（软删账号，业务数据已清理）", userId);
    }

    /** 保留原用户名便于审计追溯，但长度受 {@code VARCHAR(64)} 限制。 */
    private String releaseName(String username, Long userId) {
        String suffix = "#deleted#" + userId;
        String base = username == null ? "user" : username;
        int keep = Math.max(1, 64 - suffix.length());
        return (base.length() > keep ? base.substring(0, keep) : base) + suffix;
    }
}
