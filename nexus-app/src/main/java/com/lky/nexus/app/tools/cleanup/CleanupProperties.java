package com.lky.nexus.app.tools.cleanup;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试数据清理工具的配置（前缀 {@code nexus.cleanup}）。
 *
 * <p><b>这不是产品功能</b>：它只服务于自动化测试 / 本地验收 / CI / 开发环境的数据清理。
 * 之所以做成"配置 + CLI"而不是"管理接口"，是因为清理对象是
 * <b>凭证已丢失的一次性测试账号</b>——它们连自己的 token 都没有了，
 * 任何走 HTTP 的设计都注定用不上（见 docs/future-improvements.md 第 10 条）。
 *
 * <p>整个 {@code tools/cleanup} 包（本类 + 服务 + CLI 入口）都标了 {@code @Profile("cleanup")}，
 * 因此<b>正常运行时代码路径根本不存在，也不会多出任何 Bean</b>。
 *
 * <p>两条安全设计：
 * <ol>
 *   <li>{@link #execute} 默认 {@code false}，即<b>默认只做 dry-run</b>（只读、只打印计划）。
 *       要真的删除必须显式打开——这是"清理工具误伤"的第一道闸。</li>
 *   <li>{@link #protectedUsernames} 是<b>硬保护名单</b>，默认含真实用户 {@code Prisdvl}
 *       与演示账号 {@code demo}。保护判定发生在匹配之前：即使有人把前缀配成空串
 *       （等于匹配全部账号），这两个账号也不会进入候选集。
 *       并且 {@code TestDataCleanupService} 会额外做一次"保护账号是否被前缀命中"的断言，
 *       命中即整体中止（fail-closed），而不是"静默跳过"。</li>
 * </ol>
 */
@Data
@Component
@Profile("cleanup")
@ConfigurationProperties(prefix = "nexus.cleanup")
public class CleanupProperties {

    /**
     * 候选账号的<b>用户名前缀</b>白名单。
     *
     * <p>用前缀而不是正则：这批账号全部由验收脚本按固定命名生成
     * （{@code ua-<ts>} / {@code probe-*} / {@code smoke-*} …），前缀可枚举、可审计，
     * 而正则一旦写宽就会变成"看起来很安全"的黑盒。
     *
     * <p><b>这是白名单</b>：不在列表里的账号（例如将来出现的第三方真实账号）一律不动。
     * 这是刻意的取舍——清理工具的失败模式应该是"少删"，不是"多删"。
     */
    private List<String> usernamePrefixes = new ArrayList<>(List.of(
            "ua-",
            "probe",
            "smoke",
            "fmt-",
            "sec-check-",
            "multi-kb-other-",
            "err",
            "keyok-",
            "kbtest_",
            "vault_",
            "sync",
            "async_",
            "uicheck_",
            "nokb_probe",
            "policy-check-",
            "mcp-live-",
            "upload-limit-"
    ));

    /**
     * 硬保护名单：无论前缀怎么配，这些账号绝不进入候选集。
     *
     * <p>{@code Prisdvl} 是真实用户（名下有真实知识库）；{@code demo} 承载演示数据。
     * 它们在库里被删除后不可通过 API 恢复，因此保护放在代码里而不是文档里。
     */
    private List<String> protectedUsernames = new ArrayList<>(List.of("Prisdvl", "demo"));

    /** 是否真正执行删除。{@code false}（默认）= dry-run，只读、只打印计划。 */
    private boolean execute = false;

    /**
     * 是否连同被清理账号名下的<b>内置默认 Agent</b> 一起软删。
     *
     * <p>默认 {@code true}：内置 Agent 是"注册即赠送"的每用户资源
     * （{@code AgentService.delete} 明确拒绝删除内置 Agent），
     * 但既然整个账号都要清理掉，把它留下只会让每次清理都多堆一行无主数据。
     * 这里是管理员级旁路，因此绕过了服务层的"内置不可删"约束——这是有意为之，已在日志里显著标注。
     */
    private boolean purgeBuiltinAgents = true;

    /**
     * 是否把账号行**物理删除**（而不只是软删 {@code deleted=1}）。
     *
     * <p>默认 {@code false}：软删可回溯，是更保守的选择。
     *
     * <p>开启后 {@code sys_user} / {@code sys_user_role} 两行会被 {@code DELETE} 掉，
     * 账号从此不可恢复（名下资源早就被级联清干净了，所以丢的只是"这个账号曾经存在"这一条记录）。
     *
     * <p><b>为什么要这个开关</b>：软删的账号行会**永久累积**——
     * 每跑一轮 E2E 就多几行，实测累积到 140+ 条。
     * 它们不影响功能（登录查不到、唯一键靠 {@code (username, deleted)} 让位），
     * 但会让 {@code sys_user} 表混着大量一次性账号，排查真实用户问题时非常干扰。
     * 这是"审计留痕"与"表干净"之间的取舍，因此做成显式开关而不是替用户决定。
     */
    private boolean purgeAccountRows = false;

    // ------------------------------------------------------------------
    // 安全语义（放在配置类上而不是服务里，是为了让它能被纯单元测试覆盖——
    // "哪些账号会被删" 这件事不应该只有连上数据库才验得了）
    // ------------------------------------------------------------------

    /**
     * 用户名是否命中前缀白名单。
     *
     * <p><b>空前缀被忽略</b>：如果把前缀配成空串，{@code startsWith("")} 对所有字符串都为真，
     * 那就等于"匹配全部账号"。这里显式跳过空串，避免一个手滑的空配置把作用域放大到全库。
     */
    public boolean matches(String username) {
        if (username == null) {
            return false;
        }
        for (String prefix : usernamePrefixes) {
            if (prefix != null && !prefix.isEmpty() && username.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * fail-closed 守卫：保护账号被前缀命中即抛异常。
     *
     * <p>为什么是"中止"而不是"跳过"：跳过会让一次配置失误（例如前缀被改成 {@code Pris}）
     * 表现成"清理正常完成"，而作用域已经悄悄变了。中止能让错误立刻可见。
     *
     * @throws IllegalStateException 任一保护账号命中前缀
     */
    public void assertProtectedNotMatched() {
        for (String protectedName : protectedUsernames) {
            if (matches(protectedName)) {
                throw new IllegalStateException(
                        "保护账号 [" + protectedName + "] 被清理前缀命中，配置有误，已中止。"
                                + "前缀=" + usernamePrefixes);
            }
        }
    }
}
