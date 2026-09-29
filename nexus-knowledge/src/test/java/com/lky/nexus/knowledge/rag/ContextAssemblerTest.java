package com.lky.nexus.knowledge.rag;

import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文组装测试（纯逻辑）。
 *
 * <p>这三件事一旦出错都会"静默地"损害回答质量：重复资料挤占预算、
 * 编号与引用对不上、超预算时悄悄截断让模型以为资料就这些。
 */
class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    @Test
    @DisplayName("编号从 1 开始且带文档标题与标题路径（引用要能定位）")
    void numbersSegmentsWithLocation() {
        var context = assembler.assemble(List.of(
                hit(1L, "Redis 缓存击穿", "缓存击穿 > 互斥锁", "用 SETNX 加锁，只让一个请求去数据库。"),
                hit(2L, "JVM", "垃圾回收 > G1", "G1 按 Region 划分堆内存。")), 2000);

        assertThat(context.used()).hasSize(2);
        assertThat(context.text())
                .contains("[1]《Redis 缓存击穿》 › 缓存击穿 > 互斥锁")
                .contains("[2]《JVM》 › 垃圾回收 > G1");
        assertThat(context.truncated()).isZero();
    }

    @Test
    @DisplayName("去重：重叠切片（一个包含另一个）只保留一次，避免重复占用预算")
    void deduplicatesOverlappingSegments() {
        String body = "缓存击穿是指热点 key 失效后大量请求直接打到数据库，可用互斥锁解决。";
        var context = assembler.assemble(List.of(
                hit(1L, "Redis", "缓存击穿", body),
                // 相邻切片带 overlap：开头与上一段重合
                hit(2L, "Redis", "缓存击穿", body + "另外还可以用逻辑过期方案。")), 2000);

        assertThat(context.used()).as("包含关系应判为重复").hasSize(1);
    }

    @Test
    @DisplayName("Token 预算：超出时丢弃低分片段并如实计数（而不是静默截断）")
    void enforcesTokenBudgetAndReportsTruncation() {
        // 每段约 500 个中文字符 ≈ 500 token，预算 600 只能放下一段。
        // 注意三段内容必须不同——否则会被去重逻辑判为重复（第一版测试就踩了这个）
        var context = assembler.assemble(List.of(
                hit(1L, "A", "第一节", "甲".repeat(500)),
                hit(2L, "B", "第二节", "乙".repeat(500)),
                hit(3L, "C", "第三节", "丙".repeat(500))), 600);

        assertThat(context.used()).hasSize(1);
        assertThat(context.truncated()).isEqualTo(2);
        assertThat(context.tokens()).isLessThanOrEqualTo(600);
    }

    @Test
    @DisplayName("空结果：返回空上下文而不是抛异常（调用方据此跳过模型调用）")
    void handlesEmptyInput() {
        var context = assembler.assemble(List.of(), 1000);

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.text()).isEmpty();
    }

    private ChunkSearchService.SearchHit hit(Long chunkId, String title, String headingPath, String content) {
        return new ChunkSearchService.SearchHit(chunkId, 100L + chunkId, title, headingPath, content,
                0.5, "hybrid", List.of("BM25 #1"));
    }
}
