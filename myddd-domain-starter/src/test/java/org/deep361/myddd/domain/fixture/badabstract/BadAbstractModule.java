package org.deep361.myddd.domain.fixture.badabstract;

import org.deep361.myddd.kernel.ddd.context.Domain;

/** {@code @Domain} 标在抽象类上的非法声明：扫描期必须快速失败（Spring 默认会静默丢弃这类候选）。 */
@Domain("bad-abstract")
public abstract class BadAbstractModule {
}
