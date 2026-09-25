package org.deep361.myddd.archunit.fixture.depth.rule2.domain.sub;

import org.deep361.myddd.archunit.fixture.depth.rule2.infrastructure.sub.DeepAdapter;

/** 反例（rule2 双侧深度）：深层 domain 子包直连深层 infrastructure 子包。 */
public class DeepPollutedDomainService {

    private final DeepAdapter adapter = new DeepAdapter();

    public String describe() {
        return adapter.id();
    }
}
