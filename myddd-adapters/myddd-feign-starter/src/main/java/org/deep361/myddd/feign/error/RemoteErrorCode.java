package org.deep361.myddd.feign.error;

import org.deep361.myddd.kernel.common.error.ErrorCode;

import java.util.Objects;

/**
 * 远程服务返回的错误码在本地的只读投影：把对端的 {@code code}、文案与 HTTP 状态原样带进
 * {@link org.deep361.myddd.kernel.common.error.BizException}，并额外记住来源（Feign 的
 * {@code methodKey} 与对端的 {@code traceId}），使排障时能回答"这个错是哪次远程调用给的"。
 *
 * <p><b>状态码取对端原值</b>（不是统一压成 500）：调用链上每一跳把自己看到的状态往上带，
 * 网关与前端才能区分"对端说它没找到（404）"和"这一跳自己超时（504）"。代价是本服务的
 * 全局异常处理会把对端状态直接对外，这是刻意的透明传递。
 *
 * <p><b>文案不做本地渲染</b>：{@link #defaultMessage()} 返回的就是对端写好的句子。远程文案里若含
 * {@code {}}，按内核 {@code BizException} 的约定（未传参数时占位符原样保留）会出现在
 * {@code getMessage()} 里——那是上游给的文本，本框架不去二次加工。
 */
public final class RemoteErrorCode implements ErrorCode {

    /**
     * 固定消息键，且框架<b>不为它提供任何语言包</b>：web 层按本键解析不到资源时会退回
     * {@link #defaultMessage()}（内核 {@code BizException} 的兜底语义），于是"对端写好的那句话"必然胜出。
     * 若为它建包，等于给框架一个覆盖对端文案的机会。
     */
    static final String MESSAGE_KEY = "myddd.error.remote";

    private final String code;
    private final String message;
    private final int httpStatus;
    private final String remoteTraceId;
    private final String methodKey;

    /**
     * @param code          对端错误码，不可为 {@code null} 或空白
     * @param message       对端文案，可为 {@code null}（此时 {@link #defaultMessage()} 退化为按错误码生成的话术）
     * @param httpStatus    对端 HTTP 状态码，原样保留
     * @param remoteTraceId 对端响应里的 traceId，可为 {@code null}（对端未接入链路）
     * @param methodKey     Feign 的 {@code ClassName#method(HttpMethod)} 标识，可为 {@code null}
     * @throws NullPointerException     {@code code} 为 {@code null}
     * @throws IllegalArgumentException {@code code} 为空白
     */
    public RemoteErrorCode(String code, String message, int httpStatus, String remoteTraceId, String methodKey) {
        this.code = requireText(code, "code");
        this.message = message;
        this.httpStatus = httpStatus;
        this.remoteTraceId = remoteTraceId;
        this.methodKey = methodKey;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String messageKey() {
        return MESSAGE_KEY;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String defaultMessage() {
        return message == null || message.isBlank() ? "远程服务返回错误：" + code : message;
    }

    /** 对端响应携带的 traceId；对端未接入链路时为 {@code null}。 */
    public String remoteTraceId() {
        return remoteTraceId;
    }

    /** 产生本错误的 Feign 方法标识，形如 {@code OrderApi#getOrder(GET)}。 */
    public String methodKey() {
        return methodKey;
    }

    @Override
    public String toString() {
        return "RemoteErrorCode[code=" + code + ", httpStatus=" + httpStatus
                + ", methodKey=" + methodKey + ", remoteTraceId=" + remoteTraceId + "]";
    }
}
