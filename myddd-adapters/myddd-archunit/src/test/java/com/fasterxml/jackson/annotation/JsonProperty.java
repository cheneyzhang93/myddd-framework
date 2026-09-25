package com.fasterxml.jackson.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 架构规则夹具用的占位类型：只提供 {@code com.fasterxml.jackson..} 这个全限定名（Jackson 2），
 * 让夹具类在字节码里产生一条指向该家族的依赖（ArchUnit 按包名模式匹配，不依赖真实实现）。
 *
 * <p>不是 Jackson 的副本，也不参与任何生产代码，仅存在于测试源码树。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface JsonProperty {

    String value() default "";
}
