package com.lky.nexus.app.workflow.executor;

import com.lky.nexus.app.workflow.WorkflowExecutionContext;
import com.lky.nexus.app.workflow.WorkflowNodeExecutor;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** IF_ELSE 节点：受限操作符比较，不执行脚本表达式。 */
@Component
public class IfElseNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public String type() {
        return "IF_ELSE";
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowExecutionContext context) {
        Object left = operand(node.config().get("left"), context);
        Object right = operand(node.config().get("right"), context);
        String operator = String.valueOf(node.config().get("operator")).trim().toUpperCase();
        boolean branch = evaluate(left, right, operator);
        return Map.of("branch", branch, "left", left, "right", right);
    }

    private Object operand(Object value, WorkflowExecutionContext context) {
        if (value instanceof String text && text.contains("{{")) {
            return context.variable(text);
        }
        return value;
    }

    private boolean evaluate(Object left, Object right, String operator) {
        return switch (operator) {
            case "EQ" -> normalize(left).equals(normalize(right));
            case "NE" -> !normalize(left).equals(normalize(right));
            case "GT" -> compare(left, right) > 0;
            case "GTE" -> compare(left, right) >= 0;
            case "LT" -> compare(left, right) < 0;
            case "LTE" -> compare(left, right) <= 0;
            case "IS_TRUE" -> Boolean.TRUE.equals(left);
            case "IS_FALSE" -> Boolean.FALSE.equals(left);
            case "CONTAINS" -> String.valueOf(left).contains(String.valueOf(right));
            case "NOT_CONTAINS" -> !String.valueOf(left).contains(String.valueOf(right));
            default -> throw new IllegalArgumentException("不支持的条件操作符: " + operator);
        };
    }

    private int compare(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
