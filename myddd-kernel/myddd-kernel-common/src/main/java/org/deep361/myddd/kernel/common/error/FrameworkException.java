package org.deep361.myddd.kernel.common.error;

/**
 * 框架内部异常：装配错误、契约使用错误、“不应发生”分支；web 层将统一映射 500 且不透出堆栈。
 *
 * <p>使用边界：用户输入/校验失败等可预期错误请使用 {@link BizException}，将由适配层按
 * {@link ErrorCode#httpStatus()} 区分 4xx/5xx；把可预期错误混入本类会让该 4xx/5xx 划分失真。
 * 本类只用于框架自身装配失败、契约被误用以及“不应发生”的分支。
 *
 * <p>序列化：本异常可序列化；{@code serialVersionUID} 固定为 {@code 1L}，保证 1.x 内序列化线格式稳定。
 */
public class FrameworkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 以描述信息构造异常，无根因。
     *
     * @param message 描述信息
     */
    public FrameworkException(String message) {
        super(message);
    }

    /**
     * 以描述信息与根因构造异常。
     *
     * @param message 描述信息
     * @param cause   根因
     */
    public FrameworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
