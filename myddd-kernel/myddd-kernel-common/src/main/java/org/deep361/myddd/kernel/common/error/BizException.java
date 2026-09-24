package org.deep361.myddd.kernel.common.error;

import java.util.Objects;

/**
 * 业务可预期异常：由 {@link ErrorCode} 标识，web 层将据此映射语义化 HTTP 状态码并做 i18n 解析。
 *
 * <p>异常 {@code message} 为错误码的 {@link ErrorCode#defaultMessage()}；传入 {@code messageArgs} 时经
 * {@link ErrorCode#formatDefaultMessage(Object...)} 渲染。构造时渲染的是兜底文案、不感知语言环境，
 * web 层可凭 {@link #getMessageArgs()} 与 {@link ErrorCode#messageKey()} 按当前语言环境重新解析文案。
 *
 * <p><b>子类契约</b>：错误身份由 {@link ErrorCode} 表达、而不是由异常类型表达；异常类型只服务于 {@code catch}
 * 的可读性，{@link #getErrorCode()} 才是机器可读的身份标识，因此子类必须在构造时提供错误码。子类需要携带根因
 * 时应通过受保护构造器 {@link #BizException(ErrorCode, Throwable, Object[])} 传入 {@code cause}；
 * {@link #causedBy(ErrorCode, Throwable, Object...)} 是静态工厂，无法被覆写、也不参与子类的构造契约，子类如需工厂方法须自行声明。
 *
 * <p>携带根因请走受保护构造器或静态工厂 {@link #causedBy(ErrorCode, Throwable, Object...)}：本类按设计不提供
 * 公开的 {@code (ErrorCode, Throwable)} 构造器；若把 {@link Throwable} 混入 {@code messageArgs}，构造时立即抛出
 * {@link IllegalArgumentException}，以免异常细节经文案渲染（web 层将据 {@code messageKey} 重新解析）透出给终端用户。
 *
 * <p>序列化：本异常可序列化，{@code errorCode} 与 {@code messageArgs} 均随异常一同序列化，因此二者<b>都必须
 * 可序列化</b>（框架内置错误码为枚举，天然满足；{@code messageArgs} 的元素也必须可序列化，否则序列化失败）。
 * 两个字段按设计均<b>不做</b> {@code transient}：反序列化后 web 层仍需凭 {@link ErrorCode#messageKey()} 与
 * {@link #getMessageArgs()} 重新解析多语言文案，字段若被丢弃将无法还原。{@code serialVersionUID} 固定为
 * {@code 1L}，保证 1.x 内序列化线格式稳定。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Object[] messageArgs;

    /**
     * 以错误码与消息参数构造异常。
     *
     * <p>未传参数时 {@code message} 即 {@link ErrorCode#defaultMessage()}；传入参数时经
     * {@link ErrorCode#formatDefaultMessage(Object...)} 渲染。含 {@code {}} 占位符的错误码<b>未传参数</b>时
     * 占位符会原样保留在 {@link #getMessage()} 中（属刻意的宽松行为，与 {@link ErrorCode} 的约定一致）：
     * 调用方必须补齐参数，否则用户将看到形如 {@code 参数 {} 不合法} 的文案。
     *
     * <p><b>varargs 陷阱</b>：{@code new BizException(code, null)} 会被编译器当作"传入 null 数组"即<b>无参数</b>
     * （javac 会给出 non-varargs 调用警告），渲染结果保留 {@code {}}、{@link #getMessageArgs()} 返回空数组；
     * 要渲染一个 {@code null} 参数请写 {@code new BizException(code, (Object) null)}（渲染为
     * {@code 参数 null 不合法}）。确需无参数时请只传 {@code errorCode}，或显式传入 {@code (Object[]) null}。
     *
     * @param errorCode   错误码，不可为 {@code null}
     * @param messageArgs 渲染兜底文案的占位符参数，可为空；内部防御性拷贝
     * @throws NullPointerException     {@code errorCode} 为 {@code null}
     * @throws IllegalArgumentException {@code messageArgs} 含 {@link Throwable} 元素
     */
    public BizException(ErrorCode errorCode, Object... messageArgs) {
        this(require(errorCode), null, messageArgs);
    }

    /**
     * 携带根因的受保护构造器：子类携带根因的唯一入口，调用形如
     * {@code super(errorCode, cause, messageArgs)}。
     *
     * <p>{@code message} 的渲染语义与 {@link #BizException(ErrorCode, Object...)} 一致；
     * {@code messageArgs} 会被规范化（{@code null} 视为空数组）并做防御性拷贝，其中的
     * {@link Throwable} 元素会被拒绝。
     *
     * @param errorCode   错误码，不可为 {@code null}
     * @param cause       根因，可为 {@code null}（等价于无根因）
     * @param messageArgs 渲染兜底文案的占位符参数，可为 {@code null}
     * @throws NullPointerException     {@code errorCode} 为 {@code null}
     * @throws IllegalArgumentException {@code messageArgs} 含 {@link Throwable} 元素
     */
    protected BizException(ErrorCode errorCode, Throwable cause, Object[] messageArgs) {
        super(resolveMessage(require(errorCode), messageArgs), cause);
        this.errorCode = errorCode;
        this.messageArgs = validateArgs(messageArgs);
    }

    /**
     * 携带根因的业务异常：{@code cause} 作为异常 cause，{@code messageArgs} 的渲染语义同构造器。
     *
     * <p>本方法为静态工厂，不参与继承；子类携带根因请使用受保护构造器。
     *
     * @param errorCode   错误码，不可为 {@code null}
     * @param cause       根因
     * @param messageArgs 渲染兜底文案的占位符参数，可为空；内部防御性拷贝
     * @return 携带根因与错误码的异常实例
     * @throws NullPointerException     {@code errorCode} 为 {@code null}
     * @throws IllegalArgumentException {@code messageArgs} 含 {@link Throwable} 元素
     */
    public static BizException causedBy(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        return new BizException(require(errorCode), cause, messageArgs);
    }

    /** 返回触发本异常的错误码。 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * i18n 解析所需的格式化参数（防御性拷贝），供 web 层依据 {@link ErrorCode#messageKey()} 重新渲染文案。
     *
     * <p>返回的是<b>浅拷贝</b>：数组本身不可被外部修改，但元素仍指向原对象。参数元素应为不可变值对象，
     * 否则元素在构造后被修改会使 {@link #getMessage()} 与 {@link #getMessageArgs()} 的渲染结果不一致。
     *
     * <p>空数组<b>不能</b>区分"该错误码没有占位符"与"调用方漏传参数"：对含 {@code {}} 的错误码漏传参数时，
     * {@link #getMessage()} 会保留字面量 {@code {}}（如 {@code 参数 {} 不合法}），据此可识别后者。
     */
    public Object[] getMessageArgs() {
        return messageArgs.clone();
    }

    private static ErrorCode require(ErrorCode errorCode) {
        return Objects.requireNonNull(errorCode, "errorCode 不能为 null");
    }

    /**
     * 规范化消息参数：{@code null} 视为空数组，其余做防御性拷贝；拒绝 {@link Throwable}，
     * 引导调用方改用携带根因的入口，避免异常细节被当作参数渲染进用户可见文案。
     */
    private static Object[] validateArgs(Object[] messageArgs) {
        if (messageArgs != null) {
            for (Object arg : messageArgs) {
                if (arg instanceof Throwable) {
                    throw new IllegalArgumentException(
                            "messageArgs 不能包含 Throwable；携带根因请使用 BizException.causedBy(ErrorCode, Throwable, Object...)");
                }
            }
        }
        return messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    private static String resolveMessage(ErrorCode errorCode, Object[] messageArgs) {
        return messageArgs == null || messageArgs.length == 0
                ? errorCode.defaultMessage()
                : errorCode.formatDefaultMessage(messageArgs);
    }
}
