package com.lky.nexus.app.security;

import com.lky.nexus.common.security.ChatUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChatUsageRecorder} 端口在 app 层的实现：把 Advisor 报上来的用量交给 {@link QuotaService}。
 *
 * <p>为什么要多这一个类，而不是让 {@code QuotaService} 直接实现端口：
 * 端口是 <b>nexus-ai 能看见的</b>接口，它的形状由记账点的需要决定；
 * 而 {@code QuotaService} 是"每日配额"这个业务概念的实现。让后者去实现前者，
 * 等于让配额服务的公开形状被 Advisor 的需要牵着走（比如"要带模型名"这种纯展示需求）。
 * 中间隔一层适配器，两边各自演化。
 *
 * <p>放在 <b>app</b> 层而不是 ai 层：这里要用的 Redis 键、每日窗口、fail-closed 策略
 * 全是 app 的事。bean 由 {@code AppConfig} 装配（与 {@code ObservabilityRegistry} 同一套做法）。
 */
public class QuotaChatUsageRecorder implements ChatUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(QuotaChatUsageRecorder.class);

    private final QuotaService quotaService;

    public QuotaChatUsageRecorder(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @Override
    public void record(Long userId, int promptTokens, int completionTokens) {
        quotaService.recordTokens(userId, promptTokens, completionTokens);
    }

    @Override
    public void recordUnattributed(String model) {
        // 计数（让缺口可见）+ 日志（让缺口可定位：哪个模型、进而哪个入口漏了 userId）。
        // 只计数不写日志的话，看到这个数字的人还得自己去猜是哪条链路；
        // 只写日志不计数的话，没人会去翻日志。两样都要。
        log.warn("模型调用没有归属到任何用户，本次用量未计入任何配额 model={}；"
                + "请检查该调用点是否设置了 UserContextKeys.USER_ID", model);
        quotaService.recordUnattributedUsage();
    }
}
