package org.deep361.myddd.archunit.fixture.depth.rule1a.domain.sub;

import org.springframework.stereotype.Component;

/** 反例（that() 侧深度）：{@code <base>..domain..} 的子包被 Spring 污染。 */
@Component
public class DeepSpringDomainService {

    public String describe() {
        return "深层子包领域服务被 Spring 污染";
    }
}
