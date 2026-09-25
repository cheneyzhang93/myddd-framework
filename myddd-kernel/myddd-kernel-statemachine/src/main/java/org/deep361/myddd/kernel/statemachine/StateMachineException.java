package org.deep361.myddd.kernel.statemachine;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;

/**
 * 非法状态流转异常：当前状态下不允许执行该事件（无对应转移，或守卫拒绝）。
 *
 * <p>错误码固定为 {@link CommonErrorCode#ILLEGAL_STATE}（HTTP 409），文案模板"当前状态 {} 不允许执行操作 {}"，
 * 两个参数依次为当前状态与事件——领域层不需要为每个状态机准备文案。
 *
 * <p>继承 {@link BizException}：web 层据此映射语义化状态码与 i18n，调用方只需 {@code catch (BizException)}。
 */
public class StateMachineException extends BizException {

    private static final long serialVersionUID = 1L;

    /**
     * @param currentState 当前状态（人类可读对象，如枚举）
     * @param event        被拒绝的事件
     */
    public StateMachineException(Object currentState, Object event) {
        super(CommonErrorCode.ILLEGAL_STATE, currentState, event);
    }

    /**
     * 携带根因的构造器。
     *
     * @param currentState 当前状态
     * @param event        被拒绝的事件
     * @param cause        根因
     */
    public StateMachineException(Object currentState, Object event, Throwable cause) {
        super(CommonErrorCode.ILLEGAL_STATE, cause, new Object[] {currentState, event});
    }

    /**
     * 携带根因的工厂方法（子类专用：{@link BizException#causedBy} 的返回类型是父类，无法直接复用）。
     */
    public static StateMachineException causedBy(Object currentState, Object event, Throwable cause) {
        return new StateMachineException(currentState, event, cause);
    }
}
