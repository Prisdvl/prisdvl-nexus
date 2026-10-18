package com.lky.nexus.app.workflow;

import com.lky.nexus.app.workflow.model.WorkflowEdge;
import com.lky.nexus.app.workflow.model.WorkflowGraph;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 发布前的静态图校验。这里只做结构、引用和受限变量校验，不执行任何节点。 */
@Component
public class WorkflowGraphValidator {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Set<String> TYPES = Set.of(
            "START", "END", "KNOWLEDGE_RETRIEVAL", "LLM", "AGENT", "IF_ELSE", "TEMPLATE", "HUMAN_APPROVAL");

    public void validate(WorkflowGraph graph) {
        if (graph == null) {
            throw invalid("工作流图不能为空");
        }
        if (graph.schemaVersion() != 1) {
            throw invalid("不支持的工作流 schemaVersion: " + graph.schemaVersion());
        }

        Map<String, WorkflowNode> nodes = new HashMap<>();
        for (WorkflowNode node : graph.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                throw invalid("节点 id 不能为空");
            }
            if (!node.id().matches("[A-Za-z0-9_-]{1,64}")) {
                throw invalid("节点 id 只能包含字母、数字、下划线和短横线: " + node.id());
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                throw invalid("节点 id 重复: " + node.id());
            }
            if (node.type() == null || !TYPES.contains(node.type())) {
                throw invalid("不支持的节点类型: " + node.type());
            }
        }

        List<WorkflowNode> starts = graph.nodes().stream()
                .filter(node -> "START".equals(node.type())).toList();
        if (starts.size() != 1) {
            throw invalid("工作流必须有且只有一个 START 节点");
        }
        if (graph.nodes().stream().noneMatch(node -> "END".equals(node.type()))) {
            throw invalid("工作流缺少 END 节点");
        }

        Map<String, Set<String>> adjacency = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        Map<String, Set<String>> branchPorts = new HashMap<>();
        for (WorkflowEdge edge : graph.edges()) {
            if (edge.id() == null || edge.id().isBlank() || !edgeIds.add(edge.id())) {
                throw invalid("连线 id 缺失或重复");
            }
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                throw invalid("连线引用了不存在的节点: " + edge.source() + " → " + edge.target());
            }
            if (edge.source().equals(edge.target())) {
                throw invalid("节点不能连接自己: " + edge.source());
            }
            if ("IF_ELSE".equals(nodes.get(edge.source()).type())) {
                branchPorts.computeIfAbsent(edge.source(), key -> new HashSet<>()).add(edge.sourceHandle());
            }
            adjacency.computeIfAbsent(edge.source(), key -> new HashSet<>()).add(edge.target());
        }
        for (Map.Entry<String, Set<String>> entry : branchPorts.entrySet()) {
            if (!entry.getValue().contains("true") || !entry.getValue().contains("false")) {
                throw invalid("条件分支必须同时连接 true 和 false 端口: " + entry.getKey());
            }
        }
        if (hasCycle(adjacency)) {
            throw invalid("工作流图不能存在环");
        }

        String startId = starts.get(0).id();
        Set<String> reachable = reachable(adjacency, startId);
        for (WorkflowNode node : graph.nodes()) {
            if (!reachable.contains(node.id())) {
                throw invalid("节点无法从 START 到达: " + node.id());
            }
        }

        for (WorkflowNode node : graph.nodes()) {
            validateConfig(node, nodes, reachable);
        }
    }

    private void validateConfig(WorkflowNode node,
                                Map<String, WorkflowNode> nodes,
                                Set<String> reachable) {
        Map<String, Object> config = node.config();
        switch (node.type()) {
            case "LLM" -> {
                requireText(config, "userPrompt", node.id());
                String prompt = String.valueOf(config.get("userPrompt"));
                validateVariables(prompt, node.id(), nodes, reachable);
            }
            case "AGENT" -> {
                Object agentId = config.get("agentId");
                if (agentId == null || String.valueOf(agentId).isBlank()) {
                    throw invalid("AGENT 节点必须配置 agentId: " + node.id());
                }
            }
            case "KNOWLEDGE_RETRIEVAL" -> {
                Object kbIds = config.get("kbIds");
                if (!(kbIds instanceof List<?> list) || list.isEmpty()) {
                    throw invalid("KNOWLEDGE_RETRIEVAL 节点必须配置 kbIds: " + node.id());
                }
                String query = String.valueOf(config.getOrDefault("query", "{{ input.question }}"));
                validateVariables(query, node.id(), nodes, reachable);
            }
            case "TEMPLATE" -> {
                String template = String.valueOf(config.get("template"));
                if (template.isBlank()) {
                    throw invalid("TEMPLATE 节点必须配置 template: " + node.id());
                }
                validateVariables(template, node.id(), nodes, reachable);
            }
            case "IF_ELSE" -> {
                Object left = config.get("left");
                Object right = config.get("right");
                Object operator = config.get("operator");
                if (operator == null || String.valueOf(operator).isBlank()) {
                    throw invalid("IF_ELSE 节点必须配置 operator: " + node.id());
                }
                validateOperand(left, node.id(), nodes, reachable);
                validateOperand(right, node.id(), nodes, reachable);
            }
            default -> {
            }
        }
    }

    private void validateOperand(Object value, String nodeId,
                                 Map<String, WorkflowNode> nodes,
                                 Set<String> reachable) {
        if (value instanceof String text && text.contains("{{")) {
            validateVariables(text, nodeId, nodes, reachable);
        }
    }

    private void validateVariables(String text,
                                   String nodeId,
                                   Map<String, WorkflowNode> nodes,
                                   Set<String> reachable) {
        Matcher matcher = VARIABLE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String reference = matcher.group(1).trim();
            String[] parts = reference.split("\\.");
            if (parts.length < 2) {
                throw invalid("变量格式非法: " + reference);
            }
            if ("input".equals(parts[0])) {
                continue;
            }
            if (!"nodes".equals(parts[0]) || parts.length < 3 || !"output".equals(parts[1])) {
                throw invalid("变量只能引用 input 或节点 output: " + reference);
            }
            String upstream = parts[2];
            if (!nodes.containsKey(upstream) || !reachable.contains(upstream) || upstream.equals(nodeId)) {
                throw invalid("变量引用的上游节点不存在: " + reference);
            }
        }
    }

    private boolean hasCycle(Map<String, Set<String>> adjacency) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : adjacency.keySet()) {
            if (hasCycle(node, adjacency, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycle(String node,
                             Map<String, Set<String>> adjacency,
                             Set<String> visiting,
                             Set<String> visited) {
        if (visiting.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        visiting.add(node);
        for (String next : adjacency.getOrDefault(node, Set.of())) {
            if (hasCycle(next, adjacency, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        return false;
    }

    private Set<String> reachable(Map<String, Set<String>> adjacency, String start) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(start);
        seen.add(start);
        while (!pending.isEmpty()) {
            for (String next : adjacency.getOrDefault(pending.pop(), Set.of())) {
                if (seen.add(next)) {
                    pending.push(next);
                }
            }
        }
        return seen;
    }

    private void requireText(Map<String, Object> config, String key, String nodeId) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw invalid(key + " 不能为空: " + nodeId);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.WORKFLOW_DEFINITION_INVALID, message);
    }
}
