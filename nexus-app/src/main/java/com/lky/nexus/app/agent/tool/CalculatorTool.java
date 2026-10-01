package com.lky.nexus.app.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 计算器工具（4.3 的第二个内置工具，§十九）。
 *
 * <p><b>为什么第二个工具是它</b>：Tool 体系要证明的那条缝是"一个 Agent 可以同时装配多个工具、
 * 按 Definition 决定装哪些"。挑计算器是因为它<b>确定性、无网络、无副作用</b>——
 * 能把"装配"这件事验清楚，又不引入任何新的失败模式（对比之下，HTTP 工具的第一版会是
 * 一个 SSRF 面，那是 P1 该带着权限与审批一起做的事）。
 *
 * <p><b>为什么自己写求值器而不是用脚本引擎</b>：任何"把模型给的字符串当代码执行"的做法
 * （{@code ScriptEngine} / {@code eval} 之类）都是把一个语言运行时暴露给模型。
 * 这个类只认下面那一套四则运算语法，别的字符一律报错——解析器的递归下降实现很短，
 * 而它可能被滥用的面为零。
 *
 * <p>数值用 {@link BigDecimal} 而不是 {@code double}：模型算的常常是金额与百分比，
 * {@code 0.1 + 0.2} 出 {@code 0.30000000000000004} 会被它照抄进回答里，用户看到的就是"算错了"。
 *
 * <p><b>本类不调 {@code AgentContext.record}</b>，这是<b>刻意</b>的而不是漏了：它的结果从入参和
 * 返回值就直接看得出，没有只有它自己知道的细节可写，因此登记时 {@code selfReports = false}，
 * 过程痕迹由执行壳兜底补上（{@code ToolCatalog}）。反例是 {@code KnowledgeSearchTool}——
 * 它要上报"5 条结果（跳过 1 个库）""已降级为关键词匹配""检索不可用：&lt;异常&gt;"这些壳给不出的话，
 * 所以它自己报。两边合起来满足的不变量是：
 * <b>每一次成功的工具调用恰好产生一条痕迹</b>（由 {@code ToolResourceTest} 逐条断言，见 decisions.md B.40）。
 */
@Component
public class CalculatorTool {

    /** 表达式长度上限：挡住病态输入，正常提问远不到这个量级。 */
    private static final int MAX_LENGTH = 500;

