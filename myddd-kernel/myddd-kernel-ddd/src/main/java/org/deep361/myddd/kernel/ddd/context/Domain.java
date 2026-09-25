package org.deep361.myddd.kernel.ddd.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 领域模块声明：标注在领域模块的入口类上（{@link ElementType#TYPE}），供领域装配模块发现并装载模块（Task 12）。
 *
 * <p>不支持 {@code package-info} 载体：包级标注需要 {@link ElementType#PACKAGE}，会让装配层出现两种承载方式；
 * 模块名与扫描包统一在类型上声明。
 *
 * <p>一个应用可以有多个领域模块；模块名在应用内唯一，重复即启动失败。
 *
 * <p>本注解在 kernel 内（零框架依赖，红线）：装配层只做扫描与装配，注解本身不引用任何框架类型。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Domain {

    /** 模块名（应用内唯一，建议小写短横线，如 {@code order}），必填。 */
    String value();

    /**
     * 需要装配层扫描的包（含子包）；为空数组时取<b>被标注类所在包</b>。
     *
     * <p>用于把"领域模块"与物理包解耦：一个模块可以横跨多个包。
     */
    String[] scanPackages() default {};
}
