package org.deep361.myddd.kernel.port.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限声明：标注在方法（或类型）上，由适配器切面校验（Plan 4）。
 *
 * <p>校验失败：未登录抛 {@code BizException(CommonErrorCode.UNAUTHORIZED)}（HTTP 401）；
 * 已登录但权限不足抛 {@code BizException(CommonErrorCode.FORBIDDEN)}（HTTP 403）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    /** 所需权限标识，至少一项。 */
    String[] value();

    /** 多项权限的组合方式。 */
    Logical logical() default Logical.ALL_OF;

    /** 拒绝时的提示文案；留空表示使用统一错误码文案。 */
    String message() default "";

    /** 多项权限的组合方式。 */
    enum Logical {
        /** 必须同时具备全部权限。 */
        ALL_OF,
        /** 具备任意一项即可。 */
        ANY_OF
    }
}
