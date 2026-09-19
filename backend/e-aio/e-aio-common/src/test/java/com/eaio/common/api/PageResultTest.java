package com.eaio.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 分页结果契约（P0 册 3.2.2）。 */
class PageResultTest {

    @Test
    @DisplayName("工厂方法保留四个字段，records 顺序不变")
    void fromKeepsFieldsAndOrder() {
        PageResult<String> page = PageResult.from(2, 10, 35, List.of("a", "b"));

        assertThat(page.getPageNum()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(10);
        assertThat(page.getTotal()).isEqualTo(35);
        assertThat(page.getRecords()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("空页：total=0、records 为空列表（不是 null）")
    void emptyPageHasZeroTotalAndNoRecords() {
        PageResult<String> page = PageResult.empty(1, 20);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
    }

    @Test
    @DisplayName("总页数：向上取整，整除不多算一页")
    void pagesRoundsUp() {
        assertThat(PageResult.from(1, 10, 35, List.of()).pages()).isEqualTo(4);
        assertThat(PageResult.from(1, 10, 30, List.of()).pages()).isEqualTo(3);
        assertThat(PageResult.from(1, 10, 0, List.of()).pages()).isZero();
        assertThat(PageResult.from(1, 10, 1, List.of()).pages()).isEqualTo(1);
    }

    @Test
    @DisplayName("总页数：页大小非正数时返回 0，不抛除零异常")
    void pagesIsZeroWhenPageSizeInvalid() {
        assertThat(PageResult.from(1, 0, 100, List.of()).pages()).isZero();
        assertThat(PageResult.from(1, -5, 100, List.of()).pages()).isZero();
    }

    @Test
    @DisplayName("大数据量不溢出：long 语义而非 int")
    void pagesHandlesLargeTotals() {
        assertThat(PageResult.from(1, 100, 1_000_000_000L, List.of()).pages()).isEqualTo(10_000_000L);
    }
}
