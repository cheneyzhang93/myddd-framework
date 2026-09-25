package org.deep361.myddd.domain.fixture.composed;

import org.deep361.myddd.kernel.ddd.context.Domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 组合注解：模块名随声明固定，使用本注解的类即模块入口（本注解声明本身不是入口）。 */
@Domain("composed")
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ComposedDomain {
}
