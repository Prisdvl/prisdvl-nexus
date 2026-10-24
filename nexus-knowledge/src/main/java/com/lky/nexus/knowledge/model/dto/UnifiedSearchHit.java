package com.lky.nexus.knowledge.model.dto;

import java.util.List;

/**
 * 一条跨库命中。
 *
 * <p><b>为什么带 {@code kbId} + {@code kbName}</b>：跨库检索的结果混在一起，
 * 用户必须能一眼看出这条来自哪个库，否则"这条资料到底在哪"就丢了。
 * 名字是服务端补齐的，前端不该为了显示一个库名再去拉一次列表。
 *
 * <p><b>为什么比契约多一个 {@code documentId}</b>：命中详情要能"点开原文预览"，
 * 而预览走的是文档接口，只认文档 id。少这个字段，前端就得拿标题去猜。
 *
 * @param kbId          来源知识库 id
 * @param kbName        来源知识库名称
 * @param chunkId       切片 id（引用定位用）
 * @param documentId    文档 id（打开预览用）
 * @param documentTitle 文档标题；切片已被删除时为 {@code null}
 * @param headingPath   标题路径（如「二、安装 / 2.1 Linux」）
 * @param snippet       正文片段（服务端按 {@code SNIPPET_MAX_CHARS} 截断）
 * @param score         相关性分数：单路时为该路原始分，融合时为 RRF 分，重排后为模型分
 * @param source        {@code bm25} / {@code vector} / {@code hybrid} / {@code rerank}
 * @param matchedBy     命中说明，如 {@code ["BM25 #2", "向量 #1"]}
 */
public record UnifiedSearchHit(
        Long kbId,
        String kbName,
        Long chunkId,
        Long documentId,
        String documentTitle,
        String headingPath,
        String snippet,
        double score,
        String source,
        List<String> matchedBy) {
}
