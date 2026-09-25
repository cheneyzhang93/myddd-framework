package org.deep361.myddd.archunit.fixture.violating.rule2.domain;

import org.deep361.myddd.archunit.fixture.violating.rule2.infrastructure.OrderRecord;

public class AdapterPollutedDomainService {

    private final OrderRecord record = new OrderRecord("order-1");

    public String describe() {
        return record.id();
    }
}
