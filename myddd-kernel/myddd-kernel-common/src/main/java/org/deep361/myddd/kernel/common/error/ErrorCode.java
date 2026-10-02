package org.deep361.myddd.kernel.common.error;

/**
 * 错误码契约：全框架唯一错误标识。
 *
 * <p>编码格式 {@code <域>_<五位数字>}（如 {@code ORDER_40001}），框架内置错误码使用 {@code MYDDD_} 段位。
 * {@code messageKey} 供 i18n 解析器按当前语言环境取最优文案，
 * {@code defaultMessage} 为解析不到资源时的兜底文案。
 *
 * <p>实现约定：实现必须不可变，推荐以枚举实现（天然可序列化且并发安全）。
 * {@code code()} 是稳定的线上标识，一经发布不得变更；其余成员为展示与派发元数据。
 */
public interface ErrorCode {

    /**
     * 错误码格式的单一真源：{@code <大写字母域>_<五位数字>}。
     *
     * <p>此前这条正则只写在一个测试的字面量里，消费方要校验格式只能自己再抄一份，两处漂移无人报警
     * （路线图 :113 的待办，由 Plan 4 裁定公开）。框架内置码一律占 {@code MYDDD_} 段位
     * （见 {@link CommonErrorCode}），业务域用自己的域词，两者共用本 Pattern 校验。
     */
    java.util.regex.Pattern CODE_PATTERN = java.util.regex.Pattern.compile("[A-Z]+_\\d{5}");

    /** 稳定错误码，如 {@code MYDDD_40001}。 */
    String code();

    /** i18n 消息键，如 {@code myddd.error.invalid-parameter}。 */
    String messageKey();

    /**
     * 语义化 HTTP 状态码，取值必须是合法 HTTP 状态码（100–599）。
     *
     * <p>Web 层会将其直接写入 HTTP 响应状态；4xx 表示调用方过错，5xx 表示服务端过错。
     * 非 Web 场景（批处理、MQ 等领域）约定使用 500。
     */
    int httpStatus();

    /**
     * 兜底默认文案，可含 {@code {}} 占位符；框架内置错误码为中文，实现方语言不限。
     *
     * <p>含占位符的文案必须经 {@link #formatDefaultMessage(Object...)} 传入对应参数渲染，
     * 无参渲染会把 {@code {}} 原样留在输出中；替换细则（{@code null} 模板、参数不足、
     * 不支持 {@code \{\}} 转义等）见 {@link PlaceholderRenderer}。
     */
    String defaultMessage();

    /**
     * 用参数渲染兜底文案，等价于 {@code PlaceholderRenderer.render(defaultMessage(), messageArgs)}，
     * 替换规则完全继承 {@link PlaceholderRenderer}。
     *
     * <p>对含占位符的文案不传参数时返回原样模板（占位符保留）。
     */
    default String formatDefaultMessage(Object... messageArgs) {
        return PlaceholderRenderer.render(defaultMessage(), messageArgs);
    }
}
