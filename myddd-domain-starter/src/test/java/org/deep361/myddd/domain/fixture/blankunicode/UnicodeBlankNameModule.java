package org.deep361.myddd.domain.fixture.blankunicode;

import org.deep361.myddd.kernel.ddd.context.Domain;

/** 名称为 U+2003（EM SPACE，非 ASCII 空白）的非法声明：{@code trim()} 去不掉它，必须按 {@code isBlank()} 快速失败。 */
@Domain("\u2003")
public class UnicodeBlankNameModule {
}
