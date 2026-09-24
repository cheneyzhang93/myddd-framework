package org.deep361.myddd.kernel.common.page;

import java.util.List;
import java.util.function.Function;

/**
 * 分页查询结果：{@code items} 为不可变拷贝且不接受 {@code null} 元素。
 *
 * <p><b>字段契约</b>：{@code total} 为满足条件的总行数，由 DAO 计数提供且应为非负（本类不做校验，
 * 传负数会得到非正的 {@link #pages()}）；{@code pageNum} / {@code pageSize} 为本次查询所用的分页元数据。
 * {@link #pages()} 在 {@code pageSize <= 0} 时返回 0，因此 {@code items} 非空而 {@code pages() == 0}
 * 这类元数据矛盾由调用方负责避免。
 */
public record PageResult<T>(List<T> items, long total, long pageNum, long pageSize) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 空结果页（total = 0）。 */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(List.of(), 0L, pageNum, pageSize);
    }

    public static <T> PageResult<T> of(List<T> items, long total, long pageNum, long pageSize) {
        return new PageResult<>(items, total, pageNum, pageSize);
    }

    /**
     * 总页数：{@code total} 向上取整除以 {@code pageSize}（用 {@link Math#ceilDiv(long, long)} 而非
     * {@code (total + pageSize - 1) / pageSize}，避免后者在 {@code total} 极大时溢出）；{@code pageSize <= 0} 时返回 0。
     */
    public long pages() {
        return pageSize <= 0L ? 0L : Math.ceilDiv(total, pageSize);
    }

    /** 转换 items 元素类型并保留分页元数据，避免手工重装时漏掉 total / pageNum / pageSize；{@code mapper} 不得返回 null（items 不接受 null 元素，构造时会抛 NPE） */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResult<>(items.stream().<R>map(mapper).toList(), total, pageNum, pageSize);
    }
}
