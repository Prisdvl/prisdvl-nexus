package com.lky.nexus.app.workflow;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受限变量解析器：只支持 input.*、nodes.<id>.output.*、数组索引和纯字符串替换。
 * 不执行脚本，不提供方法调用和反射能力。
 */
@Component
public class WorkflowVariableResolver {

    private static final Pattern EXPRESSION = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    public Object resolve(String expression, Map<String, Object> nodeOutputs, Map<String, Object> inputs) {
        String path = expression == null ? "" : expression.trim();
        if (!EXPRESSION.matcher(path).matches()) {
            throw new IllegalArgumentException("变量必须是单个 {{ ... }} 表达式: " + expression);
        }
        String reference = path.substring(2, path.length() - 2).trim();
        if (reference.startsWith("input.")) {
            return lookup(inputs, reference.substring("input.".length()), reference);
        }
        if (reference.startsWith("nodes.")) {
            String rest = reference.substring("nodes.".length());
            String[] parts = rest.split("\\.", 3);
            if (parts.length != 3 || !"output".equals(parts[1])) {
                throw new IllegalArgumentException("变量只能引用节点 output: " + reference);
            }
            Object node = nodeOutputs.get(parts[0]);
            if (node == null) {
                throw new IllegalArgumentException("节点尚未产生输出: " + parts[0]);
            }
            return lookup(node, parts[2], reference);
        }
        throw new IllegalArgumentException("变量前缀只能是 input. 或 nodes.: " + reference);
    }

    public String render(String template, Map<String, Object> nodeOutputs, Map<String, Object> inputs) {
        if (template == null || template.isBlank()) {
            return template;
        }
        Matcher matcher = EXPRESSION.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = resolve(matcher.group(0), nodeOutputs, inputs);
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Object lookup(Object root, String path, String reference) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            int index = indexOfArrayIndex(segment);
            if (index >= 0) {
                current = read(current, segment.substring(0, index), reference);
                current = readArray(current, Integer.parseInt(segment.substring(index + 1, segment.length() - 1)), reference);
            } else {
                current = read(current, segment, reference);
            }
            if (current == null) {
                throw new IllegalArgumentException("变量路径不存在: " + reference);
            }
        }
        return current;
    }

    private Object read(Object current, String key, String reference) {
        if (key.isBlank()) {
            return current;
        }
        if (current instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value == null) {
                throw new IllegalArgumentException("变量路径不存在: " + reference);
            }
            return value;
        }
        throw new IllegalArgumentException("变量路径类型不支持: " + reference);
    }

    private Object readArray(Object current, int index, String reference) {
        if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) {
            throw new IllegalArgumentException("数组索引不存在: " + reference);
        }
        return list.get(index);
    }

    private int indexOfArrayIndex(String segment) {
        int start = segment.indexOf('[');
        int end = segment.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return -1;
        }
        String index = segment.substring(start + 1, end);
        return index.chars().allMatch(Character::isDigit) ? start : -1;
    }
}
