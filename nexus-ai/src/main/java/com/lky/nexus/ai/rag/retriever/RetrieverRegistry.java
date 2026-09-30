package com.lky.nexus.ai.rag.retriever;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 策略注册表：把 {@link RetrievalStrategy} 派发到具体 {@link Retriever}。
 *
 * <p>实现由 Spring 注入（所有 {@code Retriever} bean），注册表本身不认识任何具体实现——
 * 因此"新增一种检索策略"= 加一个枚举值 + 加一个实现类，业务代码零改动。
 *
 * <p><b>两个刻意的设计选择</b>：
 * <ol>
 *   <li><b>同一策略被两个实现认领时启动即失败</b>。这种情况没有"合理的默认行为"可退：
 *       选错实现会让检索结果静默变差（还记得上次是谁覆盖了谁吗），
 *       远不如让应用起不来、把问题暴露在启动日志里；</li>
 *   <li><b>请求了没人认领的策略时抛 1001 而不是返回空</b>。"没有结果"和"这个策略没实现"
 *       是完全不同的事——返回空会让上游以为知识库是空的，然后据此回答"资料中没有相关内容"，
 *       而真正的原因是配置写错了。</li>
 * </ol>
 */
@Component
public class RetrieverRegistry {

    private static final Logger log = LoggerFactory.getLogger(RetrieverRegistry.class);

    private final Map<RetrievalStrategy, Retriever> byStrategy;

    public RetrieverRegistry(List<Retriever> retrievers) {
        Map<RetrievalStrategy, Retriever> map = new EnumMap<>(RetrievalStrategy.class);
        for (Retriever retriever : retrievers) {
            for (RetrievalStrategy strategy : retriever.strategies()) {
                Retriever previous = map.put(strategy, retriever);
                if (previous != null) {
                    // 报错里同时给"实现名 + 类名"：实现名（如 vector / keyword）是运维与日志里用的标识，
                    // 类名才是能去代码里定位的那个。只报类名的话，同一类的两个不同配置实例
                    // 会打印出一模一样的两个字串，等于什么都没说。
                    throw new IllegalStateException(
                            "检索策略 %s 被两个实现同时认领：%s 与 %s。策略与实现必须一一对应，否则检索结果取决于加载顺序。"
                                    .formatted(strategy, describe(previous), describe(retriever)));
                }
            }
        }
        this.byStrategy = Map.copyOf(map);
        log.info("检索策略注册完成：{}", describe());
    }

    /**
     * 取承接该策略的实现。
     *
     * @throws BusinessException 1001：该策略当前没有实现
     */
    public Retriever get(RetrievalStrategy strategy) {
        RetrievalStrategy target = strategy == null ? RetrievalStrategy.DEFAULT : strategy;
        Retriever retriever = byStrategy.get(target);
        if (retriever == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "检索策略 %s 尚未启用（当前可用：%s）".formatted(target, supported()));
        }
        return retriever;
    }

    /** 该策略是否有实现（供上层决定"能不能用重排"这类问题，而不是靠 try/catch）。 */
    public boolean supports(RetrievalStrategy strategy) {
        return strategy != null && byStrategy.containsKey(strategy);
    }

    /** 已启用的策略。 */
    public Set<RetrievalStrategy> supported() {
        return byStrategy.keySet();
    }

    /** 策略 → 实现名，用于日志与观测。 */
    public Map<String, String> describe() {
        Map<String, String> described = new LinkedHashMap<>();
        byStrategy.forEach((strategy, retriever) -> described.put(strategy.name(), retriever.name()));
        return described;
    }

    /** 已注册实现的名单（观测用）。 */
    public List<String> names() {
        return byStrategy.values().stream().map(Retriever::name).distinct().collect(Collectors.toList());
    }

    /** 实现的可读标识：实现名 + 类名。 */
    private static String describe(Retriever retriever) {
        return "%s（%s）".formatted(retriever.name(), retriever.getClass().getSimpleName());
    }
}
