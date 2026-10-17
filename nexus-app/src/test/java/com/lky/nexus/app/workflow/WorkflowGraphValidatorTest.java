package com.lky.nexus.app.workflow;

import com.lky.nexus.app.workflow.model.WorkflowEdge;
import com.lky.nexus.app.workflow.model.WorkflowGraph;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkflowGraphValidator")
class WorkflowGraphValidatorTest {

    private final WorkflowGraphValidator validator = new WorkflowGraphValidator();

    @Test
    @DisplayName("合法最小图通过校验")
    void acceptsMinimalValidGraph() {
        WorkflowGraph graph = graph(
                node("start", "START", Map.of()),
                node("end", "END", Map.of()),
                edge("edge-1", "start", "end", null)
        );

        assertThatCode(() -> validator.validate(graph)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("普通边成环时拒绝发布")
    void rejectsCycle() {
        WorkflowGraph graph = graph(
                node("start", "START", Map.of()),
                node("a", "LLM", Map.of("userPrompt", "hello")),
                node("end", "END", Map.of()),
                edge("e1", "start", "a", null),
                edge("e2", "a", "end", null),
                edge("e3", "end", "a", null)
        );

        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("环");
    }

    @Test
    @DisplayName("变量引用的上游节点不存在时拒绝发布")
    void rejectsUnknownVariableNode() {
        WorkflowGraph graph = graph(
                node("start", "START", Map.of()),
                node("llm", "LLM", Map.of("userPrompt", "{{ nodes.missing.output.text }}")),
                node("end", "END", Map.of()),
                edge("e1", "start", "llm", null),
                edge("e2", "llm", "end", null)
        );

        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("条件分支缺少 true 端口时拒绝发布")
    void rejectsIncompleteConditionPorts() {
        WorkflowGraph graph = graph(
                node("start", "START", Map.of()),
                node("condition", "IF_ELSE", Map.of("expression", "true")),
                node("end", "END", Map.of()),
                edge("e1", "start", "condition", null),
                edge("e2", "condition", "end", "true")
        );

        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("false");
    }

    private WorkflowGraph graph(Object... nodesAndEdges) {
        List<WorkflowNode> nodes = java.util.Arrays.stream(nodesAndEdges)
                .filter(WorkflowNode.class::isInstance)
                .map(WorkflowNode.class::cast)
                .toList();
        List<WorkflowEdge> edges = java.util.Arrays.stream(nodesAndEdges)
                .filter(WorkflowEdge.class::isInstance)
                .map(WorkflowEdge.class::cast)
                .toList();
        return new WorkflowGraph(1, nodes, edges);
    }

    private WorkflowNode node(String id, String type, Map<String, Object> config) {
        return new WorkflowNode(id, type, 1, null, config);
    }

    private WorkflowEdge edge(String id, String source, String target, String sourceHandle) {
        return new WorkflowEdge(id, source, target, sourceHandle);
    }
}
