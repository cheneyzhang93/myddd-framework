package org.deep361.myddd.kernel.ddd.repository;

/**
 * 仓储标记接口：领域层定义具体仓储接口（如 {@code OrderRepository extends Repository<Order, Long>}），
 * 实现由持久化适配器提供（Plan 3 落地）；命名不带 {@code I} 前缀。
 *
 * <p>刻意不声明任何方法——查询方法的形状取决于具体仓储（分页、规格、只读视图差异过大），由领域层
 * 各自的仓储接口声明，避免在这里制造"最小公倍数"方法集。
 *
 * @param <T>  聚合根类型
 * @param <ID> 聚合标识类型
 */
public interface Repository<T, ID> {
}
