package org.deep361.myddd.archunit.fixture.violating.rule2.application;

import org.deep361.myddd.archunit.fixture.violating.rule2.api.LeakedController;

public class AdapterPollutedAppService {

    private final LeakedController controller = new LeakedController();

    public String invoke() {
        return controller.ping();
    }
}
