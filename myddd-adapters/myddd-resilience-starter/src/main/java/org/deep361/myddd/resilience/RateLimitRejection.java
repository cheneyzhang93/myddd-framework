package org.deep361.myddd.resilience;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;

/**
 * 带注解文案的限流拒绝：错误身份仍是 {@link CommonErrorCode#TOO_MANY_REQUESTS}，只有展示文案是业务的。
 *
 * <p>与 {@code IdempotentAspect.CustomTextConflict} 同形、同一条理由：内核契约里 {@link BizException} 的
 * {@code message} 由错误码渲染，而 {@code TOO_MANY_REQUESTS} 的 {@code defaultMessage}
 * （"请求过于频繁，请稍后重试"）没有占位符，塞参数只会原样输出。子类只覆写 {@code getMessage()}，
 * {@code getErrorCode()} 与 {@code getMessageArgs()} 全部继承，因此错误身份、HTTP 429、i18n 键
 * 与序列化线格式都不变（R12 要的就是"只换文案、不换身份"）。
 */
class RateLimitRejection extends BizException {

    private static final long serialVersionUID = 1L;

    private final String businessText;

    RateLimitRejection(String businessText, Throwable cause, Object[] diagnostics) {
        super(CommonErrorCode.TOO_MANY_REQUESTS, cause, diagnostics);
        this.businessText = businessText;
    }

    @Override
    public String getMessage() {
        return this.businessText;
    }
}
