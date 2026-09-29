package com.lky.akh.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页模型测试：重点验证边界行为（页数计算、pageSize 上限截断、空结果）。
 */
class PageResultTest {

    @Test
    @DisplayName("页数按向上取整计算")
    void pagesAreRoundedUp() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 137L, 1, 20);
        assertThat(page.pages()).isEqualTo(7);
        assertThat(page.total()).isEqualTo(137L);
        assertThat(page.list()).hasSize(2);
    }

    @Test
    @DisplayName("整除时页数不多算一页")
    void pagesAreExactWhenDivisible() {
        assertThat(PageResult.of(List.of(), 100L, 1, 20).pages()).isEqualTo(5);
    }

    @Test
    @DisplayName("pageSize 超过上限时截断为 100（防止拖垮数据库）")
    void pageSizeIsCapped() {
        PageResult<String> page = PageResult.of(List.of(), 0L, 1, 100_000);
        assertThat(page.pageSize()).isEqualTo(PageResult.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("pageNum 小于 1 时归一到 1；pageSize 非正时返回 0 页")
    void invalidInputsAreNormalized() {
        assertThat(PageResult.of(List.of(), 0L, 0, 20).pageNum()).isEqualTo(1);
        assertThat(PageResult.of(List.of(), 0L, 1, 0).pages()).isZero();
    }

    @Test
    @DisplayName("空结果：list 不为 null，避免前端判空分支")
    void emptyResultNeverReturnsNullList() {
        PageResult<String> page = PageResult.empty(1, 20);
        assertThat(page.list()).isNotNull().isEmpty();
        assertThat(page.total()).isZero();
    }
}
