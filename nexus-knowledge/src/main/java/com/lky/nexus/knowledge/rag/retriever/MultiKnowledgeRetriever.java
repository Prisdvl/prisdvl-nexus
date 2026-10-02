package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 多个知识库的<b>扇出</b>：并行召回，一个库坏了不影响其它库。
 *
 * <p><b>它不是 {@code Retriever} 端口的一个实现</b>，也不该被当成"第四个检索策略"：
 * {@code Retriever} 回答的是"<b>怎么召回一个库</b>"（向量 / 关键词 / 混合 / 重排），
 * 是策略；本类回答的是"<b>多个库怎么并行地召回、其中一个失败了怎么办</b>"，是扇出。
 * 把两件事混进一个接口，会让"新增一路召回"和"调整并行与降级策略"这两件互不相干的事
 * 开始互相牵制。所以它不在 {@code RetrieverRegistry} 里，只被两个门面调用：
 * {@code ChunkSearchServiceImpl.searchManyDetailed}（RAG）与 {@code KnowledgeSearchTool}（Agent）。
 *
 * <p><b>归口在这里的三条规则</b>（这是它值得单独存在的原因，而不是把 for 循环换成
 * {@code CompletableFuture} 就完事）：
 * <ol>
 *   <li><b>单库不开线程</b>：只有一个库时"隔离"没有对象，就在调用线程上跑、异常原样抛。
 *       单库路径因此与改造前<b>逐字节一致</b>（{@code ChunkSearchServiceParityTest} 钉着它）；</li>
 *   <li><b>结果按输入顺序装配</b>，与完成时序无关。这不是洁癖：下游
 *       {@code RecallSupport.merge} 与 {@code KnowledgeSearchTool} 都用<b>稳定</b>排序、
 *       分数相同时靠累积顺序定序——按完成顺序装配会让"同分条目的先后"随网络抖动而变；</li>
 *   <li><b>全部失败时抛原始异常</b>，绝不返回"检索到 0 条"。空结果的含义是
 *       "知识库里没有"，把"所有库都挂了"说成"知识库里没有"是最坏的一种假话。
 *       也不新包一层 {@code 4005}：{@code FailureClassifier} 对 {@code BusinessException}
 *       按 code 映射、对其它异常另有映射，包一层会把"ES 挂了"的错误码与文案悄悄改掉。</li>
 * </ol>
 *
 * <p><b>三个必须显式处理的并发事实</b>（都是本项目踩过或写错过的）：
 * <ul>
 *   <li><b>只用 {@code invokeAll}，不用 {@code CompletableFuture.allOf}</b>：
 *       {@code allOf().join()} 抛的是聚合异常，一 catch 就会把<b>已经成功</b>的那些库一起扔掉，
 *       正好把隔离目标打掉；而逐个 {@code get(timeout)} 会退化成 N×超时。
 *       {@code invokeAll(tasks, timeout)} 一次返回，超时按<b>整体</b>算，未完成的自动取消，
 *       且返回列表与任务<b>同序</b>——下标即输入下标，装配不需要任何映射表；</li>
 *   <li><b>执行器不用 try-with-resources</b>：{@code ExecutorService.close()} 的语义是
 *       "等到所有任务终止"，一个不响应中断的任务（阻塞在 socket 读上）会把<b>已经拿到结果</b>
 *       的本方法卡在 close 里，精心设的整体超时就作废了。所以只 {@code shutdownNow()} +
 *       有界等一小会儿。取消是<b>尽力而为</b>——真正兜底的是各客户端自己的连接/读超时；</li>
 *   <li><b>MDC 与 TCCL 不跨线程继承</b>，必须显式搬运。MDC 决定日志里还看不看得见 requestId；
 *       TCCL 是硬故障：{@code EmbeddingClientFactory} 有实测记录——虚拟线程上的 TCCL 曾是
 *       JDK 的 {@code AppClassLoader}，读不到 fat jar 内的资源，而类初始化失败会
 *       <b>永久毒化整个 JVM 的该类</b>。这里搬的是调用线程当前的 context map（不依赖
 *       app 层 {@code RequestIdHolder} 的键名约定，那个类在 nexus-knowledge 的上游，够不着）。</li>
 * </ul>
 *
 * <p><b>诚实的代价</b>：多库并行会把下游（ES / pgvector / 嵌入服务 / 连接池）的并发度
 * 一次性乘到最多"绑库数"倍——而每个库在 Retriever 里还会各做一次 {@code requireOwned} 查询，
 * 也就是 N 个并发读。所以这要求连接池上限 ≥ 绑库数（前端上限 10）。
 * 这不是免费的加速，是把延迟换成了对下游的压力；{@code retrieval.kb.failed} 计数
 * 让"压力变成了失败"这件事可巡检，而不是只表现为"回答质量下降了"。
 */
