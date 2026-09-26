package org.deep361.myddd.archunit.fixture.violating.rule5.moduleb;

import org.deep361.myddd.archunit.fixture.violating.rule5.modulea.AlphaService;

public class BetaService {

    private AlphaService alpha;

    public void bind(AlphaService alpha) {
        this.alpha = alpha;
    }
}
