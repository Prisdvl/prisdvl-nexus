package com.lky.nexus.app.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计算器工具（4.3 的第二个内置工具）——求值语义与报错措辞。
 *
 * <p>为什么这个工具值得一个专门的测试类：它是<b>唯一一处"模型给的字符串会被当表达式解析"</b>的地方。
 * 测试要钉住的不是"能不能算对"（那是 BigDecimal 的事），而是三件与安全/可信有关的事：
 * <ol>
 *   <li><b>只认这套语法</b>：{@code x*2}、{@code 1+2abc} 这类输入必须报错，而不是被容忍成
 *       "取前面一段"——那等于让模型得到半句话当答案；</li>
 *   <li><b>不引脚本引擎</b>：报错信息里不能出现任何"执行了代码"的痕迹，输入长度有上限；</li>
 *   <li><b>十进制精确</b>：金额与百分比算出来必须能被模型直接抄进回答，
 *       {@code 0.1+0.2} 给 {@code 0.30000000000000004} 就是用户眼里的"算错了"。</li>
 * </ol>
 */
@DisplayName("CalculatorTool：表达式求值与报错")
class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    @Test
    @DisplayName("四则运算与优先级；输出是「原式 = 结果」，模型可直接抄")
    void evaluatesArithmetic() {
        assertThat(tool.calculate("1+2*3")).isEqualTo("1+2*3 = 7");
        assertThat(tool.calculate("(1+2)*3/4")).isEqualTo("(1+2)*3/4 = 2.25");
        assertThat(tool.calculate("100*1.05^3")).isEqualTo("100*1.05^3 = 115.7625");
        // 幂右结合：2^3^2 = 2^9
        assertThat(tool.calculate("2^3^2")).isEqualTo("2^3^2 = 512");
        assertThat(tool.calculate("-3+1")).isEqualTo("-3+1 = -2");
    }

    @Test
    @DisplayName("十进制精确：0.1+0.2 是 0.3，不是 0.30000000000000004")
    void decimalIsExact() {
        assertThat(tool.calculate("0.1+0.2")).isEqualTo("0.1+0.2 = 0.3");
        // 去尾零 + 不进科学计数法：1E+2 这种形状会被模型照样抄给用户
        assertThat(tool.calculate("2.2500*1")).isEqualTo("2.2500*1 = 2.25");
        assertThat(tool.calculate("100+0")).isEqualTo("100+0 = 100");
    }

    @Test
    @DisplayName("函数与常数：sqrt/abs/round/floor/ceil/pow/min/max、pi/e")
    void supportsFunctionsAndConstants() {
        // 牛顿迭代到 20 位有效数字，这里只钉住前 16 位（double 精度的众所周知值），
        // 免得把迭代末位的舍入差异也钉死（那是 BigDecimal 的实现细节，不是本工具的行为）
        assertThat(tool.calculate("sqrt(2)")).startsWith("sqrt(2) = 1.414213562373095");
        assertThat(tool.calculate("round(3.14159, 2)")).isEqualTo("round(3.14159, 2) = 3.14");
        assertThat(tool.calculate("round(2.5)")).isEqualTo("round(2.5) = 3");
        assertThat(tool.calculate("floor(-1.5)")).isEqualTo("floor(-1.5) = -2");
        assertThat(tool.calculate("ceil(-1.5)")).isEqualTo("ceil(-1.5) = -1");
        assertThat(tool.calculate("pow(2, 10)")).isEqualTo("pow(2, 10) = 1024");
        assertThat(tool.calculate("max(3, 7)")).isEqualTo("max(3, 7) = 7");
        assertThat(tool.calculate("abs(0-5)")).isEqualTo("abs(0-5) = 5");
    }

    @Test
    @DisplayName("带未知数的式子必须报错：返回一个胡编的值比报错危险得多")
    void rejectsUnknownNames() {
        assertThatThrownBy(() -> tool.calculate("x*2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不认识的名字");
        // 后面还跟着东西时不能只取前面一段当答案
        assertThatThrownBy(() -> tool.calculate("1+2abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("语法错误如实说明位置：括号没闭合、末尾缺数字")
    void syntaxErrorsAreExplained() {
        assertThatThrownBy(() -> tool.calculate("(1+2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("括号没有闭合");
        assertThatThrownBy(() -> tool.calculate("1+"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少数字或括号");
        assertThatThrownBy(() -> tool.calculate("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表达式为空");
    }

    @Test
    @DisplayName("病态输入有明确出口：除数/取余为零、负数开方、非整数指数、超长表达式")
    void pathologicalInputsAreRejected() {
        assertThatThrownBy(() -> tool.calculate("1/0")).hasMessageContaining("除数是零");
        assertThatThrownBy(() -> tool.calculate("5%0")).hasMessageContaining("除数是零");
        assertThatThrownBy(() -> tool.calculate("sqrt(0-1)")).hasMessageContaining("负数不能开平方");
        // 无理指数不给近似值：与其给一个"看着精确其实是近似"的结果，不如让模型换个算法
        assertThatThrownBy(() -> tool.calculate("2^0.5")).hasMessageContaining("只支持整数");
        assertThatThrownBy(() -> tool.calculate("1+" + "1".repeat(600)))
                .hasMessageContaining("表达式过长");
        assertThatThrownBy(() -> tool.calculate("sqrt(1, 2)")).hasMessageContaining("需要 1 个参数");
    }
}