@Component
public class MultiKnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(MultiKnowledgeRetriever.class);

    /** 取消之后最多再等这么久。只为回收线程，绝不为等任务自然结束（原因见类注释）。 */
    private static final long REAP_MILLIS = 50;

    private final RetrievalDefaults defaults;
    private final ObservabilityRegistry observabilityRegistry;

    public MultiKnowledgeRetriever(RetrievalDefaults defaults, ObservabilityRegistry observabilityRegistry) {
        this.defaults = defaults;
        this.observabilityRegistry = observabilityRegistry;
    }

    /**
     * 对每个知识库执行 {@code perKnowledgeBase}。
     *
     * <p>任务体里<b>不应该</b>再往共享的监听器/SSE 上写东西：那些东西不是线程安全的
     * （{@code SseEmitter.send} 不是，{@code AgentContext.collector} 的 sink 是个普通
     * {@code ArrayList}）。需要留痕就把记录<b>当返回值带出来</b>，等本方法返回后由调用线程
     * 按输入顺序串行写——顺序也因此是确定的。
     *
     * @param perKnowledgeBase 单个库要做的事。它的异常会被记成
     *                         {@link RetrievalFailure} 并隔离（除非所有库都失败）
     * @return 与输入等长同序的结果 + 失败清单
     * @throws RuntimeException 所有库都失败时，抛出其中一个<b>原始</b>异常；若全是超时
     *                          （没有原始异常可抛），抛 {@code 4005} 并点名
     */
    public <T> FanOut<T> retrieve(List<KnowledgeBasePO> knowledgeBases,
                                  Function<KnowledgeBasePO, T> perKnowledgeBase) {
        if (knowledgeBases == null || knowledgeBases.isEmpty()) {
            return new FanOut<>(List.of(), List.of());
        }
        if (knowledgeBases.size() == 1) {
            // 单库：不开线程、不设超时，就在调用线程上跑。
            // 超时这一条是刻意的：今天单库路径（search / searchMany 单元素）本来就没有超时，
            // 那是客户端连接/读超时该管的事；在扇出这一层顺手给单库加超时，
            // 会改变被 ParityTest 钉着的路径的行为，而收益只是"给一个库也设个上限"。
            List<T> single = new ArrayList<>(1);
            single.add(perKnowledgeBase.apply(knowledgeBases.get(0)));
            return new FanOut<>(freeze(single), List.of());
        }
        return fanOut(knowledgeBases, perKnowledgeBase);
    }

    private <T> FanOut<T> fanOut(List<KnowledgeBasePO> knowledgeBases,
                                 Function<KnowledgeBasePO, T> perKnowledgeBase) {
        Duration timeout = defaults.getFanoutTimeout();
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        ClassLoader callerClassLoader = Thread.currentThread().getContextClassLoader();

        List<Callable<T>> tasks = new ArrayList<>(knowledgeBases.size());
        for (KnowledgeBasePO knowledgeBase : knowledgeBases) {
            tasks.add(() -> {
                Thread thread = Thread.currentThread();
                ClassLoader original = thread.getContextClassLoader();
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                }
                try {
                    thread.setContextClassLoader(callerClassLoader);
                    return perKnowledgeBase.apply(knowledgeBase);
                } finally {
                    thread.setContextClassLoader(original);
                    MDC.clear();
                }
            });
        }

        List<Future<T>> futures = runAll(tasks, timeout);

        List<T> ordered = new ArrayList<>(knowledgeBases.size());
        List<RetrievalFailure> failures = new ArrayList<>();
        List<RuntimeException> causes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            KnowledgeBasePO knowledgeBase = knowledgeBases.get(i);
            // invokeAll 保证返回时"全部已完成或被取消"，所以下面的 get 不会阻塞
            try {
                ordered.add(futures.get(i).get());
            } catch (CancellationException ex) {
                // 超时被取消：如实记成"超时"，绝不能当成"这个库没命中"
                ordered.add(null);
                failures.add(new RetrievalFailure(knowledgeBase.getId(), knowledgeBase.getName(),
                        "超过 " + timeout.toSeconds() + " 秒未返回（整体超时）"));
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Error error) {
                    // OOM / LinkageError 这类不是"一个库检索失败"，包装它会误导用户（进程都快没了）
                    throw error;
                }
                ordered.add(null);
                failures.add(new RetrievalFailure(knowledgeBase.getId(), knowledgeBase.getName(),
                        cause == null || cause.getMessage() == null ? ex.toString() : cause.getMessage()));
                causes.add(asRuntime(cause, ex));
            } catch (InterruptedException ex) {
                // invokeAll 保证返回时任务已全部结束，所以这个分支现实中到不了；
                // 留着是因为 get() 声明了受检异常。中断的实际出口在 runAll（那里才是唯一会阻塞的地方）
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE,
                        "多库检索被中断，已取消未完成的知识库");
            }
        }

        if (failures.size() == knowledgeBases.size()) {
            throw allFailed(knowledgeBases, failures, causes, timeout);
        }
        if (!failures.isEmpty()) {
            // 隔离不静默：谁失败了、还剩几个库在正常贡献，都要留下痕迹。
            // 这条 WARN + 下面的计数 + 流到检索事件里的 failures，是同一件事的三个出口
            log.warn("多库检索：{} 个库失败、{} 个库照常（失败：{}）；"
                            + "失败库里的资料这次不会被引用，回答必须如实说明这一点",
                    failures.size(), knowledgeBases.size() - failures.size(),
                    failures.stream().map(f -> f.displayName() + " " + f.reason()).toList());
            observabilityRegistry.increment(ObservabilityRegistry.RETRIEVAL_KB_FAILED);
        }
        return new FanOut<>(freeze(ordered), freeze(failures));
    }

    /**
     * 冻结成不可变列表，且<b>允许 null</b>。
     *
     * <p>不能用 {@code List.copyOf} / {@code List.of}：它们对 null 直接 NPE，
     * 而"失败位是 null"正是 {@link FanOut} 契约的一部分——写这段的第一版就是这样红的。
     * 这里要的是"不可变"，不是"非空"。
     */
    private static <T> List<T> freeze(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * 执行器生命周期见类注释：不用 try-with-resources。
     *
     * <p><b>被中断时必须抛出去，不能返回空列表</b>（第一版就是返回 {@code List.of()} 的，是错的）：
     * {@code ordered} 的契约是"与输入等长同序"，返回空列表会让调用方把它读成
     * "每个库都没有命中"——于是用户得到一句"资料中没有相关内容"，
     * 而真相是这次检索根本没跑完。这正是本类存在的理由所反对的那类假话。
     * 中断也不该被当成"隔离某个库"：{@code invokeAll} 在中途被打断时会取消所有未完成的任务，
     * 此时<b>哪些库成功了已经无从得知</b>，部分结果是不诚实的。
     */
    private <T> List<Future<T>> runAll(List<Callable<T>> tasks, Duration timeout) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return executor.invokeAll(tasks, timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE,
                    "多库检索被中断，已取消未完成的知识库");
        } finally {
            executor.shutdownNow();
            reap(executor);
        }
    }

    private void reap(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(REAP_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("多库检索有任务未在取消后 {}ms 内结束（虚拟线程会自行终止，不影响本次结果）",
                        REAP_MILLIS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把 {@link ExecutionException} 的原因收成 {@link RuntimeException}。
     *
     * <p>{@code Function} 不声明受检异常，所以走到这里的现实中都是运行时异常——原样返回。
     * 万一出现受检异常（框架内部包装），也<b>不吞</b>：用 {@code BusinessException} 把它带出来，
     * 原始异常挂在 {@code cause} 上，堆栈不丢。丢堆栈比丢错误码更难查。
     */
    private static RuntimeException asRuntime(Throwable cause, ExecutionException wrapper) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        Throwable effective = cause == null ? wrapper : cause;
        if (effective instanceof RuntimeException runtime) {
            return runtime;
        }
        return new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE,
                "知识库检索失败：" + effective.getMessage(), effective);
    }

    /**
     * 全部库都失败：抛原始异常，不返回空结果、也不新包一个通用错误码（理由见类注释）。
     *
     * <p>优先抛 {@code BusinessException}：它的 code 与文案更准确（例如库在校验后被删的 3001），
     * 而 {@code IllegalStateException} 会经 {@code FailureClassifier} 落到"模型不可用"那一档。
     */
    private RuntimeException allFailed(List<KnowledgeBasePO> knowledgeBases, List<RetrievalFailure> failures,
                                       List<RuntimeException> causes, Duration timeout) {
        log.warn("多库检索：{} 个库全部失败（{}）", knowledgeBases.size(),
                failures.stream().map(f -> f.displayName() + " " + f.reason()).toList());
        for (RuntimeException cause : causes) {
            if (cause instanceof BusinessException businessException) {
                return businessException;
            }
        }
        if (!causes.isEmpty()) {
            return causes.get(0);
        }
        // 全是超时：没有任何原始异常可以代表它，只能自己说清楚是"整体超时"
        return new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE,
                "绑定的 " + knowledgeBases.size() + " 个知识库都没在 " + timeout.toSeconds()
                        + " 秒内返回，本次检索没有可用资料（失败："
                        + failures.stream().map(RetrievalFailure::displayName).toList() + "）");
    }

    /**
     * 扇出结果。
     *
     * @param ordered  与输入<b>等长且同序</b>；失败或超时的下标是 {@code null}
     *                 （因此这里允许 null，见 {@code freeze}）。
     *                 <b>由调用方决定用什么占位</b>：扇出不认识 {@code T} 的"空"是什么。
     *                 RAG 那条路填空列表（归并只看列表内容，且要保持"每库一个列表"的结构，
     *                 否则 {@code RecallSupport.merge} 的单元素快路径会跳过一次截断）
     * @param failures 只在<b>部分</b>成功时非空；全部失败时 {@link #retrieve} 直接抛，不返回
     */
    public record FanOut<T>(List<T> ordered, List<RetrievalFailure> failures) {

        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }
}
