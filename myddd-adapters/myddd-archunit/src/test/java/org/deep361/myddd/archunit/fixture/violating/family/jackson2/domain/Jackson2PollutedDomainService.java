package org.deep361.myddd.archunit.fixture.violating.family.jackson2.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 反例：领域对象直接使用 Jackson 2 注解。 */
public class Jackson2PollutedDomainService {

    @JsonProperty("order-id")
    private String orderId;

    public String describe() {
        return "领域对象被 Jackson 2 污染：" + orderId;
    }
}
