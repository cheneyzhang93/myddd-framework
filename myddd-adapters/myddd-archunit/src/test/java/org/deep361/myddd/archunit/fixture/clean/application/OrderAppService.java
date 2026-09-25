package org.deep361.myddd.archunit.fixture.clean.application;

import org.deep361.myddd.archunit.fixture.clean.domain.Amount;
import org.deep361.myddd.archunit.fixture.clean.domain.Order;
import org.deep361.myddd.archunit.fixture.clean.domain.OrderRepository;

import java.math.BigDecimal;

public class OrderAppService {

    private final OrderRepository orderRepository;

    public OrderAppService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderDTO changeAmount(String orderId, BigDecimal amount) {
        Order order = orderRepository.findById(orderId);
        order.changeAmount(new Amount(amount));
        orderRepository.save(order);
        return new OrderDTO(order.id(), order.amount().value().toPlainString());
    }
}
