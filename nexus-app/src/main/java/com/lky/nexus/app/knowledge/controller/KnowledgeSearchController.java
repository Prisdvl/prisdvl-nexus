package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 索引与检索接口。
 *
 * <p>三种模式（BM25 / 向量 / 混合）都开放，用途有两个：
 * ① 让"导入 → 建索引 → 检索"这条链路现在就能被验证与使用；
 * ② 为混合检索提供单路对照（调融合效果时必须能单独看每一路的表现）。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeSearchController {

    private static final int MAX_BATCH_LIMIT = 2000;
    private static final int MAX_TOP_K = 100;

    private final ChunkIndexService chunkIndexService;
    private final ChunkSearchService chunkSearchService;

    public KnowledgeSearchController(ChunkIndexService chunkIndexService,
                                     ChunkSearchService chunkSearchService) {
        this.chunkIndexService = chunkIndexService;
        this.chunkSearchService = chunkSearchService;
    }

    /** 为待索引的切片建立检索索引（幂等，可重复调用）。 */
    @PostMapping("/{knowledgeBaseId}/index")
    public Result<ChunkIndexService.IndexResult> index(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(defaultValue = "500") int batchLimit) {
        int limit = Math.clamp(batchLimit, 1, MAX_BATCH_LIMIT);
        return Result.ok(chunkIndexService.indexPendingChunks(CurrentUser.requireId(), knowledgeBaseId, limit));
    }

    /** 重建索引：新建物理索引 → 原子切换别名 → 旧索引转为待重建。 */
    @PostMapping("/{knowledgeBaseId}/index/recreate")
    public Result<Void> recreate(@PathVariable Long knowledgeBaseId) {
        chunkIndexService.recreateIndex(CurrentUser.requireId(), knowledgeBaseId);
        return Result.ok();
    }

    /**
     * 检索：mode=bm25 / vector / hybrid（默认 hybrid，三者都开放以便对比融合效果）。
     *
     * <p>{@code topK} 不传（或传空）表示"用知识库配置的 retrieval_top_k"，
     * 而不是由服务端写死一个数——这个接口的定位是调参/对照，
     * 所以显式传值时以传的为准（优先级：请求 &gt; 知识库配置 &gt; 全局默认）。
     *
     * <p>元数据过滤（Phase 3.3）：{@code tags} / {@code folder} / {@code documentId} 都是可选的，
     * 同时给多个条件时是 AND 关系；多个标签之间是 OR（与 Obsidian 的
     * {@code tag:#a OR tag:#b} 一致）；{@code folder} 含子目录。
     * 多值参数的写法见 {@link #splitMultiValue}。详见 {@link RetrievalFilters}。
     */
    @GetMapping("/{knowledgeBaseId}/search")
    public Result<List<ChunkSearchService.SearchHit>> search(
            @PathVariable Long knowledgeBaseId,
            @RequestParam String q,
            @RequestParam(required = false) Integer topK,
            @RequestParam(defaultValue = "hybrid") String mode,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) List<String> documentId) {
        Integer limit = topK == null ? null : Math.clamp(topK, 1, MAX_TOP_K);
        RetrievalFilters filters = new RetrievalFilters(splitMultiValue(tags), folder,
                parseDocumentIds(documentId));
        return Result.ok(chunkSearchService.search(CurrentUser.requireId(), knowledgeBaseId, q, limit,
                SearchMode.parse(mode), filters));
    }

    /**
     * 多值查询参数归一：{@code ?tags=a,b} 与 {@code ?tags=a&tags=b} 必须表达同一件事。
     *
     * <p><b>为什么不能交给 Spring 的隐式转换</b>：只有在参数<b>只出现一次</b>时，
     * {@code String → List<String>} 才会按逗号拆；一旦同名参数出现两次，
     * 每个值都被当成一个整体——{@code ?tags=Redis,算法&tags=Redis} 会得到
     * {@code ["Redis, 算法", "Redis"]}，其中第一个是<b>谁也匹配不到的标签</b>。
     * 这种"过滤条件静默变成不存在的标签"不会报错，只会返回空结果，
     * 与"库里真的没有相关内容"完全无法区分——正是本增量最想消灭的失败方式。
     *
     * <p>所以拆分的责任放在边界上（这里），而不是"约定调用方只能用其中一种写法"。
     * 空白项被丢弃：{@code ?tags=a,,b} 与 {@code ?tags=a&tags=b} 等价。
     */
    static List<String> splitMultiValue(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * 文档 id 解析：同样接受逗号分隔与重复传参，但非法值必须<b>报错而不是被忽略</b>。
     *
     * <p>用 {@code List<String>} 收而不是 {@code List<Long>}：后者在
     * {@code ?documentId=7,8} 这种写法上会直接绑定失败，且失败信息是框架的
     * 类型转换异常，对使用者毫无指向性。自己解析可以把"哪个值不对"说清楚
     * （越界/负数的存在性检查交给检索侧，这里只管"是不是数字"）。
     */
    static List<Long> parseDocumentIds(List<String> raw) {
        List<String> values = splitMultiValue(raw);
        if (values.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ex) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "documentId 必须是数字: " + value);
            }
        }
        return ids;
    }
}
