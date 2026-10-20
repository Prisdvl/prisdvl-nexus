package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多库扇出（Phase 5.5.1）。
 *
 * <p>这一组里最容易被"优化"掉、又最不能丢的三条：
 * <ol>
 *   <li><b>单库不切线程</b>——它是"单库路径行为零变化"的全部依据。只要有人在扇出里无条件
 *       开线程，单库就从"调用线程上的同步调用"变成"另一个线程上的调用"，
 *       异常栈、MDC、事务/线程绑定的一切都跟着变，而 {@code ChunkSearchServiceParityTest}
 *       未必当场变红。所以这里直接断言执行线程<b>就是调用线程</b>，且它<b>不是</b>虚拟线程；</li>
 *   <li><b>按输入顺序装配</b>——用 {@link CountDownLatch} 让"后提交的库先完成"，
 *       断言顺序仍与输入一致。没有这一步，测试无法区分"按输入顺序"与
 *       "碰巧按完成顺序，而本地几乎总是先提交的先完成"；</li>
 *   <li><b>超时不是空结果</b>——超时的库必须记成 {@code failures} 里的一条，
 *       而不是安静地贡献 0 条命中。把它们混为一谈，就是"这个库挂了"伪装成
 *       "你的笔记里没有"。</li>
 * </ol>
 *
 * <p>另外两条并发事实在这里钉住：MDC 与 TCCL 必须被搬进任务线程。
 * 后者不是洁癖——{@code EmbeddingClientFactory} 记录了 TCCL 指错导致类初始化失败、
 * 并<b>永久毒化整个 JVM 该类</b>的实测。
 */
class MultiKnowledgeRetrieverTest {

    private static final Long KB_A = 1001L;
    private static final Long KB_B = 1002L;

    /**
     * 闩等待的上界。<b>不是 5 秒</b>：2 核 runner 上虚拟线程的调度与唤醒可以被拖到秒级，
     * 5 秒会把"任务没并行"和"runner 太慢"混成同一个红。
     */
    private static final long AWAIT_SECONDS = 10;

