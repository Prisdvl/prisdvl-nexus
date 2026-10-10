package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改会话请求（局部更新：为 null 的字段不修改）。
 *
 * <p><b>局部更新约定</b>：所有字段"没传"和"显式清空"是两个不同的意思，
 * 用两个独立的信号表达：
 * <ul>
 *   <li>{@code null} = 不改（连字段名都没出现时也是这个意思）</li>
 *   <li>{@code kbIds} 传空列表 = 解绑</li>
 *   <li>{@code agentId} 传 null 做不到清空（和"不改"分不清），
 *       所以清空用独立的 {@code resetAgent} 信号</li>
 * </ul>
 *
 * <h2>模式切换（本轮新增，替代"必须新建会话"的旧约束）</h2>
 *
 * <p><b>设计决策已变更</b>。此前这里<b>刻意不允许</b>改 mode，理由是：
 * "纯对话的会话突然开检索，历史回答的引用关系就不一致了"。
 * 现在允许切换，是因为"换模式"本身是高频真实需求——
 * 用户用plain 聊了几句，想改成带检索再继续，之前的唯一出路是新建会话、
 * 把上下文重打一遍。
 *
 * <p><b>接受的代价</b>（明确记录，避免以后有人以为是 bug）：
 * 同一会话里旧回答与新回答的语义可能不同。例如一段纯对话之后切到 RAG，
 * 历史部分没有引用而新部分有引用，读者需要自己分辨"哪几条是检索得出的"。
 * 这是"省掉重建上下文"换来的，属于<b>有意识的权衡</b>，不是缺陷。
 *
 * <p><b>必须守住的三条底线</b>：
 * <ol>
 *   <li><b>保留历史消息</b>——不删除、不重写、不伪造；</li>
 *   <li><b>切换后新消息严格按当前 mode 执行</b>（{@code modelRole} 会同步重算，
 *       见 {@code ConversationServiceImpl.update}）；</li>
 *   <li><b>不伪造历史消息的模式信息</b>——历史回答就是历史回答，
 *       不会被追认为"由当前模式生成"。</li>
 * </ol>
 *
 * <p><b>按目标模式清理不兼容字段</b>（{@code kbIds} 仅 RAG 有效、
 * {@code agentId} 仅 AGENT 有效，两者互斥）：
 * <ul>
 *   <li><b>PLAIN</b>：清空两者</li>
 *   <li><b>RAG</b>：清空 {@code agentId}；{@code kbIds} 未显式提供时<b>保留</b></li>
 *   <li><b>AGENT</b>：清空 {@code kbIds}；{@code agentId} 显式提供则绑定、
 *       {@code resetAgent} 则重置、<b>都没提供则保留原绑定</b></li>
 * </ul>
 * 不做跨模式的数据搬运（不会把原 Agent 的知识库复制给新会话，反之亦然）。
 */
public record UpdateConversationRequest(

        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        Boolean pinned,

        /**
         * 切换会话模式：PLAIN / RAG / AGENT；{@code null} = 不改。
         *
         * <p>非法值报 1001（复用 {@link ChatMode#parse} 的解析规则）。
         * 传了它就以目标模式为准清理不兼容字段，规则见类注释。
         */
        String mode,

        /** 绑定的知识库 id（仅 RAG 模式）；空列表表示解绑，null 表示不改。 */
        List<Long> kbIds,

        /**
         * 换一个 Agent（仅 AGENT 模式）。
         *
         * <p>与 {@code kbIds} 同理，"换 Agent 再问"是常见操作：Agent 是<b>执行者</b>，
         * 换掉只影响后续轮次由谁来答，历史回答的可比性不受影响。
         * null = 不改；要回到内置默认 Agent 请用 {@link #resetAgent()}——
         * "null 既是不改又是解绑"分不清，所以清空必须有独立信号。
         */
        Long agentId,

        /**
         * 回到内置默认 Agent（仅 AGENT 模式）：{@code true} = 清空会话选用的 Agent。
         *
         * <p>为什么需要它：选过 Agent 的会话若没有回退路径，用户唯一的办法是新建会话——
         * 而"这个会话我不想用自定义 Agent 了"是完全正常的操作（Phase 4.2 做界面时才发现
         * 这条死路，是 E2E 逼出来的：界面上要么给这个按钮，要么就得写一句"请新建会话"）。
         *
         * <p>与 {@code agentId} 同时传属于自相矛盾的请求，直接报 1001，不猜用户意图。
         * 该约定保持不变——即便同时传了 mode，也不会让 mode 去"决定"这一冲突的结果。
         */
        Boolean resetAgent) {
}