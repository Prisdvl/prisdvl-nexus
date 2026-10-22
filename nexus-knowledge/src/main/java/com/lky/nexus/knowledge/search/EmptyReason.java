package com.lky.nexus.knowledge.search;

/**
 * 一次检索"为什么一条都没命中"的原因。
 *
 * <p><b>没有它，三种完全不同的事实在界面上是同一句话</b>：库里没有可用知识库、
 * 库里有切片但没建索引、索引建好了但确实没有相关内容。三者对用户的下一步动作要求完全相反
 * （去建库 / 去点「建索引」 / 换个问法），而 {@code hits=[]} 一个都表达不了，
 * 于是用户只能看到"没有匹配到任何资料"——真实原因可能只是他刚从没点过建索引。
 *
 * <p>取值是稳定的机器可读 code（前端据此显示文案），不是给人看的句子：
 * 文案在 {@code sectionsCopy} / {@code appCopy} 里，随语言切换。
 */
public enum EmptyReason {

    /** 一个可检索的知识库都没有（未创建、全部被禁用、或绑定的库都已删除）。 */
    NO_KB,

    /** 有切片，但一条都没有进检索索引——导入不等于建索引，这条路上检索永远不可能命中。 */
    NO_INDEX,

    /** 索引是好的，这次确实没有相关内容：只有这种情形值得换关键词再试。 */
    NO_RESULTS;

    /**
     * 命中为 0 时给出原因；有命中时返回 {@code null}（"不存在的问题"不该有值班的 code）。
     *
     * @param hitCount  本次命中条数
     * @param chunkCount 目标库的切片总数
     * @param indexedChunkCount 目标库已进索引的切片数
     */
    public static EmptyReason ofEmpty(int hitCount, long chunkCount, long indexedChunkCount) {
        if (hitCount > 0) {
            return null;
        }
        // 有切片却一条都没索引：这不是"没找到"，是"根本没法找"。
        // 注意顺序——先判它，再判"没有切片"：导入完成但没建索引恰恰是最常见的误判来源。
        if (chunkCount > 0 && indexedChunkCount == 0) {
            return NO_INDEX;
        }
        return NO_RESULTS;
    }
}
