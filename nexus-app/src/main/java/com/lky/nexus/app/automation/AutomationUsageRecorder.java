package com.lky.nexus.app.automation;

import com.lky.nexus.common.security.ChatUsageRecorder;

/**
 * {@link ChatUsageRecorder} 的装饰器：先把用量抄给 {@link AutomationUsageBridge}，再委派真实记账。
 *
 * <p>为什么做成装饰器而不是再加一个 {@code @Component}：{@code AiConfiguration} 用
 * {@code ObjectProvider.getIfAvailable()} 取这个端口，<b>多一个同类型 bean 就会 NoUniqueBean</b>；
 * 端口只能有一个实现。所以装配点仍是 {@code AppConfig} 的那一个 {@code @Bean}，
 * 只是里面套了一层（见 {@code AppConfig#chatUsageRecorder}）。
 *
 * <p>顺序是刻意的：先抄后记账。配额记账可能失败（Redis 抖动），但自动化那边的用量
 * 已经与"这次模型调用"绑定，不该因为记账失败而丢掉。
 */
public class AutomationUsageRecorder implements ChatUsageRecorder {

    private final ChatUsageRecorder delegate;

    public AutomationUsageRecorder(ChatUsageRecorder delegate) {
        this.delegate = delegate;
    }

    @Override
    public void record(Long userId, int promptTokens, int completionTokens) {
        AutomationUsageBridge.capture(promptTokens, completionTokens);
        delegate.record(userId, promptTokens, completionTokens);
    }

    @Override
    public void recordUnattributed(String model) {
        delegate.recordUnattributed(model);
    }
}