    private RetrievalDefaults defaults;
    private ObservabilityRegistry registry;
    private MultiKnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        defaults = new RetrievalDefaults();
        registry = new ObservabilityRegistry();
        retriever = new MultiKnowledgeRetriever(defaults, registry);
    }

    // ------------------------------------------------------------------
    // 单库：与改造前逐字节一致
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单库：在调用线程上直接执行（不切线程、不是虚拟线程），失败原样抛")
    void singleKnowledgeBaseRunsOnCallingThread() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> executedOn = new AtomicReference<>();
        RuntimeException boom = new IllegalStateException("单库失败");

        // 成功路径
        MultiKnowledgeRetriever.FanOut<String> ok = retriever.retrieve(
                List.of(knowledgeBase(KB_A)), kb -> {
                    executedOn.set(Thread.currentThread());
                    return "A";
                });

        assertThat(executedOn.get()).as("单库必须在调用线程上执行").isSameAs(caller);
        assertThat(executedOn.get().isVirtual()).as("调用线程不是虚拟线程").isFalse();
        assertThat(ok.ordered()).containsExactly("A");
        assertThat(ok.hasFailures()).isFalse();

        // 失败路径：不隔离、不包装
        assertThatThrownBy(() -> retriever.retrieve(List.of(knowledgeBase(KB_A)), kb -> {
            throw boom;
        })).isSameAs(boom);
        assertThat(registry.snapshot())
                .as("单库本就是响亮的失败，不该计『部分库失败』")
                .doesNotContainKey(ObservabilityRegistry.RETRIEVAL_KB_FAILED);
    }

    @Test
    @DisplayName("空列表：不执行任何任务，返回空扇出（不是异常）")
    void emptyKnowledgeBasesIsNotAnError() {
        MultiKnowledgeRetriever.FanOut<String> fanOut =
                retriever.retrieve(List.of(), kb -> {
                    throw new AssertionError("不该被执行");
                });

        assertThat(fanOut.ordered()).isEmpty();
        assertThat(fanOut.failures()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 并行 + 顺序 + 隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("双库：真的并行（两个任务同时在跑），且两边的结果都回来")
    void twoKnowledgeBasesRunInParallel() {
        relaxFanoutBound();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch bothArrived = new CountDownLatch(2);

        MultiKnowledgeRetriever.FanOut<String> fanOut = retriever.retrieve(
                List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                    int now = running.incrementAndGet();
                    maxRunning.accumulateAndGet(now, Math::max);
                    // 抵达后等对方也抵达：两个任务因此必须同时在场，串行时前一个会在这里空等到上界
                    bothArrived.countDown();
                    awaitQuietly(bothArrived, AWAIT_SECONDS);
                    running.decrementAndGet();
                    return kb.getName();
                });

        assertThat(maxRunning.get())
                .as("任务没有同时在飞：同时最多只跑 1 个任务就说明退化成串行——把它改成 for 循环，这条必须红")
                .isGreaterThanOrEqualTo(2);
        assertThat(fanOut.failures()).as("并行执行不该产生失败").isEmpty();
        // 两个库都回来了，顺序与输入一致
        assertThat(fanOut.ordered()).containsExactly("库1001", "库1002");
    }

    @Test
    @DisplayName("后提交的库先完成：结果仍按输入顺序装配（不随完成时序抖动）")
    void resultsFollowInputOrderNotCompletionOrder() {
        // 扇出超时必须盖过两次闩等待，否则慢 runner 上会先整体超时把 A 取消掉
        relaxFanoutBound();

        CountDownLatch bStarted = new CountDownLatch(1);    // B 已进入任务体
        CountDownLatch bRecorded = new CountDownLatch(1);   // B 已写下自己的完成次序
        AtomicBoolean bStartedInTime = new AtomicBoolean();
        AtomicReference<String> finishOrder = new AtomicReference<>("");

        Function<KnowledgeBasePO, String> recall = kb -> {
            if (KB_B.equals(kb.getId())) {
                // 后提交的 B：先宣告"已开始"，写下自己的完成次序，最后才放行 A。
                // 顺序不能反：若先放行再写，A 的唤醒和 B 取锁就是赛跑，多核上 A 可能抢先，
                // 前提断言就成了"看运气"。
                bStarted.countDown();
                finishOrder.set(finishOrder.get() + kb.getId() + ",");
                bRecorded.countDown();
                return kb.getName();
            }
            // 先提交的 A：等的是「B 已开始」而不是「B 已完成」——前者只要求 B 被调度过一次，
            // 2 核 runner 上不会因为核少而等不到；等到 B 写下次序后 A 才返回 ⇒ 完成顺序必然 B、A。
            // 两笔写入之间由闩建立了发生在先关系，不再需要额外的锁。
            bStartedInTime.set(awaitQuietly(bStarted, AWAIT_SECONDS));
            awaitQuietly(bRecorded, AWAIT_SECONDS);
            finishOrder.set(finishOrder.get() + kb.getId() + ",");
            return kb.getName();
        };

        MultiKnowledgeRetriever.FanOut<String> fanOut =
                retriever.retrieve(List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), recall);

        assertThat(bStartedInTime.get())
                .as("前提：B 必须在 %d 秒内开始——等不到就是任务没有同时在飞，与装配顺序无关", AWAIT_SECONDS)
                .isTrue();
        assertThat(finishOrder.get()).as("前提：完成顺序确实是 B 在前")
                .isEqualTo(KB_B + "," + KB_A + ",");
        assertThat(fanOut.ordered())
                .as("装配顺序必须与输入一致——下游归并排序是稳定的，同分次序不能随网络抖动而变")
                .containsExactly("库1001", "库1002");
    }

    @Test
    @DisplayName("一个库失败：其余照常返回，failures 恰好一条，并计入 retrieval.kb.failed")
    void oneFailureIsIsolatedAndCounted() {
        MultiKnowledgeRetriever.FanOut<String> fanOut = retriever.retrieve(
                List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                    if (KB_A.equals(kb.getId())) {
                        throw new IllegalStateException("ES 连接被拒绝");
                    }
                    return "B";
                });

        assertThat(fanOut.ordered()).containsExactly(null, "B");
        assertThat(fanOut.failures()).hasSize(1);
        RetrievalFailure failure = fanOut.failures().get(0);
        assertThat(failure.knowledgeBaseId()).isEqualTo(KB_A);
        assertThat(failure.knowledgeBaseName()).isEqualTo("库1001");
        assertThat(failure.reason()).contains("ES 连接被拒绝");
        assertThat(failure.displayName()).isEqualTo("「库1001」");
        assertThat(registry.snapshot())
                .containsEntry(ObservabilityRegistry.RETRIEVAL_KB_FAILED, 1L);
    }

    @Test
    @DisplayName("全部失败：抛原异常（同一个实例），而不是返回『检索到 0 条』")
    void allFailedRethrowsTheOriginalException() {
        RuntimeException boom = new IllegalStateException("pgvector 不可用");

        assertThatThrownBy(() -> retriever.retrieve(
                List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                    throw boom;
                }))
                .as("空结果的含义是『库里没有』，不能用来表达『所有库都挂了』")
                .isSameAs(boom);
        assertThat(registry.snapshot())
                .as("全部失败本身已经足够响亮，不该再计『部分库失败』")
                .doesNotContainKey(ObservabilityRegistry.RETRIEVAL_KB_FAILED);
    }

    @Test
    @DisplayName("全部失败且含业务异常：抛 BusinessException（错误码与文案不被新包装改掉）")
    void allFailedPrefersBusinessException() {
        BusinessException notFound =
                new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库已被删除：1001");
        RuntimeException other = new IllegalStateException("另一个库是别的错");

        assertThatThrownBy(() -> retriever.retrieve(
                List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                    throw KB_A.equals(kb.getId()) ? other : notFound;
                }))
                .as("BusinessException 的 code 会被 FailureClassifier 按码映射，包一层就错档了")
                .isSameAs(notFound);
    }

    // ------------------------------------------------------------------
    // 超时
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一个库超时：记成 failure（不是空结果），另一个库的结果照常返回")
    void oneTimeoutIsRecordedAsFailureNotAsEmpty() {
        defaults.setFanoutTimeout(Duration.ofMillis(200));

        MultiKnowledgeRetriever.FanOut<String> fanOut = retriever.retrieve(
                List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                    if (KB_A.equals(kb.getId())) {
                        sleepQuietly(5_000);   // 远超整体超时，会被 cancel(true) 打断
                    }
                    return "B";
                });

        assertThat(fanOut.ordered()).containsExactly(null, "B");
        assertThat(fanOut.failures()).hasSize(1);
        assertThat(fanOut.failures().get(0).knowledgeBaseId()).isEqualTo(KB_A);
        assertThat(fanOut.failures().get(0).reason())
                .as("原因要说清是『超时』，而不是借用别的话术")
                .contains("超时");
        assertThat(registry.snapshot())
                .containsEntry(ObservabilityRegistry.RETRIEVAL_KB_FAILED, 1L);
    }

    @Test
    @DisplayName("全部超时：没有任何原始异常可抛 → 4005，点名了哪些库")
    void allTimedOutRethrowsAsVectorStoreUnavailable() {
        defaults.setFanoutTimeout(Duration.ofMillis(200));

        assertThatThrownBy(() -> retriever.retrieve(
                List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                    sleepQuietly(5_000);
                    return "never";
                }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(ErrorCode.VECTOR_STORE_UNAVAILABLE.getCode());
                    assertThat(ex.getMessage())
                            .contains("「库1001」").contains("「库1002」");
                });
    }

    @Test
    @DisplayName("调用线程被中断：响亮地失败，绝不返回一个『每个库都没命中』的空结果")
    void interruptedCallerFailsLoudlyInsteadOfReturningAnEmptyResult() {
        CountDownLatch block = new CountDownLatch(1);
        try {
            Thread.currentThread().interrupt();   // 模拟 SSE 断开 / 应用停机时调用线程被中断

            assertThatThrownBy(() -> retriever.retrieve(
                    List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                        awaitQuietly(block);
                        return "never";
                    }))
                    .as("返回空 ordered 会被调用方读成『没有命中』——即『你的笔记里没有』这句假话")
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(ErrorCode.VECTOR_STORE_UNAVAILABLE.getCode()));
        } finally {
            block.countDown();
            Thread.interrupted();   // 清掉中断标记，否则会污染同一个 JVM 里的后续用例
        }
    }

    // ------------------------------------------------------------------
    // 跨线程上下文
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MDC 里的 requestId 在任务线程内可见（否则并行那一段日志会断线）")
    void mdcIsPropagatedIntoWorkerThreads() throws Exception {
        MDC.put("requestId", "req-parallel-1");
        try {
            Map<Long, String> seen = new ConcurrentHashMap<>();
            retriever.retrieve(List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                seen.put(kb.getId(), MDC.get("requestId"));
                return kb.getName();
            });

            assertThat(seen).containsEntry(KB_A, "req-parallel-1").containsEntry(KB_B, "req-parallel-1");
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("调用线程的 TCCL 被带进任务线程（TCCL 指错会让 OllamaEmbeddingModel 类初始化永久失败）")
    void contextClassLoaderIsPropagatedIntoWorkerThreads() {
        Thread caller = Thread.currentThread();
        ClassLoader original = caller.getContextClassLoader();
        ClassLoader marker = new ClassLoader(original) { };
        Map<Long, ClassLoader> seen = new ConcurrentHashMap<>();
        try {
            caller.setContextClassLoader(marker);
            retriever.retrieve(List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)), kb -> {
                seen.put(kb.getId(), Thread.currentThread().getContextClassLoader());
                return kb.getName();
            });
        } finally {
            caller.setContextClassLoader(original);
        }

        assertThat(seen.get(KB_A)).as("A 的 TCCL 必须是调用线程那个").isSameAs(marker);
        assertThat(seen.get(KB_B)).as("B 的 TCCL 必须是调用线程那个").isSameAs(marker);
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private static KnowledgeBasePO knowledgeBase(Long id) {
        KnowledgeBasePO entity = new KnowledgeBasePO();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setName("库" + id);
        return entity;
    }

    /**
     * 把扇出整体超时放到"盖过所有闩等待"的长度。
     *
     * <p>默认的 10 秒恰好等于 {@link #AWAIT_SECONDS}：慢 runner 上闩还没等完，整体超时就会先
     * {@code cancel(true)} 掉任务，用例于是败在"全部库失败 → 4005"上，而真正想断言的
     * "任务没有并行 / 完成顺序反了"那条信息永远出不来——那不是同一个缺陷，却报成同一个红。
     * 所以凡是靠闩制造时序的用例都先调这里。
     */
    private void relaxFanoutBound() {
        defaults.setFanoutTimeout(Duration.ofSeconds(3 * AWAIT_SECONDS));
    }

    /** @return 闩在时限内被放行；超时或被打断则返回 {@code false}（中断标记会带回去） */
    private static boolean awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            // 被 cancel(true) 打断是超时路径的预期结果：把中断标记带上并返回
            Thread.currentThread().interrupt();
        }
    }

    /** 任务体在这里停住，直到测试收尾才放行——用来制造"调用方等不下去了"的场景。 */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
