package com.lky.nexus.app.chat.service;

/**
 * 会话记忆的 key 约定。
 *
 * <p><b>为什么是 {@code conv:{conversationId}} 这么朴素的形态</b>——这来自一次实测踩坑：
 * Spring AI 的 {@code SPRING_AI_CHAT_MEMORY} 建表脚本把 {@code conversation_id} 定义为
 * <b>{@code VARCHAR(36)}</b>（刚好放得下一个 UUID）。最初的实现是
 * {@code user:{userId}:conv:{conversationId}}，对 6 位用户 id + 19 位雪花 id 恰好 36 个字符，
 * <b>正好卡在边界上</b>——用户 id 位数再多一点就会以数据库异常的形式在运行期炸掉。
 *
 * <p>纠正方式：
 * <ol>
 *   <li><b>去掉 user 维度</b>：会话 id 是雪花 ID、全局唯一，归属校验在服务层已做
 *       （{@code requireOwned}）。多拼一个 user 前缀并不能提供真正额外的保护，
 *       却引入了"列宽溢出"这个真实故障模式——典型地<b>看起来更安全、实际更脆弱</b>。</li>
 *   <li><b>用测试钉住长度不变量</b>（而不是加一段永远走不到的长度守卫）：
 *       当前格式的最大长度是 {@code "conv:"} + 19 位 id = 24 字符，安全余量充足。
 *       如果将来有人想重新引入 user 维度，测试会提醒他先确认列宽。</li>
 * </ol>
 */
public final class ChatMemoryKeys {

    /**
     * Spring AI 建表脚本中 {@code conversation_id} 的宽度。
     *
     * <p>当前 key 格式的最大长度：{@code "conv:".length() + 19 = 24}（Long.MAX_VALUE 为 19 位）。
     */
    public static final int MAX_KEY_LENGTH = 36;

    private static final String PREFIX = "conv:";

    private ChatMemoryKeys() {
    }

    /**
     * 构造会话记忆的 key（{@code conv:{conversationId}}）。
     *
     * @param conversationId 会话 id；最大长度受 {@link #MAX_KEY_LENGTH} 约束
     * @return 记忆存储 key
     */
    public static String of(Long conversationId) {
        return PREFIX + conversationId;
    }
}
