package com.lky.nexus.app.chat.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lky.nexus.app.infra.mybatis.JsonTextTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表实体（chat_conversation）。
 *
 * <p>字段类型说明：{@code mode} 用 {@code String} 而不是 Java 枚举。
 * 原因是 MyBatis 对枚举的默认处理器在不同版本/配置下行为不一致
 * （{@code EnumTypeHandler} 按名字、{@code MybatisEnumTypeHandler} 按 {@code @EnumValue}），
 * 这属于"隐式魔法"。这里选择在实体层用字符串、在服务层做枚举转换：
 * 类型安全留在 API/Service 边界，持久化层保持最直白的行为。
 */
@Data
@TableName(value = "chat_conversation", autoResultMap = true)
public class ChatConversationPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户：所有查询必须带这个条件（多租户隔离） */
    private Long userId;

    /** 会话标题；为空时由首轮用户消息截断生成 */
    private String title;

    /** 标题是否为自动生成（用户改过之后就置为 false，避免后续被覆盖） */
    private Boolean titleAuto;

    /** 会话模式：PLAIN / RAG / AGENT */
    private String mode;

    /**
     * 绑定的知识库 id 列表（JSON 数组字符串）；Phase 3 起启用
     *
     * <p><b>为什么必须挂 {@link com.lky.nexus.app.infra.mybatis.JsonTextTypeHandler}</b>
     * （实测踩坑，不是推测）：
     * H2 的 {@code json} 列把「写入的 JSON 文本」存成<b>字符串节点</b>
     * （写 {@code ["1"]} 读回来是 {@code "[\"1\"]"}）。
     * 而 {@code updateById} 走「整行读出 → 改字段 → 全列写回」，
     * 于是未改动的 {@code kbIds} 也会被把已转义的形态<b>再写回一次</b>，
     * 变成 {@code "\"[\\\"1\\\"]\""} —— 每 update 一次多一层嵌套，
     * 表现为「改一下标题，知识库绑定就没了」。
     *
     * <p>该处理器只在<b>读</b>侧解开这一层，写入侧原样透传，
     * 因此 MySQL（读回来本就是数组）行为不变。
     *
     * <p>⚠️ 依赖 {@code @TableName(autoResultMap = true)}：
     * 没有它，MyBatis-Plus 不会为该字段启用 resultMap 中的 typeHandler。
     */
    @TableField(typeHandler = JsonTextTypeHandler.class)
    private String kbIds;

    /**
     * 该会话选用的 Agent（Phase 4.1）；仅 AGENT 模式有值。
     *
     * <p>为 {@code null} 表示"用内置默认 Agent"——不落一个具体 id，是为了让
     * "默认"这件事只有一处定义（{@code AgentService.resolveDefault}），
     * 而不是把某一次解析出来的 id 固化进历史会话。
     *
     * <p><b>为什么必须显式声明 {@code updateStrategy = ALWAYS}</b>（实测踩坑）：
     * MyBatis-Plus 默认策略是 {@code FieldStrategy.NOT_NULL}，
     * {@code updateById} 会<b>跳过 null 字段</b>，于是
     * {@code conversation.setAgentId(null)} 根本不生成 SQL ——
     * 接口返回 200、响应里 {@code agentId} 已是 null，但库里还是旧值。
     *这是"看起来成功、实际没生效"的典型假成功（用户以为换回了默认助手，
     * 刷新就打回原形），且现有测试只断言 HTTP 响应、没回查数据库，所以一直漏着。
     *
     * <p>同表里的 {@code kbIds} 用<b>空串</b>（JSON {@code "[]"}）规避同一个问题，
     * 见 {@code ConversationServiceImpl.resolveKbIds} 的注释。两种写法都能达到目的，
     * 这里选注解而不是空串，因为 {@code null} 本身是<b>合法且有意义</b>的状态
     * （"用内置默认 Agent"），哨兵值会把这个语义搅浑。
     *
     * <p><b>部分更新安全性</b>：{@code ConversationServiceImpl.update} 是本字段唯一的
     * 写入点，且它总是先把 {@code conversation} 从库里整行读出来再改，
     * 所以"未提供 agentId"时这里写回的是<b>库里的原值</b>，不会被误清空；
     * 而真正要清空时一定有 {@code resetAgent=true} 这个显式信号。
     * 另一处 {@code updateById} 是逻辑删除（{@code delete}），不碰 agentId。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long agentId;

    /** 该会话使用的模型逻辑角色，如 chat.default */
    private String modelRole;

    /** Phase 9 起绑定提示词版本 */
    private Long promptTemplateId;

    /** 冗余计数：追加消息时同步维护，避免列表页对消息表做 count 查询 */
    private Integer messageCount;

    /** 最后一条消息时间：会话列表按它倒序 */
    private LocalDateTime lastMessageAt;

    /** 是否置顶 */
    private Boolean pinned;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
