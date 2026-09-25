package org.deep361.myddd.kernel.port.consistency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等声明：标注在方法上，由适配器切面执行（Plan 3）。
 *
 * <p>命中重复请求时抛 {@code BizException(CommonErrorCode.IDEMPOTENT_CONFLICT)}（HTTP 409）；
 * {@link #message()} 非空时以其作为文案。
 *
 * <p>切面须用 Spring 的 {@code AnnotationUtils}/{@code MergedAnnotations} 解析，以便组合注解（元注解）生效。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 幂等键的 SpEL 表达式；留空表示 {@code 简单类名.方法名:参数摘要}。 */
    String key() default "";

    /** 占位存活秒数，必须为正。 */
    long ttl() default 60;

    /**
     * 业务方法抛异常时是否释放占位。
     *
     * <p>默认 {@code true}：校验类失败（如库存不足）后用户可修正并重试；置 {@code false} 表示无论成败占位都
     * 保留到 TTL 过期，适用于"失败也不允许立即重试"的场景。
     */
    boolean releaseOnError() default true;

    /** 重复请求的提示文案；留空表示使用统一错误码文案。 */
    String message() default "";
}
