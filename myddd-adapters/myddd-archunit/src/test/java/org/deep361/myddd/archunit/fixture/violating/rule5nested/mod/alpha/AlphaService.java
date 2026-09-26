package org.deep361.myddd.archunit.fixture.violating.rule5nested.mod.alpha;

import org.deep361.myddd.archunit.fixture.violating.rule5nested.mod.beta.BetaService;

public class AlphaService {

    private BetaService beta;

    public void bind(BetaService beta) {
        this.beta = beta;
    }
}
