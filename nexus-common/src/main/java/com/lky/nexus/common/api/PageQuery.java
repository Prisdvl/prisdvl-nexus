package com.lky.nexus.common.api;

/**
 * 分页参数规范化。
 *
 * <p>为什么要单独抽出来：DB 查询用的 {pageSize} 与返回体里的 {pageSize} <b>必须是同一个值</b>。
 * 如果只在响应里截断成 100、SQL 却按 100000 查，就会出现"返回 100 条、实际扫了全表"的性能陷阱。
 * 因此统一在这里规范化，查询与响应都用这个结果。
 */
public final class PageQuery {

    public static final int DEFAULT_PAGE_SIZE = 20;

    private PageQuery() {
    }

    /** 页码从 1 开始；小于 1 归一到 1。 */
    public static int normalizePageNum(int pageNum) {
        return Math.max(pageNum, 1);
    }

    /** 页大小：非法值取默认 20，超过 {@link PageResult#MAX_PAGE_SIZE} 则截断。 */
    public static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, PageResult.MAX_PAGE_SIZE);
    }
}
