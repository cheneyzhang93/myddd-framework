package org.deep361.myddd.archunit.fixture.violating.rule1.domain;

import org.springframework.stereotype.Component;

@Component
public class SpringPollutedDomainService {

    public String describe() {
        return "领域服务被 Spring 污染";
    }
}
