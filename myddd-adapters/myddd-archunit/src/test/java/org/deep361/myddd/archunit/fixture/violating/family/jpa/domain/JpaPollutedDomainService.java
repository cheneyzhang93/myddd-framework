package org.deep361.myddd.archunit.fixture.violating.family.jpa.domain;

import jakarta.persistence.Entity;

/** 反例：领域对象直接标注 JPA 实体注解。 */
@Entity(name = "order")
public class JpaPollutedDomainService {

    public String describe() {
        return "领域对象被 JPA 污染";
    }
}
