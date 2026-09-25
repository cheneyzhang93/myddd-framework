package org.deep361.myddd.archunit.fixture.violating.family.ibatis.domain;

import org.apache.ibatis.type.Alias;

/** 反例：领域服务直接使用 MyBatis 原生注解。 */
@Alias("order")
public class IbatisPollutedDomainService {

    public String describe() {
        return "领域服务被 MyBatis 污染";
    }
}
