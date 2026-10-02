package org.deep361.myddd.resilience;

/**
 * 把 {@code proceed()} 抛出的受检异常暂装箱，好让它穿过 {@link java.util.function.Supplier} 这条只
 * 允许 unchecked 的接缝；端口调用结束后由切面 {@code unwrap} 原样重抛。
 *
 * <p>关掉 fillInStackTrace 与 suppression：这只是一个载体，它不该有自己的栈，也不该在被 unwrap 之后
 * 还留在异常链上（内核 javadoc："被保护动作自身的异常原样传播"）。
 *
 * <p><b>provider 侧看到的是本类型而不是业务异常</b>：两家 provider 的失败判定都是"任何异常都算一次失败"
 * （Task 45 的 {@code Tracer.trace(throwable)}、Task 46 的 r4j 缺省记录），不按类型白名单，所以装箱
 * 不会漏记失败率。这条不对称写在这里，是给 Task 45/46 的交接内容，不是本任务的可观察面。
 */
final class ActionThrowableFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Throwable cause;

    ActionThrowableFailure(Throwable cause) {
        super(null, cause, false, false);
        this.cause = cause;
    }

    Throwable cause() {
        return this.cause;
    }
}
