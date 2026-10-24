package com.lky.nexus.app.observability;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型价格表：{@code nexus.pricing.<model>.input} / {@code nexus.pricing.<model>.output}
 * （单位：元 / 每 100 万 token）。
 *
 * <p><b>为什么不写成一个 @ConfigurationProperties 的 Map</b>：Map 绑定要求固定的前缀层级
 * （{@code nexus.pricing.models.*}），而契约里写死的是 {@code nexus.pricing.<model>.input}
 * ——模型名本身就是一层键。用 {@link Environment} 直接按拼出来的键读，
 * 既贴住契约，也允许运维只配一个模型而不影响其它模型。
 *
 * <p><b>没配价格就返回 null，绝不返回 0。</b> 这是本类存在的唯一理由：
 * 报表上的 "0 元" 是一个<b>看起来正确的谎言</b>——它会让人以为这个模型免费，
 * 而不是"我们没配价格"。null 在页面上显示成"—"，一眼就知道是缺配置。
 *
 * <p>价格随时可能变（供应商调价），所以不做缓存：每次查询读一次 Environment 是内存操作，
 * 与一次 HTTP 请求相比可以忽略；换来的是改配置即生效，不用重启。
 */
@Component
public class ObservabilityPricing {

    /** Token 的计价单位：每 100 万 token。 */
    private static final BigDecimal PER_TOKENS = BigDecimal.valueOf(1_000_000L);

    private static final String PREFIX = "nexus.pricing.";

    private final Environment environment;

    public ObservabilityPricing(Environment environment) {
        this.environment = environment;
    }

    /**
     * 某个模型的输入单价（元 / 1M token）。
     *
     * @return 未配置时为 {@code null}
     */
    public BigDecimal inputPrice(String model) {
        return readPrice(model, "input");
    }

    /**
     * 某个模型的输出单价（元 / 1M token）。
     *
     * @return 未配置时为 {@code null}
     */
    public BigDecimal outputPrice(String model) {
        return readPrice(model, "output");
    }

    /**
     * 估算一次调用（或一组调用合计）的成本。
     *
     * @param model     模型名
     * @param tokensIn  prompt token；{@code null} 按 0 计（没有量的那部分不产生钱）
     * @param tokensOut completion token；{@code null} 按 0 计
     * @return 成本（元，保留 6 位小数）；<b>任一单价未配置时返回 {@code null}</b>
     */
    public BigDecimal cost(String model, long tokensIn, long tokensOut) {
        BigDecimal input = inputPrice(model);
        BigDecimal output = outputPrice(model);
        if (input == null || output == null) {
            return null;
        }
        BigDecimal cost = input.multiply(BigDecimal.valueOf(tokensIn))
                .add(output.multiply(BigDecimal.valueOf(tokensOut)))
                .divide(PER_TOKENS, 6, RoundingMode.HALF_UP);
        return cost;
    }

    /**
     * 是否给某个模型配了完整价格（输入 + 输出都在）。
     *
     * <p>汇总成本要靠它判断"能不能给一个总数"：窗口里只要有一个模型缺价格，
     * 合计数就必然偏小，此时应该返回 null 并在响应里说明，而不是给一个偏小的数字。
     */
    public boolean priced(String model) {
        return inputPrice(model) != null && outputPrice(model) != null;
    }

    private BigDecimal readPrice(String model, String kind) {
        if (!StringUtils.hasText(model)) {
            return null;
        }
        String name = model.trim();
        // 两种写法都认：模型名里带点的（如 gpt-4.1）必须用中括号形式书写才不会被
        // 当成层级分隔符，所以先按原样拼、再按中括号形式兜底
        BigDecimal value = read(PREFIX + name + "." + kind);
        if (value == null) {
            value = read(PREFIX + "[" + name + "]." + kind);
        }
        return value;
    }

    private BigDecimal read(String key) {
        try {
            return environment.getProperty(key, BigDecimal.class);
        } catch (RuntimeException ex) {
            // 配错格式（例如写了 "0.5 元"）不该让整个观测页 500：当成"没配"处理
            return null;
        }
    }
}
