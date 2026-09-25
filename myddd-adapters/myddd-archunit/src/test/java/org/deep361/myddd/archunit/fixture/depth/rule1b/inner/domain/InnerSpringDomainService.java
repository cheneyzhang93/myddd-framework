package org.deep361.myddd.archunit.fixture.depth.rule1b.inner.domain;

import org.springframework.stereotype.Component;

/** 反例（中间包）：base 与 layer 之间插入了 {@code inner} 包。 */
@Component
public class InnerSpringDomainService {

    public String describe() {
        return "中间包领域服务被 Spring 污染";
    }
}
