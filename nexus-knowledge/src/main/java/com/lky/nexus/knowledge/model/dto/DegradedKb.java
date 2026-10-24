package com.lky.nexus.knowledge.model.dto;

/**
 * 本次检索里降级（失败）的知识库。
 *
 * <p><b>为什么要把失败单独返回而不是整体 500</b>：跨库检索是并发扇出，
 * 库与库之间彼此独立。一个库的索引炸了，不该让用户连其它库的资料都看不到——
 * 但也不能假装什么都没发生，否则用户会以为"这个库里就是没有"。
 *
 * @param kbId   库 id
 * @param kbName 库名（失败时也要能让用户认出来是哪一个）
 * @param reason 失败原因（已脱敏的业务描述，直接可展示）
 */
public record DegradedKb(Long kbId, String kbName, String reason) {
}
