package com.lky.nexus.ai.rag.retriever;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 检索过滤条件（元数据过滤）。
 *
 * <p>为什么要有它：检索不只是"按相似度取前 K 条"。真实使用里大量查询要带限定——
 * "只在这几个文档里找"、"只要 #算法 这个标签的笔记"、"只看某个文件夹下的"。
 * 没有过滤条件时，用户只能把无关内容也算进召回，再靠重排和上下文预算去挤，
 * 既费钱又不准。
 *
 * <p>三个字段都是有意的"可下推"条件：ES 侧已有 {@code tags} / {@code folder} /
 * {@code document_id} 字段（keyword 类型），向量侧可以按 {@code document_id} 过滤，
 * 因此它们不需要重新索引就能生效。
 *
 * <p><b>组合语义（三个条件是 AND）</b>：
 * <ul>
 *   <li>{@code tags} 内部是 <b>any-of（OR）</b>：勾选"算法"和"Java"两个标签，
 *       意思是"任一匹配"，与 Obsidian 的 {@code tag:#a OR tag:#b} 一致。
 *       若哪天要表达"同时具备两个标签"，那是另一个字段（allTags），不能靠改这里的意思来实现——
 *       同一字段在不同调用点语义不同，是检索结果对不上号最常见的原因。</li>
 *   <li>{@code folder} 匹配该目录 <b>及其子目录</b>：在目录树上点一个节点，
 *       用户的预期是"这个文件夹下面所有的东西"，而不是"正好放在这一层的文件"。</li>
 *   <li>{@code documentIds} 是显式指定的一批文档（如"只在这几篇里找"）。</li>
 * </ul>
 *
 * <p><b>空值语义</b>：{@code null} 与空集合都表示"不过滤"。
 * 用 {@link #none()} 拿一个明确的空条件，避免实现里到处判 {@code null}。
 *
 * <p><b>一个必须守住的区别</b>：{@code tags} 非空、但因为该标签在库内谁都没有而
 * <b>解析出零篇文档</b>，这与"没给条件"是两码事——前者必须返回空结果，
 * 后者才是不过滤。把前者当成后者（"解析不到就成了不过滤"）会让过滤条件静默消失，
 * 用户看到的是"我明明按标签筛了，怎么还是全库结果"，且没有任何报错。
 */
public record RetrievalFilters(List<String> tags, String folder, List<Long> documentIds) {

    /** 归一化：把 null 收敛成空集合，调用方不必判空。 */
    public RetrievalFilters {
        tags = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        // 去重与 tags 同理：这两个字段都是"集合"，重复项不影响检索结果（SQL 的 IN 与解析器的
        // 求交集都会去重），但会让 describe() 报出"限定 3 篇"而实际只有 2 篇——
        // 一行会撒谎的日志比没有日志更糟（它正是用来回答"这次到底限定了什么"的）。
        documentIds = documentIds == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(documentIds));
        folder = folder == null || folder.isBlank() ? null : folder;
    }

    /** 不过滤。 */
    public static RetrievalFilters none() {
        return new RetrievalFilters(List.of(), null, List.of());
    }

    /** 是否等价于"不过滤"（供实现决定要不要拼过滤子句）。 */
    public boolean isEmpty() {
        return tags.isEmpty() && documentIds.isEmpty() && folder == null;
    }

    /** 有没有给标签条件。 */
    public boolean hasTags() {
        return !tags.isEmpty();
    }

    /** 有没有给目录条件。 */
    public boolean hasFolder() {
        return folder != null;
    }

    /** 有没有显式指定文档。 */
    public boolean hasDocumentIds() {
        return !documentIds.isEmpty();
    }

    /**
     * 人可读的条件描述，用于检索日志。
     *
     * <p>存在意义与 Phase 3.2 的那行"实际生效的参数"一样：过滤条件最容易出现的问题是
     * "我加了条件怎么没反应"，而只有把生效的条件写进日志才能回答它。
     */
    public String describe() {
        if (isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        if (hasTags()) {
            text.append("tags=").append(tags);
        }
        if (hasFolder()) {
            text.append(text.isEmpty() ? "" : " ").append("folder=").append(folder);
        }
        if (hasDocumentIds()) {
            text.append(text.isEmpty() ? "" : " ").append("docs=").append(documentIds.size()).append(" 篇");
        }
        return text.toString();
    }
}

