package org.deep361.myddd.kernel.ddd.model;

/**
 * 领域 id 生成端口：实现由基础设施层提供（如雪花算法，Plan 3 落地），聚合在创建时即获得最终 id，
 * 保证领域事件信封中的 aggregateId 在落库前就可确定。
 *
 * <p><b>调用方契约</b>：(a) 返回值是 {@code long} 基本类型，不存在 null id，调用方无须做空值判断；
 * (b) 唯一性、单调性与是否连续由实现决定，本接口不作任何承诺，调用方不得依赖这些性质；
 * (c) 实现须自行保证线程安全——同一实例会被多个聚合的创建路径并发调用。
 */
@FunctionalInterface
public interface IdGenerator {

    /**
     * 生成下一个领域 id。
     *
     * <p>该 id 即为聚合的最终标识：先取 id 再构造聚合，实体 id 不随持久化而变化。
     */
    long nextId();
}
