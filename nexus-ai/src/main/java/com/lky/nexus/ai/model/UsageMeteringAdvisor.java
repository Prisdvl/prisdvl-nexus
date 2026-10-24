package com.lky.nexus.ai.model;

import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.common.observe.CallTrace;
import com.lky.nexus.common.observe.CallTraceRecorder;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.common.security.ChatUsageRecorder;
import com.lky.nexus.common.security.UserContextKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * token 记账（5.6）：把每一次模型调用的用量交给 {@link ChatUsageRecorder}，用于每日配额。
 *
 * <p><b>为什么是 Advisor 而不是"逐个改调用点"</b>：全仓有 6 个地方真的发起 chat 调用
 * （{@code ChatServiceImpl} / {@code ChatStreamServiceImpl} / {@code RagAnswerServiceImpl} /
 * {@code RagStreamServiceImpl} / {@code AgentRuntime} / {@code LlmReranker}），但它们<b>全都</b>
 * 经过 {@link ChatClientFactory}。在 {@code ChatClientFactory.create} 里挂一次 defaultAdvisor，
 * 六个入口就一起覆盖了——这是本项目一直缺的那个"唯一收口"
 * （与 {@code FailureClassifier} 是错误判定的唯一入口同一个思路）。
 *
 * <p>而且它顺手解决了另一个问题：{@code RagAnswerServiceImpl.generate} 与
 * {@code AgentRuntime.ask} 拿的是 {@code .content()}，调用方手上根本没有 usage 对象。
 * Advisor 看的是 {@link ChatResponse}，与调用方拿到什么无关，所以这两处一行都不用改。
 *
 * <p><b>为什么实现 {@link CallAdvisor} + {@link StreamAdvisor} 而不是 {@code BaseAdvisor}</b>：
 * {@code BaseAdvisor} 的 {@code after(ChatClientResponse, AdvisorChain)} 拿不到
 * {@link ChatClientRequest}，也就读不到上下文里的 userId，只剩"记了也不知道算谁的"。
 * 这两个接口的方法签名都带 request，是唯一能拿到归属的入口。
 *
 * <p><b>顺序：{@code LOWEST_PRECEDENCE - 100}，必须小于 {@code LOWEST_PRECEDENCE}。</b>
 * Advisor 链按 order 升序执行、越小越靠外。{@code ToolCallingAdvisor} 的 order 是
 * {@code Integer.MIN_VALUE + 100}（框架每次请求都会自动挂上），因此本 advisor 位于它的
 * <b>内侧</b>，于是：
 *
 * <ul>
 *   <li>没有工具时，一次对话就是一轮，记一次；</li>
 *   <li>有工具时，工具循环的<b>每一轮</b>都会穿过这里一次，逐轮记账。
 *       这正是配额要的粒度——每一轮都真实消耗了 token，加总才是这次对话的成本
 *       （框架自己的 {@code UsageAccumulator} 是在外侧做加总，那是给"这次回答用了多少"
 *       看的，与"这天烧了多少额度"不是一回事）。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么不能直接用 {@link Ordered#LOWEST_PRECEDENCE}（写成 {@code MAX_VALUE}）</b>：
 * 框架的 {@code ChatModelCallAdvisor}（真正调模型的那个，链条终端）用的正是
 * {@code LOWEST_PRECEDENCE}，作者显然是想表达"我在最里面"。但实测（2026-10-02）：
 * order 与之相同的顾问<b>一次都不会被调用</b>——模型照常被调、回答照常返回、
 * 没有任何异常或日志，只是这个顾问被整条跳过。这正是本期最怕的失效形态：
 * 记账静默归零，只有月底账单会发现。留 100 的余量既避开这个坑，
 * 又仍然是"所有业务顾问里最靠内的一个"（该结论由
 * {@code UsageMeteringAdvisorTest#factoryRegistersTheAdvisorAsADefaultAdvisor} 钉住）。
 *
 * <p>不做任何缓存或本地累加：账一旦记进 {@code QuotaService}，就是当天的真相。
 *
 * <p><b>第二件事：调用明细</b>（{@code observability_call}）。同一个"唯一收口"顺手把
 * 每次调用的 requestId / 场景 / 模型 / token / 延迟 / 首 Token / 成败交给
 * {@link CallTraceRecorder}。之所以仍然在这里而不是去业务层撒点：
 * <ul>
 *   <li>业务层只知道自己那一层（{@code .content()} 拿不到 usage），也拿不到失败的模型名；</li>
 *   <li>散点一旦漏一处，报表就长期少一截，而且没人会去核对；</li>
 *   <li>这里天然能看到"失败的调用"（异常从这条链上抛过），而失败恰恰是观测最想看的。</li>
 * </ul>
 * 明细必须<b>逐轮</b>记（工具循环的每一轮都是一次真金白银的调用），
 * 与后端按 {@code request_id} 聚合成"一次请求"是两件事。
 */
public class UsageMeteringAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(UsageMeteringAdvisor.class);

    /**
     * 链条最内侧（不含终端 {@code ChatModelCallAdvisor}）。<b>刻意不等于</b>
     * {@link Ordered#LOWEST_PRECEDENCE}——原因见类注释里的那次实测。
     */
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    private final ChatUsageRecorder recorder;
    private final ObservabilityRegistry observability;
    private final CallTraceRecorder traceRecorder;

    /**
     * 不带观测注册表：指标记录退化为空操作。
     *
     * <p>保留它是为了纯 AI 层的单测——那里验的是"账记到谁头上"，
     * 不该为了跑起来而拖进一个指标注册表。
     */
    public UsageMeteringAdvisor(ChatUsageRecorder recorder) {
        this(recorder, new ObservabilityRegistry());
    }

    public UsageMeteringAdvisor(ChatUsageRecorder recorder, ObservabilityRegistry observability) {
        this(recorder, observability, CallTraceRecorder.NOOP);
    }

    public UsageMeteringAdvisor(ChatUsageRecorder recorder, ObservabilityRegistry observability,
                                CallTraceRecorder traceRecorder) {
        this.recorder = recorder == null ? ChatUsageRecorder.NOOP : recorder;
        this.observability = observability == null ? new ObservabilityRegistry() : observability;
        this.traceRecorder = traceRecorder == null ? CallTraceRecorder.NOOP : traceRecorder;
    }

    @Override
    public String getName() {
        return "UsageMeteringAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long startedNanos = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            ChatResponse chatResponse = response == null ? null : response.chatResponse();
            long latencyMillis = elapsedMillis(startedNanos);
            Metrics.of(observability, request, chatResponse).record(latencyMillis);
            recordUsage(request, chatResponse);
            // 非流式没有"首 Token"这个概念，显式给 null 而不是 0——
            // 报表上的 0ms 会被读成"极快"，null 才能被读成"没有这项"
            recordTrace(request, chatResponse, latencyMillis, null, true, null);
            return response;
        } catch (RuntimeException ex) {
            // 失败的调用也要留档：报错的那些请求恰恰是观测最想看的；
            // 只记成功调用等于把需要排查的那部分擦掉
            recordTrace(request, null, elapsedMillis(startedNanos), null, false, errorCodeOf(ex));
            throw ex;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 流式的一"轮"里只有末帧带真实 usage（前面的帧是 EmptyUsage），
        // 所以留最后一帧有量的，而不是把每一帧加起来——那会把同一次调用记成 N 笔。
        AtomicReference<ChatResponse> lastWithTokens = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean();
        // 计时从「订阅那一刻」起，而不是本方法被调用时：返回 Flux 到真正订阅之间可能隔着
        // 线程调度（本项目的流式链路正是如此），算进来会把排队时间记成模型耗时。
        AtomicLong startedNanos = new AtomicLong();
        // 首 Token 延迟：只有真的吐出文字的那一帧才算数。有些供应商会先发一两帧
        // 只带 role 的空 delta，把那种帧算成"首 Token"会让这项指标永远偏小。
        AtomicLong firstTokenNanos = new AtomicLong();

        return chain.nextStream(request)
                .doOnSubscribe(subscription -> startedNanos.set(System.nanoTime()))
                .doOnNext(response -> {
                    ChatResponse chatResponse = response == null ? null : response.chatResponse();
                    if (hasText(chatResponse)) {
                        // compareAndSet：多线程下只留第一帧，后到的不能覆盖
                        firstTokenNanos.compareAndSet(0, System.nanoTime());
                    }
                    if (hasTokens(chatResponse)) {
                        lastWithTokens.set(chatResponse);
                    }
                })
                // doFinally 而不是 doOnComplete：用户中途断开（cancel）时模型那边可能已经
                // 算过钱了，能记就记，别把已经发生的消耗丢掉
                .doFinally(signal -> {
                    // 同一轮只记一次：Flux 若被重复订阅（重试、重放），
                    // 没有这道闸就会把一次调用记成多笔
                    if (recorded.compareAndSet(false, true)) {
                        ChatResponse last = lastWithTokens.get();
                        long started = startedNanos.get();
                        long latencyMillis = started == 0 ? -1 : elapsedMillis(started);
                        Metrics.of(observability, request, last).record(latencyMillis);
                        recordUsage(request, last);
                        recordTrace(request, last, latencyMillis, firstTokenMillis(started, firstTokenNanos.get()),
                                // 用户主动断开不是模型失败：它正常产出过内容，
                                // 记成 failed 会把"我们自己的错误率"算高
                                signal != SignalType.ON_ERROR, null);
                    }
                });
    }

    private static Long firstTokenMillis(long startedNanos, long firstTokenNanos) {
        if (startedNanos == 0 || firstTokenNanos == 0) {
            return null;
        }
        return Math.max(0, (firstTokenNanos - startedNanos) / 1_000_000);
    }

    private static Integer errorCodeOf(RuntimeException ex) {
        try {
            return FailureClassifier.toBusinessException(ex).getCode();
        } catch (RuntimeException classifierFailure) {
            // 分类器自己挂了也不能把原始异常吞掉：观测是附加价值
            return null;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 一次模型调用的指标（5.9 六）：调用次数、token 量、耗时。
     *
     * <p><b>什么时候记</b>：只要这次调用真的发生了就记，<b>不取决于有没有 usage</b>。
     * 供应商不回传 usage（流式下很常见）时，token 量记不到——但"调用了几次、花了多久"
     * 是本地就能观测的事实，把它一起丢掉会让指标在那些供应商上整片为空，
     * 看起来像"没人在用"。token 为 0 与"没有这次调用"是两件事。
     *
     * <p>系统调用（重排）也照记：它确实花了钱和时间，只是不归属到任何用户。
     * 把它排除在指标外，运维看到的模型开销就永远比账单少一截。
     */
    private record Metrics(ObservabilityRegistry observability, Integer promptTokens,
                           Integer completionTokens) {

        static Metrics of(ObservabilityRegistry observability, ChatClientRequest request,
                          ChatResponse response) {
            Usage usage = hasTokens(response) ? response.getMetadata().getUsage() : null;
            return new Metrics(observability,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens());
        }

        void record(long latencyMillis) {
            observability.recordLlmCall(promptTokens, completionTokens, latencyMillis);
        }
    }

    // ------------------------------------------------------------------

    private void recordUsage(ChatClientRequest request, ChatResponse response) {
        if (!hasTokens(response)) {
            // 供应商没回传 usage（流式下很常见）：记不到就是记不到，不猜。
            // 这条路径的可见性由付费方在账单上体现，本项目不额外造一个假数字。
            return;
        }
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        int promptTokens = nullToZero(usage.getPromptTokens());
        int completionTokens = nullToZero(usage.getCompletionTokens());
        String model = metadata.getModel();

        try {
            if (isSystemCall(request)) {
                // 系统调用（当前只有 LlmReranker 的重排）算系统成本，不进任何用户的配额
                return;
            }
            Long userId = userIdOf(request);
            if (userId == null) {
                // 不静默：走到这里意味着某个入口漏传了 UserContextKeys.USER_ID，
                // 也就是说那个入口的 token 消耗没人认领。计数让它可见，而不是悄悄漏掉。
                recorder.recordUnattributed(model);
                return;
            }
            recorder.record(userId, promptTokens, completionTokens);
        } catch (RuntimeException ex) {
            // 端口契约要求实现方自己不抛异常（记账失败留痕即可），这里再兜一层：
            // 绝不能因为记账把一次已经成功的模型调用变成失败——用户已经拿到（或正在收）答案。
            log.warn("token 记账失败，本次用量未能计入配额 model={} promptTokens={} completionTokens={}",
                    model, promptTokens, completionTokens, ex);
        }
    }

    /**
     * 记一条调用明细（{@code observability_call}）。
     *
     * <p>与 token 记账不同，这里<b>没有 usage 也照记</b>：一次调用有没有发生、花了多久、
     * 成没成功，都是本地就能观测的事实。丢了它，供应商不回传 usage 的那些请求
     * 在观测页上就完全不存在——看起来像没人用过。
     *
     * <p>失败时 model 只能从请求里拿（没有响应可读），拿不到就留 null，
     * 不拿角色名顶替：角色会随配置改，模型名才是账单上的那个。
     */
    private void recordTrace(ChatClientRequest request, ChatResponse response, long latencyMillis,
                             Long firstTokenMs, boolean success, Integer errorCode) {
        try {
            Integer promptTokens = null;
            Integer completionTokens = null;
            if (hasTokens(response)) {
                Usage usage = response.getMetadata().getUsage();
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
            }
            traceRecorder.record(new CallTrace(
                    stringContextValue(request, UserContextKeys.REQUEST_ID),
                    userIdOf(request),
                    stringContextValue(request, UserContextKeys.SCENE),
                    modelOf(request, response),
                    promptTokens,
                    completionTokens,
                    // 没订阅成功时 latency 是 -1（见上面 started == 0 的分支），
                    // 落库前归零：负数时长会污染 AVG()
                    Math.max(latencyMillis, 0),
                    firstTokenMs,
                    success,
                    errorCode));
        } catch (RuntimeException ex) {
            log.warn("调用明细留档失败，本次调用未进入观测 requestId={} scene={}",
                    stringContextValue(request, UserContextKeys.REQUEST_ID),
                    stringContextValue(request, UserContextKeys.SCENE), ex);
        }
    }

    private static String modelOf(ChatClientRequest request, ChatResponse response) {
        if (response != null && response.getMetadata() != null) {
            String model = response.getMetadata().getModel();
            if (model != null && !model.isBlank()) {
                return model;
            }
        }
        try {
            if (request != null && request.prompt() != null && request.prompt().getOptions() != null) {
                return request.prompt().getOptions().getModel();
            }
        } catch (RuntimeException ignored) {
            // 有些自定义 ChatOptions 实现在这里会抛：观测不该因此丢一条记录
        }
        return null;
    }

    private static boolean hasText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return false;
        }
        String text = response.getResult().getOutput().getText();
        return text != null && !text.isEmpty();
    }

    private static String stringContextValue(ChatClientRequest request, String key) {
        Object value = contextValue(request, key);
        return value == null ? null : value.toString();
    }

    private static boolean isSystemCall(ChatClientRequest request) {
        return Boolean.TRUE.equals(contextValue(request, UserContextKeys.SYSTEM_CALL));
    }

    private static Long userIdOf(ChatClientRequest request) {
        Object value = contextValue(request, UserContextKeys.USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            // 宽容一点：把能解析的字符串认下来。解析不了就返回 null，
            // 于是走 unattributed 计数——那是一条可见的缺陷信号，不会静默丢失
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object contextValue(ChatClientRequest request, String key) {
        return request == null || request.context() == null ? null : request.context().get(key);
    }

    private static boolean hasTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return false;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return false;
        }
        // 刻意不调 getTotalTokens()：它的默认实现是 prompt + completion，
        // 任意一侧为 null 就是 NPE——而"供应商只回了一半"恰恰是真实存在的情况
        return nullToZero(usage.getPromptTokens()) + nullToZero(usage.getCompletionTokens()) > 0;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
