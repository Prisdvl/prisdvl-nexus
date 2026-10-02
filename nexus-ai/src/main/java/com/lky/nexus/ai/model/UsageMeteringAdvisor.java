package com.lky.nexus.ai.model;

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

import java.util.concurrent.atomic.AtomicBoolean;
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
 */
public class UsageMeteringAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(UsageMeteringAdvisor.class);

    /**
     * 链条最内侧（不含终端 {@code ChatModelCallAdvisor}）。<b>刻意不等于</b>
     * {@link Ordered#LOWEST_PRECEDENCE}——原因见类注释里的那次实测。
     */
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    private final ChatUsageRecorder recorder;

    public UsageMeteringAdvisor(ChatUsageRecorder recorder) {
        this.recorder = recorder == null ? ChatUsageRecorder.NOOP : recorder;
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
        ChatClientResponse response = chain.nextCall(request);
        recordUsage(request, response == null ? null : response.chatResponse());
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 流式的一"轮"里只有末帧带真实 usage（前面的帧是 EmptyUsage），
        // 所以留最后一帧有量的，而不是把每一帧加起来——那会把同一次调用记成 N 笔。
        AtomicReference<ChatResponse> lastWithTokens = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean();

        return chain.nextStream(request)
                .doOnNext(response -> {
                    ChatResponse chatResponse = response == null ? null : response.chatResponse();
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
                        recordUsage(request, lastWithTokens.get());
                    }
                });
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
