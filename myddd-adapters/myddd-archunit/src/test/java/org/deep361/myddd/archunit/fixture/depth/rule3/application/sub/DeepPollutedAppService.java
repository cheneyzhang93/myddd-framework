package org.deep361.myddd.archunit.fixture.depth.rule3.application.sub;

import org.deep361.myddd.archunit.fixture.depth.rule3.contract.sub.DeepContractApi;

/** 反例（rule3 双侧深度）：深层 application 子包直连深层 contract 子包。 */
public class DeepPollutedAppService {

    private final DeepContractApi contract;

    public DeepPollutedAppService(DeepContractApi contract) {
        this.contract = contract;
    }

    public String invoke() {
        return contract.ping();
    }
}
