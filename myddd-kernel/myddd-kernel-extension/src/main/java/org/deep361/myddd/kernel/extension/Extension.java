package org.deep361.myddd.kernel.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 扩展实现：标注在扩展点实现类上，声明它服务哪个扩展点、哪个场景、优先级多少。
 *
 * <p><b>场景语义</b>：{@link #scenario()} 留空表示"默认实现"——当调用方请求的场景没有精确匹配的实现时兜底，
 * 见 {@link ExtensionRegistry#getExtension(Class, String)}。
 *
 * <p><b>优先级语义</b>：{@link #priority()} 数值越小越优先；同一扩展点同一场景下必须能决出唯一实现，
 * 出现同优先级的多份实现属配置冲突，构建注册表时立即失败（宁可启动报错，不要线上随机路由）。
 *
 * @implNote 当前注册表只做精确场景匹配；{@link #scenario()} 留空所声明的"默认实现"兜底语义由后续的场景解析步骤交付。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Extension {

    /** 扩展点接口；留空时从实现类实现的 {@code @ExtensionPoint} 接口推断，必须唯一，否则构建失败。 */
    Class<?> point() default void.class;

    /**
     * 场景标识（业务身份，如 {@code "vip"}）；留空表示默认实现。
     *
     * @implNote 当前按精确场景匹配：留空只会登记到默认场景（{@code ""}），兜底语义由后续的场景解析步骤交付。
     */
    String scenario() default "";

    /** 优先级：数值越小越优先；同场景同优先级视为冲突。 */
    int priority() default 100;
}
