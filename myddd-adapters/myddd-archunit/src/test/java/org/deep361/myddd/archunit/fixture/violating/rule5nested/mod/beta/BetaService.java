package org.deep361.myddd.archunit.fixture.violating.rule5nested.mod.beta;

import org.deep361.myddd.archunit.fixture.violating.rule5nested.mod.alpha.AlphaService;

public class BetaService {

    private AlphaService alpha;

    public void bind(AlphaService alpha) {
        this.alpha = alpha;
    }
}
