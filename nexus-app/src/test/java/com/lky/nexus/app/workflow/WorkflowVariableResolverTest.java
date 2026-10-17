package com.lky.nexus.app.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkflowVariableResolver")
class WorkflowVariableResolverTest {

    private final WorkflowVariableResolver resolver = new WorkflowVariableResolver();

    @Test
    @DisplayName("解析嵌套节点变量")
    void resolvesNodeVariable() {
        Map<String, Object> outputs = Map.of(
                "retrieve", Map.of("documents", List.of(Map.of("content", "hello")))
        );

        Object value = resolver.resolve("{{ nodes.retrieve.output.documents[0].content }}", outputs, Map.of());

        assertThat(value).isEqualTo("hello");
    }

    @Test
    @DisplayName("解析工作流输入变量")
    void resolvesInputVariable() {
        Object value = resolver.resolve("{{ input.question }}", Map.of(), Map.of("question", "Java 是什么"));

        assertThat(value).isEqualTo("Java 是什么");
    }

    @Test
    @DisplayName("模板文本只替换变量占位符")
    void interpolatesTemplate() {
        String value = resolver.render("问题：{{ input.question }} 资料：{{ nodes.retrieve.output.documents[0].content }}",
                Map.of("retrieve", Map.of("documents", List.of(Map.of("content", "答案")))),
                Map.of("question", "Java 是什么"));

        assertThat(value).isEqualTo("问题：Java 是什么 资料：答案");
    }
}
