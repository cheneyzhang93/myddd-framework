package org.deep361.myddd.archunit.fixture.clean.domain;

/** 仓储接口：无 I 前缀（规则 6）。 */
public interface OrderRepository {

    Order findById(String id);

    void save(Order order);
}
