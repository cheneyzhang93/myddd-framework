package org.deep361.myddd.domain.fixture.single;

import org.deep361.myddd.kernel.ddd.context.Domain;

/** 最小领域模块：不声明 scanPackages，期望回退到本类所在包。 */
@Domain("order")
public class OrderDomainModule {
}
