package org.deep361.myddd.kernel.port.consistency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁声明：标注在方法上，由适配器切面执行（Plan 3）。
 *
 * <p>{@link #key()} 为必填的 SpEL 表达式（如 {@code "#userId"}、{@code "#command.orderId"}）——
 * 锁键必须由业务显式指定，框架不生成隐式锁键。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /** 锁键的 SpEL 表达式，必填。 */
    String key();

    /** 获取锁的最长等待毫秒数；{@code 0} 表示不等待。 */
    long waitTime() default 0;

    /** 持锁毫秒数，到期自动释放（防死锁），必须为正。 */
    long leaseTime() default 30000;

    /** 获取锁失败时的提示文案；留空表示使用统一错误码文案。 */
    String message() default "";
}
