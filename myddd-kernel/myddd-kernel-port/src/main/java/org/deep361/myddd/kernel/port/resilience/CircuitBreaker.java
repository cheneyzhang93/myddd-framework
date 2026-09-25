package org.deep361.myddd.kernel.port.resilience;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 熔断声明：标注在方法（或类型）上，由适配器切面执行（Plan 4）。
 *
 * <p>熔断打开时抛 {@code BizException(CommonErrorCode.SERVICE_DEGRADED)}；配置
 * {@link #fallbackMethod()} 时改为调用同类内的降级方法（签名须与被保护方法兼容），降级方法自身抛出的
 * 异常原样传播。三项阈值参数为 {@code 0} 时表示由外部配置决定。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CircuitBreaker {

    /** 资源名；留空表示 {@code 简单类名.方法名}。 */
    String key() default "";

    /** 失败率阈值（百分比，0–100）；{@code 0} 表示外部配置。 */
    int failureRateThreshold() default 0;

    /** 滑动窗口大小（次数）；{@code 0} 表示外部配置。 */
    int slidingWindowSize() default 0;

    /** 熔断打开后的等待毫秒数；{@code 0} 表示外部配置。 */
    long waitDurationInOpenState() default 0;

    /** 同类内降级方法名；留空表示不降级。 */
    String fallbackMethod() default "";
}
