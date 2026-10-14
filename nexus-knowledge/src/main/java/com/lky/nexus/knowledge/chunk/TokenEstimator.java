package com.lky.nexus.knowledge.chunk;

/**
 * Token 数量估算器。
 *
 * <p><b>为什么是"估算"而不是精确计数</b>：不同供应商用的分词器不同
 * （DeepSeek / GLM / Qwen / OpenAI 各不相同），引入某个具体分词器只会对某一家准确，
 * 反而给人"很精确"的错觉。而切分只需要一个<b>相对稳定的预算</b>——
 * 真正的 Token 消耗以模型返回的 usage 为准（Phase 10 观测）。
 *
 * <p>估算规则（基于中英文混合文本的经验值）：
 * <ul>
 *   <li>中日韩字符：约 1 token/字（BPE 对 CJK 基本按字切）；</li>
 *   <li>其他字符（拉丁字母、标点、空白）：约 4 字符/token。</li>
 * </ul>
 * 该系数偏保守（宁可切小一点，也不要因超出上下文被截断）。
 */
public final class TokenEstimator {

    /** 非 CJK 字符折算成一个 token 所需字符数。 */
    private static final double NON_CJK_CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {
    }

    /**
     * 估算文本的 token 数（保守近似，规则见类注释）。
     *
     * @param text 待估算文本；{@code null} 或空串返回 0
     * @return 估算的 token 数量（CJK 约 1 token/字，其他字符约 4 字符/token）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjkCount++;
            } else {
                otherCount++;
            }
            i += Character.charCount(codePoint);
        }
        return cjkCount + (int) Math.ceil(otherCount / NON_CJK_CHARS_PER_TOKEN);
    }

    /** 是否中日韩文字（含中文标点区间的汉字主体部分）。 */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)      // CJK 统一表意文字
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)  // 扩展 A
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)  // 兼容表意文字
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)  // 日文假名
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF); // 韩文
    }
}
