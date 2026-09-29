package com.lky.nexus.app.chat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("chat_conversation")
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

    /** 绑定的知识库 id 列表（JSON 数组字符串）；Phase 3 起启用 */
    private String kbIds;

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
