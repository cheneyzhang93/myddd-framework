package org.deep361.myddd.archunit.fixture.violating.rule5.modulea;

import org.deep361.myddd.archunit.fixture.violating.rule5.moduleb.BetaService;

public class AlphaService {

    private BetaService beta;

    public void bind(BetaService beta) {
        this.beta = beta;
    }
}
