package org.deep361.myddd.kernel.common.response;

import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.deep361.myddd.kernel.common.trace.TraceContext;

import java.util.Objects;

/**
 * 统一响应结构：成功与失败共用 {@code { code, message, data, traceId }}，成功时 {@code code = "OK"}。
 *
 * <p>HTTP 状态码由 web 层按错误码语义化映射，响应体始终为本结构，前端单一拦截器即可处理。
 *
 * <p><b>字段契约</b>：{@code code} 不可为 null（机器可读的判别标识）；{@code message} 不可为 null，
 * 传入 null 会被归一化为空串（{@code ex.getMessage()} 本身可能为 null，前端按字符串处理即可）；
 * {@code data} 允许为 null。
 *
 * <p>{@code traceId} 为 null 时在<b>构造时</b>快照当前线程的 {@link TraceContext#currentTraceId()}，
 * 显式传入的值优先；快照后不再随上下文变化，因此<b>不要缓存长期存活的 {@code Response} 实例</b>
 * （例如静态单例），否则会固化一个过期的 traceId——web 层应在响应写出时构造本对象。
 *
 * @param code    业务结果码，成功为 {@link #OK_CODE}，失败为错误码的 {@code code()}，不可为 null
 * @param message 用户可读文案，成功为空串；失败时由错误码兜底文案渲染而来，不可为 null
 * @param data    业务数据，无数据时为 null
 * @param traceId 链路标识，传入 null 时取当前线程的 traceId（可能仍为 null）
 * @throws NullPointerException {@code code} 为 null
 */
public record Response<T>(String code, String message, T data, String traceId) {

    /** 成功响应的 code 取值。 */
    public static final String OK_CODE = "OK";

    public Response {
        Objects.requireNonNull(code, "code 不能为 null");
        if (message == null) {
            message = "";
        }
        if (traceId == null) {
            traceId = TraceContext.currentTraceId();
        }
    }

    /** 成功响应（无数据）。 */
    public static <T> Response<T> ok() {
        return new Response<>(OK_CODE, "", null, null);
    }

    /** 成功响应（带数据）。 */
    public static <T> Response<T> ok(T data) {
        return new Response<>(OK_CODE, "", data, null);
    }

    /**
     * 失败响应：{@code code} 取自错误码，{@code message} 由错误码兜底文案按参数渲染得到。
     *
     * <p>{@code messageArgs} 的规则与
     * {@link org.deep361.myddd.kernel.common.error.BizException BizException} 一致：
     * <b>不得包含</b> {@link Throwable}——构造时即抛 {@link IllegalArgumentException} 拒绝，避免异常细节
     * 经文案渲染透出给终端用户；
     * 含 {@code {}} 占位符的错误码必须补齐参数，漏传时占位符会原样留在用户可见文案中。
     *
     * <p><b>varargs 陷阱</b>：{@code fail(code, null)} 会被编译器当作"传入 null 数组"即<b>无参数</b>
     * （javac 会给出 non-varargs 调用警告），渲染结果保留 {@code {}}；要渲染一个 null 参数请写
     * {@code fail(code, (Object) null)}。
     *
     * @throws IllegalArgumentException {@code messageArgs} 中包含 {@link Throwable}
     */
    public static <T> Response<T> fail(ErrorCode errorCode, Object... messageArgs) {
        requireNoThrowable(messageArgs);
        return new Response<>(errorCode.code(), errorCode.formatDefaultMessage(messageArgs), null, null);
    }

    /** 失败响应：自定义错误码与文案（用于框架兜底，或不便建模为 {@link ErrorCode} 的场景）。 */
    public static <T> Response<T> fail(String code, String message) {
        return new Response<>(code, message, null, null);
    }

    /** 业务是否成功：{@code code} 等于 {@link #OK_CODE} 时为 true。 */
    public boolean successful() {
        return OK_CODE.equals(code);
    }

    private static void requireNoThrowable(Object[] messageArgs) {
        if (messageArgs == null) {
            return;
        }
        for (Object arg : messageArgs) {
            if (arg instanceof Throwable) {
                throw new IllegalArgumentException(
                        "messageArgs 不能包含 Throwable；根因请记入日志（log.error），不要作为文案素材");
            }
        }
    }
}
