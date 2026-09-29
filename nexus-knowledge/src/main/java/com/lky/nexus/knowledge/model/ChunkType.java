package com.lky.nexus.knowledge.model;

/**
 * 切片类型。
 *
 * <p>为什么要区分：不同内容在检索与展示上的处理方式不同——
 * <ul>
 *   <li>代码块<b>绝不能被切开</b>（切一半的代码既没法读，向量语义也是噪音）；</li>
 *   <li>表格同理，切开后表头与数据行分离，模型会误读；</li>
 *   <li>正文段落可以按语义边界再分。</li>
 * </ul>
 * 这个枚举就是"切分时尊重结构"的具体体现。
 */
public enum ChunkType {
    /** 普通段落。 */
    PARAGRAPH,
    /** 代码块（含围栏内容）。 */
    CODE,
    /** Markdown 表格。 */
    TABLE,
    /** 列表块。 */
    LIST,
    /** 标题下没有正文时的兜底块（通常会被合并掉）。 */
    HEADING
}
