package org.deep361.myddd.domain.fixture.badpackage;

import org.deep361.myddd.kernel.ddd.context.Domain;

/** scanPackages 含空白项的非法声明：扫描期必须快速失败。 */
@Domain(value = "bad-package", scanPackages = {" "})
public class BlankPackageModule {
}
