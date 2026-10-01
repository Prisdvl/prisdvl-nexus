package com.lky.nexus.ai.agent;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.util.Locale;

/**
 * 回答策略：<b>没有可引用的资料时，允不允许用模型自身的知识回答</b>（4.4）。
 *
 * <p>它之前不是一个选项，而是写死的唯一行为——"检索不到就拒答"。那个行为对
 * <b>一个知识库都没有的新用户</b>等于"第一次提问就吃闭门羹"，而拒绝本身没有信息量
 * （两个诉求相反的诉求其实是同一件事：用户要的是"别把我的笔记说成你有"，不是"别说话"）。
 * 于是把它从提示词里拿出来，变成用户可配的一列。
 *
 * <p>两条策略的分界线是<b>归属</b>，不是"答不答"：
 * <ul>
 *   <li>{@link #KB_PREFERRED}（默认）：笔记优先；没有可引用资料时<b>可以</b>用自身知识回答，
 *       但必须明说"不是来自你的笔记"；</li>
 *   <li>{@link #KB_ONLY}：只依据笔记，没有就说没找到——4.4 之前的旧行为，现在是显式选择。</li>
 * </ul>
 * 两种策略下都<b>禁止</b>把模型自身的知识说成是用户笔记里的内容（那段写在
 * {@code AgentPrompts.AGENT_SYSTEM} 里，与策略无关）。
 *
 * <p><b>为什么在 nexus-ai 而不是像 {@code AgentStatus} 那样放在 app 层</b>：
 * 它被 {@link AgentDefinition} 持有，而依赖方向是 {@code app → ai}，放 app 会倒挂。
 * 因此这里成了 nexus-ai 里<b>第一个带 {@code parse} 的枚举</b>——{@code ToolCategory} /
 * {@code ToolSource} 故意没有 parse，因为它们不从数据库列解析；本类是有的，因为
 * {@code agent.answer_policy} 存的就是它的名字。别照着这个去给那两个补 parse。
 */
public enum AnswerPolicy {

    /** 默认：笔记优先，没有可引用资料时可凭自身知识回答（但必须注明不是来自笔记）。 */
    KB_PREFERRED,

    /** 只依据笔记；没有可引用资料就如实说没找到，不用自身知识作答。 */
    KB_ONLY;

    /**
     * 没配时的默认值。
     *
     * <p>选 {@code KB_PREFERRED} 而不是"更安全"的 {@code KB_ONLY}：旧行为不是被选的，
     * 它只是当时唯一的行为，而它恰是这次要修的那个问题。默认值该给的是"能服务"，
     * 想要严格的人显式选 {@code KB_ONLY}（存量内置行的旧提示词由
     * {@code AgentService.resolveDefault} 就地升级，不会留下"提示词说严格、策略说宽松"的矛盾）。
     */
    public static final AnswerPolicy DEFAULT = KB_PREFERRED;

    /**
     * 宽松解析：null/空 → {@link #DEFAULT}；大小写不敏感；非法值抛 1001（客户端参数错误）。
     *
     * <p>与 {@code AgentStatus.parse} 同一套约定——写入口就报错，而不是存下一个没人认识的值、
     * 等到提问时才莫名其妙地降级。
     */
    public static AnswerPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的 Agent 回答策略: %s（可选值：KB_PREFERRED / KB_ONLY）".formatted(raw));
        }
    }
}
