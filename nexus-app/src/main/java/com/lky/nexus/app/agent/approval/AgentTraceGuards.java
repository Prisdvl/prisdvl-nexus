package com.lky.nexus.app.agent.approval;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计轨迹的脱敏与截断（P0，§五 / §九）。
 *
 * <p>工具的入参里可能带 apiKey / token / password，输出里可能带用户隐私；
 * 这些内容一旦原样落进 {@code ai_agent_run_step} / {@code ai_tool_invocation}，
 * 就等于把凭据写进了审计表——审计表恰恰是"会被导出、会被 DBA 看、会随备份走"的地方。
 *
 * <p><b>为什么用文本级脱敏而不是解析成 JSON 再遍历</b>：工具入参不保证是合法 JSON
 * （模型可能给出截断的片段，人写的工具也可能收纯文本），而且这里处理的常见形态就是
 * {@code "apiKey":"sk-xxx"} 这种字面量。解析失败就走"不脱敏原样落库"是最糟的选项——
 * 所以这里宁可对原始文本做保守替换：宁可多打几个星号，也不放过一个凭据字段。
 */
public final class AgentTraceGuards {

    /** 审计摘要的默认上限：轨迹是给人扫的，不是把返回值再贴一遍。 */
    public static final int DEFAULT_LIMIT = 800;

    /** {@code "token": "..."} 这类键值对：值一律替换成 ***。 */
    private static final Pattern SENSITIVE_KEY_VALUE = Pattern.compile(
            "(?i)(\"(?:api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|password|passwd"
                    + "|authorization|credential|client[-_]?secret)\"\\s*:\\s*\")([^\"]*)(\")");

    /** 请求头里的 Bearer 令牌（工具入参里经常整段带过来）。 */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]{8,}");

    /** 形如 {@code sk-xxxxx} 的厂商密钥（OpenAI / DeepSeek 等）。 */
    private static final Pattern SK_KEY = Pattern.compile("\\bsk-[A-Za-z0-9._\\-]{8,}\\b");

    private AgentTraceGuards() {
    }

    /** 脱敏并截断（null 安全）。 */
    public static String digest(String raw) {
        return digest(raw, DEFAULT_LIMIT);
    }

    public static String digest(String raw, int limit) {
        if (raw == null) {
            return null;
        }
        String masked = mask(raw);
        return masked.length() <= limit ? masked : masked.substring(0, limit) + "…(截断)";
    }

    /** 只脱敏、不截断。 */
    public static String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String masked = replaceAll(SENSITIVE_KEY_VALUE, raw, "$1***$3");
        masked = replaceAll(BEARER, masked, "$1***");
        return replaceAll(SK_KEY, masked, "sk-***");
    }

    private static String replaceAll(Pattern pattern, String input, String replacement) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.replaceAll(replacement) : input;
    }
}
