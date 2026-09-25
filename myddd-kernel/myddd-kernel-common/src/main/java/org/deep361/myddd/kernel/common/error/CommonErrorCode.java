package org.deep361.myddd.kernel.common.error;

/**
 * 框架内置错误码（{@code MYDDD_} 段位）：通用参数/鉴权/限流/降级/系统级错误。
 *
 * <p>编号规则：{@code MYDDD_<3 位 HTTP 状态码><2 位序号>}，如 {@code MYDDD_40003} 为状态码 400 分组下的第 3 个
 * 错误码，2 位序号用于区分共享同一状态码的多个错误码；{@code messageKey} 遵循 {@code <命名空间>.error.<原因>}，
 * 本枚举命名空间固定为 {@code myddd.error.}。段位覆盖：通用参数、鉴权、限流、熔断降级、状态机、分布式锁、幂等、系统级。
 *
 * <p>业务域错误码由业务工程自行定义枚举实现 {@link ErrorCode}，格式 {@code <域>_<五位数字>}。
 */
public enum CommonErrorCode implements ErrorCode {

    INVALID_PARAMETER("MYDDD_40001", "myddd.error.invalid-parameter", 400, "参数校验失败"),
    MALFORMED_REQUEST("MYDDD_40002", "myddd.error.malformed-request", 400, "请求格式错误"),
    ARGUMENT_INVALID("MYDDD_40003", "myddd.error.argument-invalid", 400, "参数 {} 不合法"),
    UNAUTHORIZED("MYDDD_40101", "myddd.error.unauthorized", 401, "未认证或登录已过期"),
    FORBIDDEN("MYDDD_40301", "myddd.error.forbidden", 403, "无权访问该资源"),
    NOT_FOUND("MYDDD_40401", "myddd.error.not-found", 404, "资源不存在"),
    METHOD_NOT_ALLOWED("MYDDD_40501", "myddd.error.method-not-allowed", 405, "请求方法不支持"),
    CONFLICT("MYDDD_40901", "myddd.error.conflict", 409, "数据状态冲突，请刷新后重试"),
    ILLEGAL_STATE("MYDDD_40902", "myddd.error.illegal-state", 409, "当前状态 {} 不允许执行操作 {}"),
    LOCK_ACQUIRE_FAILED("MYDDD_40903", "myddd.error.lock-acquire-failed", 409, "资源正在处理中，请稍后重试"),
    IDEMPOTENT_CONFLICT("MYDDD_40904", "myddd.error.idempotent-conflict", 409, "重复请求，请勿重复提交"),
    UNSUPPORTED_MEDIA_TYPE("MYDDD_41501", "myddd.error.unsupported-media-type", 415, "不支持的请求媒体类型"),
    TOO_MANY_REQUESTS("MYDDD_42901", "myddd.error.too-many-requests", 429, "请求过于频繁，请稍后重试"),
    SYSTEM_ERROR("MYDDD_50001", "myddd.error.system", 500, "系统内部错误"),
    SERVICE_DEGRADED("MYDDD_50301", "myddd.error.service-degraded", 503, "服务暂时不可用（熔断降级）");

    private final String code;
    private final String messageKey;
    private final int httpStatus;
    private final String defaultMessage;

    CommonErrorCode(String code, String messageKey, int httpStatus, String defaultMessage) {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
