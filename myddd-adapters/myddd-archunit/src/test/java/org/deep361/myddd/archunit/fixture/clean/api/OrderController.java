package org.deep361.myddd.archunit.fixture.clean.api;

import org.deep361.myddd.archunit.fixture.clean.application.OrderAppService;
import org.deep361.myddd.archunit.fixture.clean.application.OrderDTO;
import org.deep361.myddd.archunit.fixture.clean.contract.OrderApi;
import org.deep361.myddd.archunit.fixture.clean.contract.OrderForm;
import org.deep361.myddd.archunit.fixture.clean.contract.OrderVO;

import java.math.BigDecimal;

public class OrderController implements OrderApi {

    private final OrderAppService orderAppService;

    public OrderController(OrderAppService orderAppService) {
        this.orderAppService = orderAppService;
    }

    @Override
    public OrderVO changeAmount(OrderForm form) {
        OrderDTO dto = orderAppService.changeAmount(form.orderId(), new BigDecimal(form.amount()));
        return new OrderVO(dto.orderId(), dto.amount());
    }
}
