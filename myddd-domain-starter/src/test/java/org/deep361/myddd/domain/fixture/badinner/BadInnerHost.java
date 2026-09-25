package org.deep361.myddd.domain.fixture.badinner;

import org.deep361.myddd.kernel.ddd.context.Domain;

/** 承载非静态内部类声明的宿主类（自身未被标注，不会成为模块）。 */
public class BadInnerHost {

    /** {@code @Domain} 标在非静态内部类上的非法声明：扫描期必须快速失败。 */
    @Domain("bad-inner")
    public class BadInnerModule {
    }
}
