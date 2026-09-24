package org.deep361.myddd.kernel.common.page;

/**
 * 分页查询入参：页码从 1 开始，超出边界的值自动规整（页码 ≥1，页大小 1..{@value #MAX_PAGE_SIZE}）。
 *
 * <p>刻意设计为可变 Bean（无参构造 + setter）而非 record：web 层可直接把 query 参数
 * {@code pageNum} / {@code pageSize} 绑定进来，同时保留 {@link #of(long, long)} 作为编程式入口。
 *
 * <p>setter 只规整、不抛异常：非法入参（0、负数、过大）会落到最近的合法值，避免一个手误的分页参数
 * 把整个查询打成 500。
 */
public class PageQuery {

    /** 默认页码。 */
    public static final long DEFAULT_PAGE_NUM = 1L;

    /** 默认页大小。 */
    public static final long DEFAULT_PAGE_SIZE = 10L;

    /** 单页最大条数，防止一次拉全表。 */
    public static final long MAX_PAGE_SIZE = 500L;

    private static final long MIN_PAGE_NUM = 1L;
    private static final long MIN_PAGE_SIZE = 1L;

    private long pageNum = DEFAULT_PAGE_NUM;
    private long pageSize = DEFAULT_PAGE_SIZE;

    /** 编程式构造：等价于先 {@link #setPageNum(long)} 再 {@link #setPageSize(long)}。 */
    public static PageQuery of(long pageNum, long pageSize) {
        PageQuery query = new PageQuery();
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return query;
    }

    public long getPageNum() {
        return pageNum;
    }

    /** 页码小于 1 时归一到 1。 */
    public void setPageNum(long pageNum) {
        this.pageNum = Math.max(pageNum, MIN_PAGE_NUM);
    }

    public long getPageSize() {
        return pageSize;
    }

    /** 页大小超出 1..{@value #MAX_PAGE_SIZE} 时归一到最近的合法边界。 */
    public void setPageSize(long pageSize) {
        this.pageSize = Math.min(Math.max(pageSize, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
    }

    /**
     * 数据库 offset；页码大到乘法会溢出时饱和为 {@link Long#MAX_VALUE}（超大页码得到空页，
     * 而不是一个负 offset 让 SQL 报错）。
     *
     * <p>前提：{@code pageSize >= 1}、{@code pageNum >= 1}——由 setter / {@link #of(long, long)} 维持的
     * 不变量；绕过 setter 直接改写字段（如字段级反射绑定）不在保护范围内。
     */
    public long offset() {
        long maxPageNum = Long.MAX_VALUE / pageSize;
        if (pageNum - 1L > maxPageNum) {
            return Long.MAX_VALUE;
        }
        return (pageNum - 1L) * pageSize;
    }
}
