package com.lky.nexus.ai.rag.retriever;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 检索参数的<b>全局默认值</b>（{@code nexus.retrieval.*}）——检索参数优先级里最底下的一层。
 *
 * <pre>
 * nexus:
 *   retrieval:
 *     top-k: 5
 *     similarity-threshold: 0.25
 * </pre>
 *
 * <p><b>为什么要单独抽一个配置类</b>：在这之前，"默认 topK"至少有三个来源——
 * 知识库建库时的常量 5、检索接口的 {@code defaultValue="10"}`、Agent 工具里的 5，
 * 而"默认相似度阈值"有两个且互相打架（建库写 0.7、检索时实际用 0.25）。
 * 这种"同一个含义的默认值散在多处"必然有一天会各自改一半：
 * 建库默认 0.7、检索默认 0.25，用户看到的是"我设了阈值却好像没生效"。
 * 现在只剩一个来源，知识库创建与检索落空两处都读它。
 *
 * <p>参数优先级（{@code RetrievalQueryFactory} 实现，自高到低）：
 * <pre>
 * 调用方显式指定  &gt;  知识库自身配置（retrieval_top_k / similarity_threshold）  &gt;  本类
 * </pre>
 *
 * <p><b>为什么相似度阈值的默认值是 0.25 而不是 0.7</b>：这是有实测依据的选择，
 * 不是随手取的值。真实联调中「缓存击穿怎么解决」的向量第一名余弦相似度是 <b>0.7458</b>
 * （见 decisions.md 附录 B 的检索实测表）——它只是<b>刚好</b>高于 0.7。
 * 换句话说 0.7 这条线离"一条完全正确的命中"只有 0.05 的余量，
 * 任何正常但表述不同的笔记（0.5~0.7 区间）都会被静默丢掉，
 * 而表现是"知识库里明明有，模型却说找不到"——这类静默变差最难发现。
 * 阈值的职责是挡掉明显无关的片段（噪声下限），不是做精排；
 * 想收紧请按库把 {@code similarity_threshold} 调高（那是它的用途），
 * 但不要在全局默认上收紧——那会一次性影响所有知识库。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "nexus.retrieval")
public class RetrievalDefaults {

    /**
     * 默认返回条数。
     *
     * <p>与数据库列默认值（{@code retrieval_top_k DEFAULT 5}）、
     * 以及 {@link RetrievalQuery#DEFAULT_TOP_K} 保持一致；
     * 三者同值是有意的：它们分别服务"没走 API 的插入"、"Java 侧兜底"与"线上默认"。
     */
    private int topK = RetrievalQuery.DEFAULT_TOP_K;

    /** 默认向量相似度下限（取值理由见类注释）。 */
    private double similarityThreshold = RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD;

    /**
     * 多库扇出的<b>整体</b>超时（Phase 5.5.1，{@code nexus.retrieval.fanout-timeout}）。
     *
     * <p><b>它不在上面那条优先级阶梯里</b>——没有"调用方指定"也没有"按库配置"两层，
     * 所以这个类里它是个异类，写在这里是因为 {@code nexus.retrieval.*} 只该有一个持有者。
     * 类注释说的"同一含义的默认值不要散在多处"同样适用于它。
     *
     * <p><b>10 秒不是"慢但还能用"的意思</b>：检索这一段一直是百毫秒级
     * （重排那 28→172 秒的实测见 {@code TimeLimitedCall} 的类注释，时间全在 LLM 调用上）。
     * 所以撞到 10s 就说明下游某一环卡死了，此时宁可少一个库的资料并<b>如实说出来</b>，
     * 也不要让整个问答挂在这里——这与"重排超时就保持召回顺序继续"是同一套取舍。
     *
     * <p><b>为什么没有"并行度"这个旋钮</b>：扇出跑在虚拟线程上（等待不占平台线程），
     * 而绑定的知识库数有硬上限（前端 {@code multiple-limit=10}，后端也校验），
     * 所以"最多并行几个"这个数字永远等于实际库数——加一个只会被人以为它有用。
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration fanoutTimeout = Duration.ofSeconds(10);
}
