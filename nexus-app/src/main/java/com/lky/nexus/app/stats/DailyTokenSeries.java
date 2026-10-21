package com.lky.nexus.app.stats;

import java.util.List;

/**
 * 当前用户最近一段时间的逐日 Token 消耗（热力图数据源）。
 *
 * <p>为什么单独开接口而不是把窗口塞进 {@code /api/stats/summary}：汇总接口是工作台首屏
 * 的固定形状（14 / 30 天），把它拉长到 365 天等于让每个打开工作台的人都多读 11 个月的
 * 消息行；用量页只有它自己需要整年，单独一条路径更省。
 *
 * <p>{@code days} 是<b>连续</b>日轴：没有消息的日子也有一项，{@code tokens = 0}。
 * 稀疏返回会让前端热力图把"没有那天"画成"那天不存在"。{@code from} / {@code to} 是
 * 窗口两端（ISO 日期，闭区间），前端据此知道这串数据代表哪一段。
 *
 * <p>{@code tokens} 是字节里 {@code chat_message.total_tokens} 按自然日求和的合计，
 * 口径与 {@code /summary} 的 {@code tokenHeat} 一致，只是窗口更长；流式中断、未写回
 * 用量的消息没有 {@code total_tokens}，按 0 计（不是按估算值补齐，别把估算混进账单）。
 */
public record DailyTokenSeries(String from, String to, List<DayTokens> days) {

    /** 某一天的 Token 合计。{@code day} 为 {@code YYYY-MM-DD}。 */
    public record DayTokens(String day, long tokens) {
    }
}
