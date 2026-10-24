package com.lky.nexus.app.infra;

import com.lky.nexus.app.automation.AutomationUsageRecorder;
import com.lky.nexus.app.security.QuotaChatUsageRecorder;
import com.lky.nexus.app.security.QuotaService;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.common.security.ChatUsageRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用级 bean 装配（跨层公共组件的装配统一放这里）。
 *
 * <p>{@link ObservabilityRegistry} 在 nexus-common 里是纯 Java 类——
 * common 是零 Spring 依赖的底座，装配是 app 层的事。
 */
@Configuration
public class AppConfig {

    /**
     * 观测注册表：静默降级计数（{@code /api/observability/summary}）与运行指标
     * （{@code /actuator/metrics}）的<b>同一个</b>上报口。
     *
     * <p>传入 {@link MeterRegistry} 之后，{@code increment} 等调用会同步转发给
     * Micrometer，于是两个端点看到的是同一批事实。这里注入的是 Spring Boot 自动配置的
     * 复合注册表，<b>不引入任何指标后端</b>：没有 Prometheus/Grafana 时它只是一个内存里的
     * 注册表 + {@code /actuator/metrics} 的 JSON，这正是本阶段要的粒度。
     */
    @Bean
    public ObservabilityRegistry observabilityRegistry(MeterRegistry meterRegistry) {
        return new ObservabilityRegistry(meterRegistry);
    }

    /**
     * token 记账端口（5.6）：nexus-ai 的 {@code UsageMeteringAdvisor} 只认接口，
     * 具体去向（每日配额、Redis 键）在 app 层。
     *
     * <p>本 bean 存在与否<b>不改变</b> {@code ChatClientFactory} 的构造：那边用
     * {@code ObjectProvider} 取，拿不到就退化成 {@code NOOP}。所以把 ai 层单独拿出来跑
     * （单测、Phase 7 的 mcp-server）不会因为缺这个 bean 而起不来。
     */
    @Bean
    public ChatUsageRecorder chatUsageRecorder(QuotaService quotaService) {
        // 外面套一层自动化用的旁路采集：自动化运行要往 automation_run 落 token，
        // 而用量只在这一条链路上经过。装饰器透传记账语义，只在记账前顺手抄一份到
        // AutomationUsageBridge（同线程、非自动化调用时收集器不存在，等于空操作）。
        // 不能另加一个 ChatUsageRecorder bean——AiConfiguration 用 getIfAvailable() 取，多一个就 NoUniqueBean。
        return new AutomationUsageRecorder(new QuotaChatUsageRecorder(quotaService));
    }
}