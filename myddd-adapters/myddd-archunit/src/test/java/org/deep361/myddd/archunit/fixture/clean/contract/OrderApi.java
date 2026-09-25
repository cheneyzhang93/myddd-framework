package org.deep361.myddd.archunit.fixture.clean.contract;

/** 契约接口：api 层 Controller 必须实现它（规则 7）。 */
public interface OrderApi {

    OrderVO changeAmount(OrderForm form);
}
