package com.lky.nexus.ai.agent;

/**
 * 工具分类（§十九）。<b>只用于界面分组与文档</b>，不参与任何判定——
 * 这一点必须写清楚，否则以后会有人以为"标了 NETWORK 就会被拦"。
 *
 * <p>真正的拦截在 P1 的 {@code permission}（READ / WRITE / DANGEROUS）+ 执行壳上；
 * 这里连"危险"这个概念都不假装有。
 *
 * <p><b>刻意没有 {@code parse(String)}</b>：{@code tool} 表没有 {@code category} 列
 * （V13 文件头记了理由），分类只在 {@code ToolCatalog} 的登记项里写死，
 * 没有"从库/输入解析"这条路径。理由与 {@link ToolSource} 那条相同。
 */
public enum ToolCategory {

    /** 检索本机数据（今天：knowledge_search）。 */
    RETRIEVAL,

    /** 纯计算，无副作用（今天：calculator）。 */
    COMPUTE,

    /** 需要出网（未实现：http_request / web_search——它们要先去 P1 拿权限与审批）。 */
    NETWORK,

    /** 其它。 */
    OTHER
}
