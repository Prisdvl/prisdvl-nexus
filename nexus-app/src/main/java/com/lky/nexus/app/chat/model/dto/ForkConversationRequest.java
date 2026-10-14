package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 会话分支请求（P1-3.7：从此处创建分支）。
 *
 * <p>分支 = 复制一条新会话：模式 / Agent / 知识库绑定与原会话一致，
 * 消息复制到 {@code beforeMessageId}（含）为止；该消息之后的内容不带入新会话。
 *
 * <p>{@code title} 由前端按「分支 / 分支1 / 分支2 …」的规则计算后显式传入
 * （前端持有当前用户的全部会话标题，能在一次交互内判定重名）；
 * 为空时服务端退回 1001，不在这里替用户猜名字。
 *
 * <p>{@code beforeMessageId} 可空：空表示复制原会话的全部消息。
 */
public record ForkConversationRequest(

        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        /** 复制到哪条消息为止（含该条）；必须属于被分支的原会话。 */
        Long beforeMessageId) {
}
