package org.deep361.myddd.archunit.fixture.violating.family.jta.domain;

import jakarta.transaction.Transactional;

/** 反例：领域服务直接声明 Jakarta 事务边界。 */
@Transactional
public class JtaPollutedDomainService {

    public String describe() {
        return "领域服务被 Jakarta Transactions 污染";
    }
}
