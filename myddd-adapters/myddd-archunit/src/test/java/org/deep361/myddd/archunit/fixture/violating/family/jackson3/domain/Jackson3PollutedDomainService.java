package org.deep361.myddd.archunit.fixture.violating.family.jackson3.domain;

import tools.jackson.databind.ObjectMapper;

/** 反例：领域服务直接持有 Jackson 3（新包名）的序列化器。 */
public class Jackson3PollutedDomainService {

    private final ObjectMapper mapper = new ObjectMapper();

    public String describe() {
        return "领域服务被 Jackson 3 污染：" + mapper.writeValueAsString(this);
    }
}
