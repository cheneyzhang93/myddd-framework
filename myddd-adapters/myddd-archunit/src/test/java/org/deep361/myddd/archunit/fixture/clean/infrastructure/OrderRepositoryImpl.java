package org.deep361.myddd.archunit.fixture.clean.infrastructure;

import org.deep361.myddd.archunit.fixture.clean.domain.Amount;
import org.deep361.myddd.archunit.fixture.clean.domain.Order;
import org.deep361.myddd.archunit.fixture.clean.domain.OrderRepository;

import java.math.BigDecimal;

public class OrderRepositoryImpl implements OrderRepository {

    @Override
    public Order findById(String id) {
        return toDomain(new OrderPO(id, "0.0000"));
    }

    @Override
    public void save(Order order) {
        OrderPO persisted = toPO(order);
        if (persisted.id() == null) {
            throw new IllegalStateException("持久化对象必须携带主键");
        }
    }

    private Order toDomain(OrderPO po) {
        return new Order(po.id(), new Amount(new BigDecimal(po.amount())));
    }

    private OrderPO toPO(Order order) {
        return new OrderPO(order.id(), order.amount().value().toPlainString());
    }
}
