package org.deep361.myddd.kernel.common.page;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResultTest {

    @Test
    void should_carry_page_metadata() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 12L, 3L, 4L);

        assertThat(result.items()).containsExactly("a", "b");
        assertThat(result.total()).isEqualTo(12L);
        assertThat(result.pageNum()).isEqualTo(3L);
        assertThat(result.pageSize()).isEqualTo(4L);
    }

    @Test
    void items_should_be_immutable_copy() {
        List<String> source = new ArrayList<>(List.of("a"));

        PageResult<String> result = PageResult.of(source, 1L, 1L, 10L);
        source.add("b");

        assertThat(result.items()).containsExactly("a");
        assertThatThrownBy(() -> result.items().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void null_items_should_become_empty_list() {
        assertThat(PageResult.of(null, 0L, 1L, 10L).items()).isEmpty();
    }

    @Test
    void empty_should_build_zero_total_page() {
        PageResult<String> result = PageResult.empty(3L, 20L);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.pageNum()).isEqualTo(3L);
        assertThat(result.pageSize()).isEqualTo(20L);
        assertThat(result.pages()).isZero();
    }

    @Test
    void pages_should_round_up() {
        assertThat(PageResult.of(List.of(), 0L, 1L, 10L).pages()).isZero();
        assertThat(PageResult.of(List.of(), 10L, 1L, 10L).pages()).isEqualTo(1L);
        assertThat(PageResult.of(List.of(), 11L, 1L, 10L).pages()).isEqualTo(2L);
    }

    @Test
    void pages_should_avoid_overflow_for_huge_totals() {
        assertThat(PageResult.of(List.of(), Long.MAX_VALUE, 1L, 10L).pages())
                .isEqualTo(922_337_203_685_477_581L);
    }

    @Test
    void pages_should_be_zero_when_page_size_is_not_positive() {
        assertThat(PageResult.of(List.of(), 10L, 1L, 0L).pages()).isZero();
        assertThat(PageResult.of(List.of(), 10L, 1L, -5L).pages()).isZero();
    }

    @Test
    void items_should_reject_null_elements() {
        List<String> withNull = new ArrayList<>();
        withNull.add("a");
        withNull.add(null);

        assertThatThrownBy(() -> PageResult.of(withNull, 1L, 1L, 10L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void map_should_transform_items_and_keep_metadata() {
        PageResult<String> source = PageResult.of(List.of("a", "bb"), 12L, 2L, 10L);

        PageResult<Integer> mapped = source.map(String::length);

        assertThat(mapped.items()).containsExactly(1, 2);
        assertThat(mapped.total()).isEqualTo(12L);
        assertThat(mapped.pageNum()).isEqualTo(2L);
        assertThat(mapped.pageSize()).isEqualTo(10L);
    }
}
