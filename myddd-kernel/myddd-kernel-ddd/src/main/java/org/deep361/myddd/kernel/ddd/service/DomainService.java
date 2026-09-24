package org.deep361.myddd.kernel.ddd.service;

/**
 * 领域服务标记：跨聚合/跨实体、不属于任一聚合的不变量与业务规则载体（无状态）。
 *
 * <p>与 {@code @Service} 等框架注解无关——本接口只表达"这是领域服务"的语义，装配由 domain-starter
 * 负责（Plan 2 落地）。
 * 实现必须无状态（不持有可变实例字段），否则并发调用将相互干扰。
 */
public interface DomainService {
}
