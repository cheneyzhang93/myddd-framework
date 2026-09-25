package org.deep361.myddd.archunit.fixture.violating.rule3.application;

import org.deep361.myddd.archunit.fixture.violating.rule3.contract.ContractApi;

public class ContractPollutedAppService {

    private final ContractApi contractApi;

    public ContractPollutedAppService(ContractApi contractApi) {
        this.contractApi = contractApi;
    }

    public String invoke() {
        return contractApi.ping();
    }
}
