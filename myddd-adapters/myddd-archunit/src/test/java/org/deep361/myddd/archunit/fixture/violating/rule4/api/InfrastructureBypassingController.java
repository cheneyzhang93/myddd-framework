package org.deep361.myddd.archunit.fixture.violating.rule4.api;

import org.deep361.myddd.archunit.fixture.violating.rule4.infrastructure.OrderStore;

public class InfrastructureBypassingController {

    private final OrderStore store = new OrderStore();

    public String load(String id) {
        return store.load(id);
    }
}
