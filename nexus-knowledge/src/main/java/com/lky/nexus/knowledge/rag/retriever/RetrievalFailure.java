package com.lky.nexus.knowledge.rag.retriever;

/**
 * 一个知识库这次<b>没有检索到</b>的如实记录（Phase 5.5.1）。
 *
 * <p><b>为什么它必须存在</b>：多库检索把失败隔离掉之后，那个库会安静地什么都不贡献——
 * 于是"这个库挂了"与"这个库里本来就没有这个内容"在用户侧长得一模一样。模型随后会说
 * "你的笔记里没有提到 X"，而 X 正躺在那个失败的库里。隔离本身是对的（一个库坏了不该
 * 让另外两个库的资料一起作废），但**隔离必须留下痕迹**：这条记录就是痕迹，
 * 它一路流到检索事件（给人看）与提示词（给模型看）。
 *
 * <p>字段里<b>只有给人和给模型看的东西</b>：id、库名、一句人话原因。原始异常刻意不进这个
 * record——它只用于"全部库都失败时把原始异常抛出去"，而那件事在
 * {@link MultiKnowledgeRetriever} 内部就做完了，异常对象不该跟着失败描述一起流到接口层。
 *
 * @param knowledgeBaseId   出问题的库
 * @param knowledgeBaseName 库名（"「Java 笔记」没搜到"）——只给 id 用户对不上是哪个库
 * @param reason            人话原因：异常消息，或"超过 10 秒未返回"
 */
public record RetrievalFailure(Long knowledgeBaseId, String knowledgeBaseName, String reason) {

    /** 提示词与界面上统一用它来指代一个库，免得两处各写一种括法。 */
    public String displayName() {
        return "「" + knowledgeBaseName + "」";
    }
}
