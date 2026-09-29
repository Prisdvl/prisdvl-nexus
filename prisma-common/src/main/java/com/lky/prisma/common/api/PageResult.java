package com.lky.prisma.common.api;

import java.util.List;

/**
 * 统一分页返回体。
 *
 * <p>约定（docs/api.md 1.4）：请求用 {@code pageNum} / {@code pageSize}，响应固定返回
 * {@code list} / {@code total} / {@code pageNum} / {@code pageSize} / {@code pages}。
 *
 * <p>{@code total} 用 {@code long} 基本类型而不是 {@code Long} 包装类型 ——
 * 本项目把 {@code Long}（雪花 ID）序列化为字符串以免前端精度丢失，
 * 计数字段用基本类型即可保持数字形态（该行为由
 * {@code LongToStringJacksonComponentTest} 断言）。
 */
public record PageResult<T>(List<T> list, long total, int pageNum, int pageSize, int pages) {

    /** 单页最大条数：超出直接截断，不报错（防止前端传 pageSize=100000 拖垮数据库）。 */
    public static final int MAX_PAGE_SIZE = 100;

    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        int safePageSize = normalizePageSize(pageSize);
        int safePageNum = Math.max(pageNum, 1);
        int pages = safePageSize == 0 ? 0 : (int) ((total + safePageSize - 1) / safePageSize);
        return new PageResult<>(list == null ? List.of() : list, total, safePageNum, safePageSize, pages);
    }

    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return of(List.of(), 0L, pageNum, pageSize);
    }

    private static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 0;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
