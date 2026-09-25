package org.deep361.myddd.archunit.fixture.depth.rule1a.domain;

import org.deep361.myddd.archunit.fixture.depth.rule1a.infrastructure.sub.DeepAdapter;

/** 反例（should() 侧深度）：浅层 domain 直连 {@code <base>..infrastructure..} 的子包。 */
public class DirectAdapterDomainService {

    private final DeepAdapter adapter = new DeepAdapter();

    public String describe() {
        return adapter.id();
    }
}