    @Tool(name = "calculator",
            description = "计算数学表达式并返回精确结果。涉及算术、百分比、复利、单位换算等需要准确数字的问题时"
                    + "必须先调用本工具，不要心算、也不要凭估算回答。"
                    + "表达式支持 + - * / % ^、括号、一元负号，函数 sqrt/abs/round/floor/ceil/pow/min/max，"
                    + "常数 pi/e。例：(1+2)*3/4、sqrt(2)、round(3.14159, 2)、max(3, 7)。"
                    + "本工具只做计算，不能查资料、不能访问网络。")
    public String calculate(
            @ToolParam(description = "要计算的数学表达式，例如 (1+2)*3/4 或 100*1.05^3") String expression) {
        if (!StringUtils.hasText(expression)) {
            throw new IllegalArgumentException("表达式为空：请给出要计算的算式");
        }
        String text = expression.trim();
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("表达式过长（上限 %d 个字符）".formatted(MAX_LENGTH));
        }
        BigDecimal value = new Parser(text).parseAll();
        return "%s = %s".formatted(text, format(value));
    }

    /** 去掉多余的小数尾零（2.2500 → 2.25，4.000000000 → 4），让模型抄进回答里也是干净的。 */
    private static String format(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        // toPlainString：避免科学计数法（1E+2 这种形状模型会照样抄给用户）
        return stripped.toPlainString();
    }

    /**
     * 递归下降求值器：只认下面这套语法，遇到别的字符直接报错。
     *
     * <pre>
     * expression := term (('+' | '-') term)*
     * term       := unary (('*' | '/' | '%') unary)*
     * unary      := ('+' | '-') unary | power
     * power      := primary ('^' unary)?          // 右结合：2^3^2 = 2^9
     * primary    := number | '(' expression ')' | ident '(' args ')' | ident
     * </pre>
     */
    private static final class Parser {

        /** 除法与开方保留 20 位有效数字：足够任何现实计算，又不至于像 double 那样漂。 */
        private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        /** 解析整个表达式；尾部有多余字符（"1+2abc"）也是错误，不能只取前面一段当答案。 */
        BigDecimal parseAll() {
            BigDecimal value = expression();
            skipSpaces();
            if (pos < text.length()) {
                throw new IllegalArgumentException("表达式里有无法识别的内容：" + text.substring(pos));
            }
            return value;
        }

        private BigDecimal expression() {
            BigDecimal left = term();
            while (true) {
                skipSpaces();
                if (eat('+')) {
                    left = left.add(term(), MC);
                } else if (eat('-')) {
                    left = left.subtract(term(), MC);
                } else {
                    return left;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal left = unary();
            while (true) {
                skipSpaces();
                if (eat('*')) {
                    left = left.multiply(unary(), MC);
                } else if (eat('/')) {
                    left = left.divide(requireNonZero(unary(), "除法"), MC);
                } else if (eat('%')) {
                    left = left.remainder(requireNonZero(unary(), "取余"), MC);
                } else {
                    return left;
                }
            }
        }

        private BigDecimal unary() {
            skipSpaces();
            if (eat('-')) {
                return unary().negate();
            }
            if (eat('+')) {
                return unary();
            }
            return power();
        }

        private BigDecimal power() {
            BigDecimal base = primary();
            skipSpaces();
            if (eat('^')) {
                BigDecimal exponent = unary();
                // 指数必须是整数才能进 BigDecimal.pow；否则走 sqrt/exp 那条路会引入无理数近似，
                // 与其给一个看着精确其实是近似的结果，不如明确报错让模型换个算法
                if (exponent.stripTrailingZeros().scale() > 0) {
                    throw new IllegalArgumentException("指数目前只支持整数（收到 %s）".formatted(exponent.toPlainString()));
                }
                try {
                    return base.pow(exponent.intValueExact(), MC);
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException("指数超出可用范围：" + exponent.toPlainString());
                }
            }
            return base;
        }

        private BigDecimal primary() {
            skipSpaces();
            if (eat('(')) {
                BigDecimal inner = expression();
                skipSpaces();
                if (!eat(')')) {
                    throw new IllegalArgumentException("括号没有闭合");
                }
                return inner;
            }
            if (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                return number();
            }
            if (pos < text.length() && Character.isLetter(text.charAt(pos))) {
                return named();
            }
            throw new IllegalArgumentException(pos >= text.length()
                    ? "表达式在这里就结束了，缺少数字或括号"
                    : "无法识别的位置：" + text.substring(pos));
        }

        private BigDecimal number() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            String raw = text.substring(start, pos);
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("不是合法的数字：" + raw);
            }
        }

        private BigDecimal named() {
            int start = pos;
            while (pos < text.length() && Character.isLetterOrDigit(text.charAt(pos))) {
                pos++;
            }
            String name = text.substring(start, pos).toLowerCase();
            skipSpaces();
            if (!eat('(')) {
                return switch (name) {
                    case "pi" -> new BigDecimal(Math.PI, MC);
                    case "e" -> new BigDecimal(Math.E, MC);
                    // 不给"变量"留口子：模型有时会写 x*2 这种带未知数的式子，
                    // 明确报错比返回一个胡编的值好
                    default -> throw new IllegalArgumentException(
                            "不认识的名字：%s（支持 pi / e 与函数 sqrt, abs, round, floor, ceil, pow, min, max）".formatted(name));
                };
            }
            BigDecimal first = expression();
            java.util.List<BigDecimal> args = new java.util.ArrayList<>();
            args.add(first);
            skipSpaces();
            while (eat(',')) {
                args.add(expression());
                skipSpaces();
            }
            if (!eat(')')) {
                throw new IllegalArgumentException("函数 %s 的括号没有闭合".formatted(name));
            }
            return function(name, args);
        }

        private BigDecimal function(String name, java.util.List<BigDecimal> args) {
            return switch (name) {
                case "sqrt" -> {
                    requireArgs(name, args, 1);
                    yield sqrt(args.get(0));
                }
                case "abs" -> {
                    requireArgs(name, args, 1);
                    yield args.get(0).abs();
                }
                case "floor" -> {
                    requireArgs(name, args, 1);
                    yield args.get(0).setScale(0, RoundingMode.FLOOR);
                }
                case "ceil" -> {
                    requireArgs(name, args, 1);
                    yield args.get(0).setScale(0, RoundingMode.CEILING);
                }
                case "round" -> {
                    // round(x) 与 round(x, 位数) 都接受：模型两种写法都会出
                    if (args.size() == 1) {
                        yield args.get(0).setScale(0, RoundingMode.HALF_UP);
                    }
                    requireArgs(name, args, 2);
                    int scale = args.get(1).intValueExact();
                    if (scale < 0 || scale > 20) {
                        throw new IllegalArgumentException("round 的位数要在 0–20 之间（收到 %d）".formatted(scale));
                    }
                    yield args.get(0).setScale(scale, RoundingMode.HALF_UP);
                }
                case "pow" -> {
                    requireArgs(name, args, 2);
                    int exponent = args.get(1).intValueExact();
                    yield args.get(0).pow(exponent, MC);
                }
                case "min" -> {
                    requireAtLeast(name, args, 1);
                    yield args.stream().min(BigDecimal::compareTo).orElseThrow();
                }
                case "max" -> {
                    requireAtLeast(name, args, 1);
                    yield args.stream().max(BigDecimal::compareTo).orElseThrow();
                }
                default -> throw new IllegalArgumentException(
                        "不支持的函数：%s（支持 sqrt, abs, round, floor, ceil, pow, min, max）".formatted(name));
            };
        }

        /** 牛顿迭代开平方——BigDecimal 没有内置 sqrt，而 Math.sqrt(double) 会丢精度。 */
        private static BigDecimal sqrt(BigDecimal value) {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("负数不能开平方：" + value.toPlainString());
            }
            if (value.signum() == 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal guess = new BigDecimal(Math.sqrt(value.doubleValue()), MC);
            for (int i = 0; i < 30; i++) {
                BigDecimal next = guess.add(value.divide(guess, MC), MC).divide(BigDecimal.valueOf(2), MC);
                if (next.compareTo(guess) == 0) {
                    break;
                }
                guess = next;
            }
            return guess;
        }

        private static BigDecimal requireNonZero(BigDecimal divisor, String what) {
            if (divisor.signum() == 0) {
                throw new IllegalArgumentException("%s 的除数是零，没有定义".formatted(what));
            }
            return divisor;
        }

        private static void requireArgs(String name, java.util.List<BigDecimal> args, int expected) {
            if (args.size() != expected) {
                throw new IllegalArgumentException("%s 需要 %d 个参数（收到 %d 个）".formatted(name, expected, args.size()));
            }
        }

        private static void requireAtLeast(String name, java.util.List<BigDecimal> args, int least) {
            if (args.size() < least) {
                throw new IllegalArgumentException("%s 至少需要 %d 个参数".formatted(name, least));
            }
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private boolean eat(char expected) {
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }
    }
}
