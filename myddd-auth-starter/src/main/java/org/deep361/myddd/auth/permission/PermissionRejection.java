package org.deep361.myddd.auth.permission;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.ErrorCode;

/**
 * 带注解文案的权限拒绝：错误身份仍是 {@code UNAUTHORIZED}（401）或 {@code FORBIDDEN}（403），
 * 只有展示文案是业务写的那一句。
 *
 * <p>内核契约里 {@link BizException} 的 {@code message} 由错误码渲染，而 {@code MYDDD_40101} 与
 * {@code MYDDD_40301} 的 {@code defaultMessage} 都没有占位符，往参数里塞业务文案只会得到原样输出——
 * 所以"注解自带文案优先"只能靠一个只覆写 {@code getMessage()} 的子类表达。
 * {@code getErrorCode()} 与 {@code getMessageArgs()} 全部继承，因此错误身份、HTTP 状态码、i18n 键
 * 与序列化线格式都不变（R12 要的就是"只换文案、不换身份"）。
 *
 * <p><b>类型是包内的，且 web 层不按类型认它</b>：出口那侧的判据是"这条异常携带了与错误码缺省不同的
 * 非空白文案"这一<b>结构</b>事实，不是 {@code instanceof} 某一家拒绝类型——否则 {@code myddd-web-starter}
 * 要反过来依赖本模块，而"web 依赖某个能力的壳"是一条没人想再看见第二次的依赖边。
 */
class PermissionRejection extends BizException {

    private static final long serialVersionUID = 1L;

    private final String businessText;

    PermissionRejection(ErrorCode errorCode, String businessText) {
        super(errorCode);
        this.businessText = businessText;
    }

    @Override
    public String getMessage() {
        return this.businessText;
    }
}
