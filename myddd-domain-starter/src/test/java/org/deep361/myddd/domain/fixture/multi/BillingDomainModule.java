package org.deep361.myddd.domain.fixture.multi;

import org.deep361.myddd.kernel.ddd.context.Domain;

/** 声明式扫描包（含重复项，用于证明去重 + 排序）且声明顺序与字典序不同。 */
@Domain(value = "billing", scanPackages = {
        "org.deep361.myddd.domain.fixture.multi.infra",
        "org.deep361.myddd.domain.fixture.multi.core",
        "org.deep361.myddd.domain.fixture.multi.infra"
})
public class BillingDomainModule {
}
