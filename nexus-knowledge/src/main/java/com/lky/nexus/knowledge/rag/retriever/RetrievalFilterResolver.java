package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把"标签 / 目录"过滤条件解析成文档 id 集合，供向量检索下推。
 *
 * <p><b>为什么需要它</b>：pgvector 表里只有 {@code kb_id} 与 {@code document_id}，
 * 没有标签和目录列。ES 那一路可以把 tags/folder 直接拼进查询（索引里有这些 keyword 字段），
 * 向量那一路却不能——所以必须先在关系库里把条件"翻译"成一批文档 id。
 * 反过来说：{@link com.lky.nexus.knowledge.vector.VectorStore} 的签名里
 * <b>不</b>接受 {@link RetrievalFilters}，只接受 documentIds，就是为了不让它有机会
 * "收到标签然后忽略掉"——被静默忽略的过滤条件比没有过滤条件危险得多。
 *
 * <p><b>关系库是唯一事实来源</b>：标签取 {@code kb_document_tag}、目录取 {@code kb_document.folder}，
 * 而不是 ES 索引里的副本。ES 里那份是索引时的快照，重打标签后要等重新索引才一致；
 * 用关系库解析可以保证"昨天刚改的标签今天就能筛"。
 * （两条腿因此可能短暂不一致：关键词腿按快照过滤、向量腿按当前值过滤。
 * 实际影响有限——改标签会改变文档 hash 从而触发重新索引，见增量导入的 SHA-256 对账。）
 *
 * <p><b>组合语义</b>：三个条件是 AND，逐个求交集；标签内部是 OR（见 {@link RetrievalFilters}）。
 *
 * <p><b>为什么是 public</b>：它是检索实现的协作者，而检索实现的测试分布在多个包里
 * （门面测试、parity 测试都要亲手组装 {@code VectorRetriever}）。
 * 把可测性放在"包的私密性"之上——一个构造不出协作者的实现，最后一定被测试绕过去（
 * 比如再造一个假的实现），那才是真的失控。
 *
 * <p><b>解析结果为空 vs 没有条件</b>：这是本类存在的最关键区别。
 * 用户按标签筛、而这个标签谁都没有时，结果必须是<b>空</b>；若把"解析不到"当成"没有条件"，
 * 过滤就会静默消失，用户看到的是"筛了等于没筛"的全库结果。
 * 因此这里返回明确的 {@link FilterPlan#nothing()}，由调用方短路成空结果。
 */
@Component
public class RetrievalFilterResolver {

    private static final Logger log = LoggerFactory.getLogger(RetrievalFilterResolver.class);

    /** LIKE 的转义字符：选 {@code !} 而不是反斜杠，避免 MySQL 字符串字面量二次转义。 */
    private static final char LIKE_ESCAPE = '!';

    private final DocumentTagMapper documentTagMapper;
    private final DocumentMapper documentMapper;

    public RetrievalFilterResolver(DocumentTagMapper documentTagMapper, DocumentMapper documentMapper) {
        this.documentTagMapper = documentTagMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * 解析过滤条件。
     *
     * @param knowledgeBaseId 只在该库内解析——过滤条件绝不能跨库生效
     */
    public FilterPlan resolve(Long knowledgeBaseId, RetrievalFilters filters) {
        if (filters == null || filters.isEmpty()) {
            return FilterPlan.none();
        }
        List<Long> documentIds = null;   // null = 这个维度还没参与，与"求交集得到空集"必须区分开
        if (filters.hasTags()) {
            documentIds = intersect(documentIds,
                    documentTagMapper.selectDocumentIdsByTags(knowledgeBaseId, filters.tags()));
        }
        if (filters.hasFolder()) {
            String folder = filters.folder();
            documentIds = intersect(documentIds, documentMapper.selectIdsByFolder(
                    knowledgeBaseId, folder, likePrefix(folder)));
        }
        if (filters.hasDocumentIds()) {
            documentIds = intersect(documentIds, filters.documentIds());
        }
        if (documentIds == null || documentIds.isEmpty()) {
            // 条件给全了却一篇都没命中：明确"空"，而不是"不过滤"
            log.debug("过滤条件在知识库 {} 内未命中任何文档：{}", knowledgeBaseId, filters.describe());
            return FilterPlan.nothing();
        }
        return FilterPlan.limited(documentIds);
    }

    /** 求交集；{@code current} 为 null 表示该维度尚未参与。 */
    private static List<Long> intersect(List<Long> current, List<Long> next) {
        if (current == null) {
            return distinct(next);
        }
        LinkedHashSet<Long> intersection = new LinkedHashSet<>(current);
        intersection.retainAll(next);
        return List.copyOf(intersection);
    }

    /** 去重（查库结果理论上已去重，显式指定的一批 id 可能有重复）。 */
    private static List<Long> distinct(List<Long> ids) {
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    /**
     * 目录的"含子目录"前缀：{@code 算法} → {@code 算法/%}。
     *
     * <p>转义目录名里可能出现的 {@code %} / {@code _}——{@code _} 在笔记命名里很常见
     * （{@code day_01}），不转义会把 {@code day101} 也一起匹配进来。
     */
    public static String likePrefix(String folder) {
        StringBuilder escaped = new StringBuilder(folder.length() + 8);
        for (int i = 0; i < folder.length(); i++) {
            char c = folder.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.append('/').append('%').toString();
    }

    /**
     * 解析结果。
     *
     * @param matchNothing 条件命中零篇文档 → 检索结果必须为空
     *                     （而不是"不过滤"；两者混淆会让过滤静默消失）
     * @param documentIds  向量检索可下推的文档 id 集合（{@code matchNothing} 时无意义）
     */
    public record FilterPlan(boolean matchNothing, List<Long> documentIds) {

        public static FilterPlan none() {
            return new FilterPlan(false, List.of());
        }

        /**
         * 条件命中零篇：检索结果必须为空。
         *
         * <p>方法名不能叫 {@code matchNothing()}——那会与记录分量 {@code matchNothing}
         * 的访问器同签名冲突（Java 里这两者会撞在一起，编译器报"方法已定义"）。
         */
        public static FilterPlan nothing() {
            return new FilterPlan(true, List.of());
        }

        public static FilterPlan limited(List<Long> documentIds) {
            return new FilterPlan(false, List.copyOf(documentIds));
        }

        /** 有没有实际的文档范围限定（供实现决定要不要在 SQL 里加条件）。 */
        public boolean hasDocumentScope() {
            return !documentIds().isEmpty();
        }

        /** 供日志：让"这次检索到底限定到了哪些文档"可回放。 */
        public String describe() {
            if (matchNothing()) {
                return "命中 0 篇（过滤条件无匹配）";
            }
            return documentIds().isEmpty() ? "不限文档" : "限定 " + documentIds().size() + " 篇";
        }

        /** 便于测试与日志的稳定输出（顺序无关的集合语义）。 */
        public List<Long> sortedDocumentIds() {
            List<Long> sorted = new ArrayList<>(documentIds());
            sorted.sort(Long::compareTo);
            return List.copyOf(sorted);
        }
    }
}
