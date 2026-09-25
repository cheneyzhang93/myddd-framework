package org.deep361.myddd.archunit.fixture.violating.rule1.domain;

import org.deep361.myddd.archunit.fixture.violating.rule1.api.DomainLeakAdapter;

public class AdapterLeakingDomainService {

    private final DomainLeakAdapter adapter = new DomainLeakAdapter();

    public String dispatch() {
        return adapter.ping();
    }
}
