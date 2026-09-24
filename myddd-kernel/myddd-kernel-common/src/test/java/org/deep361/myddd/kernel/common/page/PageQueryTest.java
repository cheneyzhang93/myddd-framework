package org.deep361.myddd.kernel.common.page;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void should_use_defaults() {
        PageQuery query = new PageQuery();

        assertThat(query.getPageNum()).isEqualTo(1L);
        assertThat(query.getPageSize()).isEqualTo(10L);
    }

    @Test
    void of_should_build_and_normalize() {
        PageQuery query = PageQuery.of(3, 20);

        assertThat(query.getPageNum()).isEqualTo(3L);
        assertThat(query.getPageSize()).isEqualTo(20L);
    }

    @Test
    void page_num_should_be_at_least_one() {
        PageQuery query = PageQuery.of(0, 10);

        assertThat(query.getPageNum()).isEqualTo(1L);
    }

    @Test
    void page_size_should_be_clamped() {
        assertThat(PageQuery.of(1, 0).getPageSize()).isEqualTo(1L);
        assertThat(PageQuery.of(1, 10_000).getPageSize()).isEqualTo(PageQuery.MAX_PAGE_SIZE);
    }

    @Test
    void setters_should_normalize_negative_values() {
        PageQuery query = new PageQuery();
        query.setPageNum(-5L);
        query.setPageSize(-1L);

        assertThat(query.getPageNum()).isEqualTo(1L);
        assertThat(query.getPageSize()).isEqualTo(1L);
    }

    @Test
    void offset_should_follow_page_formula() {
        assertThat(PageQuery.of(1, 10).offset()).isEqualTo(0L);
        assertThat(PageQuery.of(3, 20).offset()).isEqualTo(40L);
    }

    @Test
    void offset_should_saturate_instead_of_overflowing() {
        assertThat(PageQuery.of(Long.MAX_VALUE, PageQuery.MAX_PAGE_SIZE).offset())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void offset_should_saturate_only_when_the_product_would_overflow() {
        long maxPageNum = Long.MAX_VALUE / PageQuery.MAX_PAGE_SIZE;

        assertThat(PageQuery.of(maxPageNum + 1L, PageQuery.MAX_PAGE_SIZE).offset())
                .isEqualTo(9_223_372_036_854_775_500L);
        assertThat(PageQuery.of(maxPageNum + 2L, PageQuery.MAX_PAGE_SIZE).offset())
                .isEqualTo(Long.MAX_VALUE);
    }
}
