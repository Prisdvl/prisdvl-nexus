package com.lky.nexus.app.auth.model.dto;

import java.time.LocalDateTime;

/**
 * 当前用户的当日额度（5.6，只读）。
 *
 * <p><b>为什么要把配额做成可见的接口</b>：配额本身是个"隐形旋钮"——
 * 用户被 403 拦住之前完全不知道有这回事，被拦住之后也不知道自己用掉了多少、
 * 什么时候恢复。对一个会真实花钱的产品来说，"还剩多少"必须能被回答，
 * 否则用户唯一的体验就是"莫名其妙不能用了"。
 *
 * <p>字段刻意与前端展示一一对应，不做嵌套：{@code requestUsed/requestLimit} 是次数，
 * {@code tokenUsed/tokenLimit} 是 token 数，{@code resetAt} 是下一次重置的时刻（次日 00:00）。
 * <b>没有 userId 字段</b>：身份只从 JWT 的 {@code sub} 取（ADR-018）。
 */
public record QuotaResponse(
        long requestUsed,
        long requestLimit,
        long tokenUsed,
        long tokenLimit,
        LocalDateTime resetAt) {
}
