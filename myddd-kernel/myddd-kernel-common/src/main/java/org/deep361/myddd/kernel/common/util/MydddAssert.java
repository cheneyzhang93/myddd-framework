package org.deep361.myddd.kernel.common.util;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.ErrorCode;

import java.util.Collection;
import java.util.Map;

/**
 * 领域断言工具：条件不满足即抛 {@link BizException}（业务可预期错误），
 * 用于表达聚合不变量、领域服务前置条件与防腐层入参校验。
 *
 * <p>失败以 {@link BizException} 而非 JDK {@code assert} 呈现，因为不变量被破坏是业务可预期的结果：
 * 异常携带 {@link ErrorCode}，web 层可据此映射语义化 HTTP 状态码（如 400/404）并按
 * {@link ErrorCode#messageKey()} 做 i18n 解析，而不是一律降级为 500 且无文案可依。
 *
 * <p><b>文案渲染契约</b>：{@code messageArgs} 按顺序填充错误码 {@link ErrorCode#defaultMessage()} 中的
 * {@code {}} 占位符（替换细则见 {@link MessageFormatter}）。含占位符的错误码必须补齐参数——例如
 * {@link org.deep361.myddd.kernel.common.error.CommonErrorCode#ARGUMENT_INVALID CommonErrorCode.ARGUMENT_INVALID}
 * 漏传参数时，占位符会原样留在用户可见文案中（{@code 参数 {} 不合法}）；多余参数被忽略，
 * 这些行为均与 {@link BizException} 的构造器一致。
 *
 * <p>{@code messageArgs} <b>不得包含</b> {@link Throwable}：断言表达的是可预期业务规则，根因应通过
 * {@link BizException#causedBy(ErrorCode, Throwable, Object...)} 携带；混入的参数会被当作文案素材渲染、
 * 令异常细节透出给终端用户，故 {@link BizException} 构造器会对此直接抛出 {@link IllegalArgumentException}。
 *
 * <p>{@code errorCode} 仅在断言失败时被使用：条件成立时传入 {@code null} 不会报错，
 * 断言失败时则由 {@link BizException} 校验并抛出 {@link NullPointerException}。
 */
public final class MydddAssert {

    private MydddAssert() {
    }

    /**
     * 断言条件成立，不成立即抛出 {@link BizException}。
     *
     * @param expression  必须成立的条件，为 {@code false} 时断言失败
     * @param errorCode   错误码，不可为 {@code null}
     * @param messageArgs 渲染错误码文案中 {@code {}} 占位符的参数，可为空
     * @throws BizException {@code expression} 为 {@code false}
     */
    public static void isTrue(boolean expression, ErrorCode errorCode, Object... messageArgs) {
        if (!expression) {
            throw new BizException(errorCode, messageArgs);
        }
    }

    /**
     * 断言对象非 {@code null}，{@code null} 即抛出 {@link BizException}。
     *
     * @param value       待校验对象
     * @param errorCode   错误码，不可为 {@code null}
     * @param messageArgs 渲染错误码文案中 {@code {}} 占位符的参数，可为空
     * @throws BizException {@code value} 为 {@code null}
     */
    public static void notNull(Object value, ErrorCode errorCode, Object... messageArgs) {
        isTrue(value != null, errorCode, messageArgs);
    }

    /**
     * 断言字符串既非 {@code null} 也非空白（{@link String#isBlank()}），否则抛出 {@link BizException}。
     *
     * <p>空白判定沿用 {@link Character#isWhitespace(char)} 语义，因此不换行空格（NBSP，{@code U+00A0}）
     * <b>不算</b>空白，仅有 NBSP 的字符串会被视为合法内容。
     *
     * @param value       待校验字符串
     * @param errorCode   错误码，不可为 {@code null}
     * @param messageArgs 渲染错误码文案中 {@code {}} 占位符的参数，可为空
     * @throws BizException {@code value} 为 {@code null} 或不含任何非空白字符
     */
    public static void notBlank(String value, ErrorCode errorCode, Object... messageArgs) {
        isTrue(value != null && !value.isBlank(), errorCode, messageArgs);
    }

    /**
     * 断言集合非 {@code null} 且至少含一个元素，否则抛出 {@link BizException}。
     *
     * <p>本方法与 {@code Map} 重载对字面量 {@code null} 存在重载歧义，传 {@code null} 必须显式转型：
     * {@code MydddAssert.notEmpty((Collection<?>) null, errorCode)}。
     *
     * @param value       待校验集合
     * @param errorCode   错误码，不可为 {@code null}
     * @param messageArgs 渲染错误码文案中 {@code {}} 占位符的参数，可为空
     * @throws BizException {@code value} 为 {@code null} 或为空集合
     */
    public static void notEmpty(Collection<?> value, ErrorCode errorCode, Object... messageArgs) {
        isTrue(value != null && !value.isEmpty(), errorCode, messageArgs);
    }

    /**
     * 断言映射非 {@code null} 且至少含一个键值对，否则抛出 {@link BizException}。
     *
     * <p>本方法与 {@code Collection} 重载对字面量 {@code null} 存在重载歧义，传 {@code null} 必须显式转型：
     * {@code MydddAssert.notEmpty((Map<?, ?>) null, errorCode)}。
     *
     * @param value       待校验映射
     * @param errorCode   错误码，不可为 {@code null}
     * @param messageArgs 渲染错误码文案中 {@code {}} 占位符的参数，可为空
     * @throws BizException {@code value} 为 {@code null} 或为空映射
     */
    public static void notEmpty(Map<?, ?> value, ErrorCode errorCode, Object... messageArgs) {
        isTrue(value != null && !value.isEmpty(), errorCode, messageArgs);
    }
}
