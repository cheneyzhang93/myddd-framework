package org.deep361.myddd.kernel.port.resilience;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流声明：标注在方法（或类型，作用于全部方法）上，由适配器切面执行（Plan 4）。
 *
 * <p>命中限流时抛 {@code BizException(CommonErrorCode.TOO_MANY_REQUESTS)}；{@link #message()} 非空时
 * 以其作为文案。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 资源名；留空表示 {@code 简单类名.方法名}。 */
    String key() default "";

    /** 每秒许可数；{@code 0} 表示由外部配置决定（Sentinel 控制台 / Resilience4j 配置）。 */
    int permits() default 0;

    /** 获取许可的最长等待毫秒数；{@code 0} 表示不等待（获取不到立即失败）。 */
    long timeout() default 0;

    /** 限流提示文案；留空表示使用统一错误码文案。 */
    String message() default "";
}
