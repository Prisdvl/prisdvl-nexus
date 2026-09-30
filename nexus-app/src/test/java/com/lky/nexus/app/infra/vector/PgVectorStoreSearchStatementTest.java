package com.lky.nexus.app.infra.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 检索语句的构造测试（不需要 PostgreSQL）。
 *
 * <p>钉住两件事，它们都不会在出错时报错、只会静默返回错的东西：
 * <ol>
 *   <li><b>限定文档范围时才加 {@code document_id IN (...)}</b>——永远加会让"不限范围"变成"范围为空"，
 *       那就一条都检不出来；永远不加则等于过滤没生效。</li>
 *   <li><b>参数顺序</b>——{@code CAST(? AS vector)} 在语句里出现两次（算分、排序），
 *       加上 kb_id 与 id 列表，插错位置不会抛异常，只会算出莫名其妙的分数。</li>
 * </ol>
 */
@DisplayName("pgvector 检索：文档范围下推与参数顺序")
class PgVectorStoreSearchStatementTest {

    private static final long KB_ID = 1001L;
    private static final String LITERAL = "[0.1,0.2]";

    @Test
    @DisplayName("不限范围：语句里不出现 document_id，参数是 向量 / kb / 向量 / topK")
    void withoutDocumentScope() {
        PgVectorStore.SearchStatement statement =
                PgVectorStore.buildSearchStatement(KB_ID, LITERAL, 5, null);

        assertThat(statement.sql()).doesNotContain("document_id");
        assertThat(statement.arguments()).containsExactly(LITERAL, KB_ID, LITERAL, 5);
    }

    @Test
    @DisplayName("空集合 = 不限范围（'匹配到零篇'由调用方短路，不该走到这里）")
    void emptyDocumentScopeMeansNoScope() {
        PgVectorStore.SearchStatement statement =
                PgVectorStore.buildSearchStatement(KB_ID, LITERAL, 5, List.of());

        assertThat(statement.sql()).doesNotContain("document_id");
        assertThat(statement.arguments()).containsExactly(LITERAL, KB_ID, LITERAL, 5);
    }

    @Test
    @DisplayName("限定范围：document_id IN (?,?) 写进 WHERE，且 id 排在 kb_id 之后、向量之前")
    void withDocumentScope() {
        PgVectorStore.SearchStatement statement =
                PgVectorStore.buildSearchStatement(KB_ID, LITERAL, 3, List.of(7L, 8L));

        assertThat(statement.sql()).contains("WHERE kb_id = ?");
        assertThat(statement.sql()).contains("AND document_id IN (?,?)");
        assertThat(statement.arguments())
                .as("顺序必须与占位符一一对应：算分用的向量 → kb_id → 文档 id → 排序用的向量 → topK")
                .containsExactly(LITERAL, KB_ID, 7L, 8L, LITERAL, 3);
    }

    @Test
    @DisplayName("占位符个数与参数个数必须相等（少一个就是运行时 SQL 异常，多一个会静默错位）")
    void placeholdersMatchArguments() {
        PgVectorStore.SearchStatement statement =
                PgVectorStore.buildSearchStatement(KB_ID, LITERAL, 3, List.of(7L, 8L, 9L));

        long placeholders = statement.sql().chars().filter(c -> c == '?').count();
        assertThat(placeholders).isEqualTo(statement.arguments().length);
        assertThat(statement.sql()).contains("AND document_id IN (?,?,?)");
    }

    @Test
    @DisplayName("LIMIT 在最后：加过滤条件不会把 LIMIT 挤到前面（否则后面的子句永远不生效）")
    void limitStaysLast() {
        PgVectorStore.SearchStatement statement =
                PgVectorStore.buildSearchStatement(KB_ID, LITERAL, 3, List.of(7L));

        assertThat(statement.sql().trim()).endsWith("LIMIT ?");
    }
}
