package org.deep361.myddd.kernel.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 扩展点：标注在<b>接口</b>上，声明"该接口的实现可由业务方按场景插拔"。
 *
 * <p>标注在类上属误用：{@link DefaultExtensionRegistry} 在登记时校验并快速失败。
 *
 * <p><b>契约</b>：扩展点接口属于内核/公共契约的一部分，业务方只依赖接口；实现方通过 {@link Extension}
 * 声明场景与优先级，运行期不感知具体实现类。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExtensionPoint {

    /** 扩展点名称（业务可读，用于日志与诊断）；留空取接口简单类名。 */
    String value() default "";
}
