package org.deep361.myddd.domain.fixture.blank;

import org.deep361.myddd.kernel.ddd.context.Domain;

/** 模块名为空白的非法声明：扫描期必须快速失败。 */
@Domain("   ")
public class BlankNameModule {
}
