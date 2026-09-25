package org.deep361.myddd.kernel.port.job;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 定时任务声明：标注在实现 {@link JobHandler} 的类上，由适配器启动期扫描注册（Plan 4）。
 *
 * <p><b>两套适配器语义一致</b>：XXL-Job 适配器以 {@link #name()} 注册执行器（调度周期可在控制台覆盖）；
 * Spring 适配器以 {@link #cron()} 注册本地调度。切换适配器时业务代码零改动。
 *
 * <p>同一应用内不得出现同名任务（适配器启动期校验，违反即启动失败）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MydddJob {

    /** 任务名，必填；XXL-Job 下须与控制台配置的执行器任务名一致。 */
    String name();

    /** 6 段 Quartz 风格调度表达式，必填。 */
    String cron();

    /** 说明文字（仅用于文档与诊断日志）。 */
    String description() default "";
}
